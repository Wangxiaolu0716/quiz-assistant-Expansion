#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把小飞机网盘（飞极盘）里的 APK 上传到指定文件夹。

做法照抄课表项目的 tools/upload_feijipan.ps1 —— 走网盘自己的接口，不是浏览器自动化：

    1. POST /app/user/info/map        拿 userId / account
    2. POST /app/7n/getUpToken        拿七牛上传凭证（upToken 为 -1 表示秒传）
    3. POST https://up.qiniup.com/    表单上传，服务端回调自动登记到目录

鉴权只用 query 参数 appToken（网页登录后 localStorage 里那个）。
用法：

    export FEIJIPAN_TOKEN=<appToken>
    python tools/upload_feijipan.py build/app-release.apk
    python tools/upload_feijipan.py app.apk --folder 1982065881 --name quizassist-2.5.8.apk

坑（踩过的）：
    · fileSize 单位是 MB（整数），传字节会被判成超大文件拒收
    · 必须带 pathKey，格式 storage/files/yyyy/MM/dd/<账号末位>/<account>/<毫秒>.gz
    · md5 必须是这个文件的真实 md5，服务端要校验
    · upToken == "-1" 时说明内容已存在，脚本直接跳过（此时不会新建目录条目）
"""

import argparse
import hashlib
import json
import os
import sys
import time
import uuid
import urllib.request
import urllib.error
from datetime import datetime, timezone

API = "https://api.feijipan.com"
QINIU = "https://up.qiniup.com/"
COMMON = "extra=2&devType=6"

# 「答题助手」文件夹
DEFAULT_FOLDER = "1982065881"


def post_json(path, body, token):
    url = "%s%s?appToken=%s&%s" % (API, path, token, COMMON)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Origin": "https://www.feijipan.com",
            "User-Agent": "Mozilla/5.0",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def file_md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def upload_to_qiniu(uptoken, key, path):
    """multipart/form-data：token + key + file。"""
    boundary = "----qiniu" + uuid.uuid4().hex
    filename = os.path.basename(key)

    head = (
        "--%s\r\nContent-Disposition: form-data; name=\"token\"\r\n\r\n%s\r\n"
        "--%s\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\n%s\r\n"
        "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
        "Content-Type: application/octet-stream\r\n\r\n"
        % (boundary, uptoken, boundary, key, boundary, filename)
    ).encode("utf-8")
    tail = ("\r\n--%s--\r\n" % boundary).encode("utf-8")

    size = os.path.getsize(path)
    total = len(head) + size + len(tail)

    class Body:
        def __init__(self):
            self.buf = head
            self.fh = open(path, "rb")
            self.sent_head = False
            self.sent_file = False
            self.sent_tail = False

        def read(self, n=-1):
            if not self.sent_head:
                self.sent_head = True
                return self.buf
            if not self.sent_file:
                chunk = self.fh.read(n if n and n > 0 else 1 << 20)
                if chunk:
                    return chunk
                self.sent_file = True
                self.fh.close()
            if not self.sent_tail:
                self.sent_tail = True
                return tail
            return b""

    req = urllib.request.Request(
        QINIU, data=Body(), method="POST",
        headers={
            "Content-Type": "multipart/form-data; boundary=%s" % boundary,
            "Content-Length": str(total),
        },
    )
    with urllib.request.urlopen(req, timeout=1800) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", help="要上传的 apk 路径")
    ap.add_argument("--folder", default=DEFAULT_FOLDER, help="目标文件夹 id")
    ap.add_argument("--name", default="", help="网盘里显示的文件名")
    ap.add_argument("--token", default="", help="appToken，默认读 FEIJIPAN_TOKEN 环境变量")
    args = ap.parse_args()

    token = args.token or os.environ.get("FEIJIPAN_TOKEN", "")
    if not token:
        sys.exit("缺少 appToken：设置环境变量 FEIJIPAN_TOKEN，或传 --token")

    apk = os.path.abspath(args.apk)
    if not os.path.isfile(apk):
        sys.exit("文件不存在：%s" % apk)

    name = args.name or os.path.basename(apk)
    size_bytes = os.path.getsize(apk)
    size_mb = int(round(size_bytes / 1048576.0)) or 1
    md5 = file_md5(apk)

    print("文件   %s" % name)
    print("大小   %.1f MB（上报 %d MB）" % (size_bytes / 1048576.0, size_mb))
    print("md5    %s" % md5)

    user = post_json("/app/user/info/map", {}, token)
    if user.get("code") != 200:
        sys.exit("取用户信息失败：%s（appToken 可能失效）" % user.get("msg"))
    info = user.get("map") or {}
    user_id = info.get("userId")
    account = str(info.get("account") or "")
    print("账号   %s（userId=%s）" % (info.get("userName"), user_id))

    now = datetime.now(timezone.utc)
    storage_key = "storage/files/%s/%s/%s/%s/%s/%d.gz" % (
        now.strftime("%Y"), now.strftime("%m"), now.strftime("%d"),
        account[-1] if account else "0", account,
        int(time.time() * 1000),
    )

    up = post_json("/app/7n/getUpToken", {
        "fileName": name,
        "fileType": 1,
        "userId": user_id,
        "md5": md5,
        "pathKey": storage_key,
        "fileSize": str(size_mb),
        "folderId": int(args.folder),
        "fileId": "",
    }, token)
    if up.get("code") != 200:
        sys.exit("取上传凭证失败：%s" % up.get("msg"))

    uptoken = up.get("upToken")
    if uptoken == "-1":
        print("秒传：网盘上已有同一内容，跳过上传")
        print("注意：秒传不会在目标文件夹里新建条目，需要网页确认文件是否可见")
        return 0

    print("上传中 …")
    started = time.time()
    res = upload_to_qiniu(uptoken, storage_key, apk)
    elapsed = time.time() - started
    if res.get("code") != 200:
        sys.exit("上传失败：%s" % json.dumps(res, ensure_ascii=False))

    print("上传完成 %.1fs   %.2f MB/s" % (elapsed, size_bytes / 1048576.0 / max(elapsed, 0.1)))
    print("文件 id %s" % res.get("token", "")[:24])
    print("分享页 https://share.feijipan.com/s/xbxbXQzH （提取码 1006）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
