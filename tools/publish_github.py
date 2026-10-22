#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 APK 发到 GitHub Release。

只依赖标准库。凭据从 .env 读（GITHUB_TOKEN / GITHUB_REPO），也可用参数或环境变量覆盖。

用法：
    python tools/publish_github.py app-release.apk --tag v2.5.8
    python tools/publish_github.py app-release.apk --tag v2.5.8 --notes "修复了 xxx"
    python tools/publish_github.py app-release.apk --tag v2.5.8 --notes-file docs/CHANGELOG-2.5.7.md

做的事：
    1. 读取 .env
    2. 查 tag 对应的 release 是否已存在（存在就复用，不重复建）
    3. 不存在则创建 release（tag 由 GitHub 自动指向默认分支最新提交）
    4. 上传 APK 作为附件（同名附件会先删再传，可反复执行）
    5. 打印 release 页面地址

坑：
    · release 的 tag 必须是「已存在的 tag 或合法的新 tag 名」，这里交给 GitHub 自动创建；
      如果你希望它指向特定提交，先手动 git tag + push。
    · 附件上传走 uploads.github.com，不走 api.github.com，域名不能替换。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"

# 本机访问 GitHub 走代理；git 的 proxy 配置 urllib 不读，得自己装上。
# 优先级：环境变量 HTTPS_PROXY / https_proxy -> 默认 127.0.0.1:7890（如果有监听）。
def _opener():
    handlers = []
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or os.environ.get("HTTP_PROXY")
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"https": proxy, "http": proxy}))
    return urllib.request.build_opener(*handlers)


OPENER = _opener()


def load_env():
    """读项目根目录的 .env，不覆盖已存在的环境变量。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(root, ".env")
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            if key and key not in os.environ:
                os.environ[key] = value.strip()


def request(method, url, token, data=None, content_type="application/json", raw=False):
    body = None
    if data is not None:
        body = data if raw else json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "quizassist-publisher")
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        # 附件上传单独放宽：50MB 走代理常常要几分钟
        timeout = 1800 if raw else 120
        with OPENER.open(req, timeout=timeout) as resp:
            payload = resp.read()
            if not payload:
                return {}
            try:
                return json.loads(payload.decode("utf-8"))
            except json.JSONDecodeError:
                return {"raw": payload.decode("utf-8", "replace")}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise RuntimeError("GitHub API %s %s 失败：HTTP %s %s" % (method, url, exc.code, detail[:400]))


def find_release(repo, tag, token):
    try:
        return request("GET", "%s/repos/%s/releases/tags/%s" % (API, repo, tag), token)
    except RuntimeError as exc:
        if "HTTP 404" in str(exc):
            return None
        raise


def create_release(repo, tag, name, notes, prerelease, token):
    body = {
        "tag_name": tag,
        "name": name or tag,
        "body": notes or "",
        "draft": False,
        "prerelease": bool(prerelease),
    }
    return request("POST", "%s/repos/%s/releases" % (API, repo), token, body)


def delete_asset(repo, asset_id, token):
    try:
        request("DELETE", "%s/repos/%s/releases/assets/%d" % (API, repo, asset_id), token)
    except RuntimeError:
        pass


def upload_asset(release, path, token):
    name = os.path.basename(path)
    # 同名附件先删掉，方便同 tag 反复重传
    for asset in release.get("assets", []):
        if asset.get("name") == name:
            delete_asset(release["_repo"], asset["id"], token)

    upload_url = release["upload_url"].split("{")[0]
    url = "%s?name=%s" % (upload_url, urllib.parse.quote(name))
    with open(path, "rb") as fh:
        payload = fh.read()
    return request(
        "POST", url, token, payload,
        content_type="application/vnd.android.package-archive", raw=True,
    )


def main():
    load_env()

    ap = argparse.ArgumentParser()
    ap.add_argument("apk", help="要上传的 apk")
    ap.add_argument("--tag", required=True, help="版本 tag，例如 v2.5.8")
    ap.add_argument("--name", default="", help="release 标题，默认用 tag")
    ap.add_argument("--notes", default="", help="更新说明")
    ap.add_argument("--notes-file", default="", help="从文件读更新说明")
    ap.add_argument("--prerelease", action="store_true", help="标记为预发布")
    ap.add_argument("--repo", default="", help="owner/repo，默认读 GITHUB_REPO")
    ap.add_argument("--token", default="", help="GitHub token，默认读 GITHUB_TOKEN")
    args = ap.parse_args()

    token = args.token or os.environ.get("GITHUB_TOKEN", "")
    repo = args.repo or os.environ.get("GITHUB_REPO", "")
    if not token:
        sys.exit("缺少 GITHUB_TOKEN：写进 .env，或用 --token 传")
    if not repo or "/" not in repo:
        sys.exit("缺少 GITHUB_REPO（形如 owner/repo）：写进 .env，或用 --repo 传")

    apk = os.path.abspath(args.apk)
    if not os.path.isfile(apk):
        sys.exit("文件不存在：%s" % apk)

    notes = args.notes
    if args.notes_file:
        with open(args.notes_file, "r", encoding="utf-8") as fh:
            notes = fh.read()
    if not notes:
        notes = "问题修复与体验优化"

    import urllib.parse  # upload_asset 里用到，延迟导入避免顶部污染

    print("仓库   %s" % repo)
    print("tag    %s" % args.tag)
    print("文件   %s（%.1f MB）" % (os.path.basename(apk), os.path.getsize(apk) / 1048576.0))

    release = find_release(repo, args.tag, token)
    if release:
        print("已存在 release #%s，复用" % release.get("id"))
    else:
        release = create_release(repo, args.tag, args.name or args.tag, notes, args.prerelease, token)
        print("已创建 release #%s" % release.get("id"))

    release["_repo"] = repo
    started = time.time()
    asset = upload_asset(release, apk, token)
    print("已上传附件 %.1fs  %s" % (time.time() - started, asset.get("browser_download_url", "")))
    print("页面 %s" % release.get("html_url", ""))
    return 0


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  （upload_asset 需要在模块级可见）
    sys.exit(main())
