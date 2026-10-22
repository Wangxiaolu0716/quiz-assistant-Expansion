# 远程配置 / 公告接口契约（v2）

面向 `QuizAssist` 2.5.7+。旧版客户端只认 Gitee 上那一份平铺 JSON，新版客户端变成：

```
启动 → 读本地缓存（立即渲染）→ 后台请求主源 → 备源 → 缓存 → 内置默认值
```

> **2026-10-22 起 Gitee 备源已下线**：`CONFIG_FALLBACK_URL` 置空，
> 客户端只剩「主源 → 本地缓存 → 内置默认值」三层。原来的 Gitee 静态 JSON 不再维护。

主源是你自己的服务器。地址在 `app/build.gradle.kts` 的 `buildConfigField` 里。

---

## 1. 拉取配置

```
GET /api/v1/client/config
Accept: application/json
If-None-Match: "<上次返回的 ETag>"      # 可选，服务端支持就发
```

响应：

| 情况 | 状态码 | 说明 |
| --- | --- | --- |
| 内容有变 | 200 | 返回完整 JSON，带 `ETag` |
| 内容未变 | 304 | 空 body，客户端直接刷新本地时间戳 |
| 出错 | 4xx/5xx | 客户端自动降级到备源 |

建议响应头：

```
Content-Type: application/json; charset=utf-8
Cache-Control: no-cache
ETag: "config-v17"
```

> 千万不要给这个接口加 CDN 长缓存。公告的意义就是"现在就要看到"。
> 支持 `ETag` 之后，请求流量几乎为零，但延迟是实时的。

### 1.1 完整字段

```jsonc
{
  "configVersion": 17,

  // ---- 界面文案（可热改，不用发版）----
  "appTitle": "小多答题助手拓展版@cjzwwxl",
  "subtitle": "截图识题、快速回答、联网搜索、题库检索",
  "securityNotice": "应用不会上传你的密钥。",
  "setupGuide": "密钥可以不用填 已内置题库 按步骤开始即可",
  "sponsorTitle": "我想赞助",
  "sponsorText": "感谢支持！",
  "sponsorHint": "长按图片即可保存到相册",

  // ---- 公告：数组，客户端按顺序一条条弹 ----
  "announcements": [
    {
      "id": "2026-10-22-server-online",   // 唯一 ID，改内容就换 ID
      "title": "服务器已上线",
      "content": "公告和题库更新已切到自有服务器，加载更快。",
      "level": "normal",                  // normal | important | force
      "once": true,                       // true=点过「不再提醒」永久不再弹
      "buttonText": "",                   // 留空=「确认」
      "linkUrl": "",                      // 有值则按钮变成「查看详情」
      "minVersionCode": 0,                // 只对 >= 该版本展示
      "maxVersionCode": 0,                // 只对 <= 该版本展示（发新版前给老版留话）
      "startAt": 0,                       // Unix 秒，0=不限
      "endAt": 0
    }
  ],

  // ---- 版本更新 ----
  "latestVersionCode": 20507,
  "latestVersionName": "2.5.7",
  "downloadUrl": "https://your-domain.com/download/quizassist-2.5.7.apk",
  "updateContent": "1. 公告接口迁移到自有服务器\n2. 提示词重写，选项误判降低",
  "minSupportedVersionCode": 20500,     // 低于它强制更新，弹窗不可取消
  "apkSha256": "",                       // 可选，更新前校验

  // ---- 远程开关 ----
  "features": {
    "enableSearchDefault": true,
    "enableVision": true,
    "maxWaitSeconds": 0,                 // 0=用客户端默认
    "captureIntervalSeconds": 0,
    "promptProfile": "v2",
    "disableQuestionBank": false,
    "enableReport": true,
    "allowQuestionTelemetry": false
  },

  // ---- 远程提示词：留空则用客户端内置 ----
  "prompt": {
    "version": 2,
    "flashSystem": "",
    "searchSystem": "",
    "maxTokens": 96,
    "temperature": 0.1
  },

  // ---- 旧版单条公告字段，发版过渡期保留 ----
  "announcementId": "",
  "announcementTitle": "",
  "announcementContent": ""
}
```

字段可以只发一部分，客户端所有字段都有默认值。**推荐只发变化的字段**：

```json
{
  "configVersion": 18,
  "announcements": [
    { "id": "a-18", "title": "维护通知", "content": "今晚 23:00 维护 30 分钟", "level": "important" }
  ]
}
```

### 1.2 公告行为对照

| 配置 | 客户端表现 |
| --- | --- |
| `level: normal` | 「确认」+「不再提醒」，点确认本次关闭 |
| `level: important` | 同上，标题加粗程度更高（预留） |
| `level: force` | 只有「我知道了」，点空白/返回键无效 |
| `once: true` | 点「不再提醒」后，同 ID 永不再弹 |
| `once: false` | 每次启动都弹（适合置顶须知） |
| `startAt` / `endAt` | 到时间自动生效/失效，不用再发一版配置 |
| `min/maxVersionCode` | 定向推送：只给某几个版本的用户看 |

### 1.3 版本更新行为

| 条件 | 表现 |
| --- | --- |
| `latestVersionCode <= 当前版本` | 不弹 |
| `latestVersionCode > 当前版本` | 弹，可「取消」，取消后同版本不再打扰 |
| `minSupportedVersionCode > 当前版本` | 弹，无「取消」，点空白无效，只能去更新 |

---

## 2. 匿名上报（可选）

```
POST /api/v1/client/report
Content-Type: application/json

{
  "event": "app_launch",           // app_launch | announcement_ack | update_click
  "versionCode": 20507,
  "versionName": "2.5.7",
  "deviceId": "随机 24 位，安装时生成",   // 非 ANDROID_ID，不含个人信息
  "at": 1761100000000,
  "extra": { "source": "primary", "fromCache": "false" }
}
```

服务端返回 200 即可，body 随意。客户端失败静默，不重试、不阻塞。

`features.enableReport = false` 可整体关闭上报。

---

## 3. 兼容性

- 旧版客户端（<= 2.5.6）不认识 `announcements`，但它读 `announcementId/Title/Content`
  三个平铺字段，所以**过渡期把重要公告同时写两份**即可人人可见。
- 新版客户端优先读 `announcements`，为空才回退到平铺字段。
- 2.5.7 起不再有 Gitee 备源，服务器短时故障时客户端用本地缓存（最长 6 小时内的那份）。

---

## 4. 部署检查清单

1. `GET /api/v1/client/config` 返回 `200` + `Content-Type: application/json`。
2. 返回头有 `Cache-Control: no-cache`，没有 CDN 长缓存。
3. `ETag` 生效：带 `If-None-Match` 时返回 `304`。
4. APK 下载链接是直链（`Content-Disposition: attachment`），不要跳转网页。
5. HTTPS 证书有效（Android 7+ 默认不信任用户证书，明文 HTTP 在 targetSdk 35 会被拦）。
6. 改完配置后，用手机飞行模式开关一次验证缓存降级：断网启动应仍显示上次公告。
7. 服务器宕机演练：`systemctl stop quizassist-config` 后启动 App，应仍能读到缓存的公告和版本信息。

---

## 5. 提示词远程下发怎么用

`prompt.flashSystem` / `prompt.searchSystem` 一旦非空，会**完全替换**客户端内置提示词。
调试新提示词的标准流程：

1. 手机上先用内置提示词跑 20 道已知答案的题，记录准确率。
2. 把新提示词写进 `prompt.flashSystem`，`prompt.version` 加一，发布配置。
3. 重启 App（或等下次启动）即生效，不用重新打包安装。
4. 对比准确率；不行就清空字段回滚，秒级恢复。

`prompt.maxTokens` 默认 96，两行短答案够用；如果新提示词要求更长的解析，调到 160～200。
`prompt.temperature` 留空则沿用用户在设置里的值（默认 0.1）。
