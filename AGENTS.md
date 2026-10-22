# AGENTS.md · 项目备忘

> 给下一个接手的人（或者下一次会话的我）看的东西。改代码前先读这页。

---

## 一句话

Android 截图答题工具。系统截图 → 本地 OCR → 用户自配的 OpenAI 兼容接口 → 悬浮窗显示答案。
不做自动点击、不读内存、不抓包。

---

## 仓库身份

| 项 | 值 |
| --- | --- |
| **代码仓库** | `Wangxiaolu0716/quiz-assistant-Expansion`（Android 版，就是这个项目） |
| 旧仓库 | `Wangxiaolu0716/AutoAnswer_REDLAND` —— **Python 原型**，不要往里推 Kotlin 代码 |
| 本地路径 | `D:\projects\QuizAssist\` |
| 原始备份 | `D:\downloads\答题助手\`（未改动，出问题从这恢复） |
| 包名 | `com.wangxiaolu.quizassist`（namespace 仍是 `com.quizassist`） |

### GitHub 凭据

**本机 `gh` CLI 已登录**（`Wangxiaolu0716`，OAuth token 存在 keyring，scopes 含 `repo`）。

```bash
export PATH="$PATH:/c/Program Files/GitHub CLI"
gh auth token          # 40 字符 gho_ 开头
```

`git push` 直接用（credential helper 已指向 `gh auth git-credential`）。
`tools/publish_github.py` 的 `GITHUB_TOKEN` 也从这取，见 `.env`。

**代理**：本机访问 GitHub 走 `http://127.0.0.1:7890`。
git 已配 `http.proxy`；Python 脚本要读 `.env` 里的 `HTTPS_PROXY`（脚本已支持）。

---

## 构建

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot"
GRADLE=$(ls -d /c/Users/Administrator/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle | head -1)
"$GRADLE" --no-daemon -p /d/projects/QuizAssist -PskipLint=true assembleRelease
```

约 50 秒。产物 `app/build/outputs/apk/release/app-release.apk`（50 MB）。

签名读 `signing.properties` + `release.keystore`，两者都在 `.gitignore` 里，别提交。

已知无害警告：`OcrProcessor.kt:49 Variable 'latinCount' is never used`。

---

## 发版 = 三处

```bash
# 1. 服务器直链（后台上传接口，30 秒，自动算 sha 并回填配置）
curl -u admin:841117Dqc -H "X-Admin-Token: 841117Dqc" \
     -H "Content-Type: application/octet-stream" \
     --data-binary @app-release.apk \
     "https://xuhuohua.xyz/quiz-api/admin/upload?filename=quizassist-2.5.8.apk"

# 2. 网盘
python tools/upload_feijipan.py app-release.apk --name quizassist-2.5.8.apk --folder 1982065881

# 3. GitHub Release
python tools/publish_github.py app-release.apk --tag v2.5.8 --notes "更新说明"
```

最后到后台 `https://xuhuohua.xyz/quiz-admin/` 改 `latestVersionCode` / `updateContent` / `apkSha256` 保存。

下载源两个都留着：`downloadUrl` = 网盘（主），`downloadUrlAlt` = 服务器直链（慢，兜底）。

---

## 关键路径

### 本地

| 路径 | 说明 |
| --- | --- |
| `.env` | **所有凭据**：SSH、后台 token、网盘 token、GitHub token、代理 |
| `tools/upload_feijipan.py` | 网盘上传（纯 API + 七牛直传，无依赖） |
| `tools/publish_github.py` | GitHub Release + 附件上传 |
| `server/index.js` | 零依赖 Node 服务：配置下发 / 上报 / 管理 / 上传 / 统计 |
| `server/public/admin.html` | 网页面板（单文件） |
| `docs/文件清单.md` | 全部落点一页看完 |
| `docs/remote-config-api.md` | 接口契约 |
| `docs/服务器部署.md` | 部署运维手册 |

### 服务器 `121.43.151.118`（阿里云，root / 密码在 `.env`）

| 路径 | 说明 |
| --- | --- |
| `/opt/quizassist-config/` | 服务本体 + 配置 + 面板 |
| `/etc/systemd/system/quizassist-config.service` | 监听 `127.0.0.1:18080` |
| `/etc/nginx/conf.d/xuhuohua.conf` | 4 段 location（见下） |
| `/etc/nginx/quizassist.htpasswd` | 后台 Basic Auth（`admin`） |
| `/var/www/quizassist/` | APK 直链目录 |
| `/var/log/quizassist-config.log` | 日志 |

nginx 四段：`/quiz-api/client/`（无鉴权）、`/quiz-api/admin/`（Basic Auth + `client_max_body_size 300m`）、`/quiz-admin/`（面板）、`/quizassist/`（直链）。

---

## 线上地址

| 用途 | 地址 |
| --- | --- |
| 面板 | `https://xuhuohua.xyz/quiz-admin/`（`admin` / `841117Dqc`，进页面再填同一个 `ADMIN_TOKEN`） |
| 客户端配置 | `https://xuhuohua.xyz/quiz-api/client/config` |
| 匿名上报 | `https://xuhuohua.xyz/quiz-api/client/report` |
| 统计 | `https://xuhuohua.xyz/quiz-api/admin/stats` |
| 网盘 | `https://share.feijipan.com/s/xbxbXQzH`（提取码 `1006`） |

只有 `xuhuohua.xyz` 和 `nbcc.xuhuohua.xyz` 有证书，没有通配符证书——所有端点必须挂在这两个域名下走**路径**区分。

---

## 踩过的坑（别再踩）

### 1. 远程配置 6 小时不刷新

`RemoteConfig.refresh()` 原来有个 `CACHE_TTL_MS` 短路：缓存没过期就直接返回，根本不发请求。
后果是后台改了 `setupGuide` 手机端永远看不到。
现在改成：每次启动必发一次带 `If-None-Match` 的请求，304 只更新时间戳，另加 5 秒防抖防重复调用。

### 2. nginx 50MB 上传 413

`/quiz-api/admin/upload` 命中的是更长的前缀 `location /quiz-api/admin/`，那边继承 server 级 `30m`。
只给 `/quiz-api/` 加 `client_max_body_size` 没用。
必须在这个 admin 块内单独写 `client_max_body_size 300m; proxy_request_buffering off;`。

### 3. nginx 模板会把手工改动冲掉

`/opt/kebiao-site/deploy/agent.py` 是全文件覆盖式渲染，模板是 `/opt/kebiao-site/deploy/xuhuohua.conf.txt`。
改 nginx 必须：**改线上 → `cp` 线上回模板 → `nginx -t` → reload**。
别直接跑 `agent.py nginx`，会把 quizassist 的四段 location 全删掉。

### 4. Basic Auth 覆盖 Authorization 头

浏览器填了 Basic Auth 后，`Authorization: Bearer xxx` 会被冲掉，管理接口 401。
所以管理接口优先读 `x-admin-token` 自定义头，Bearer 只作 curl 兜底。

### 5. 题库表头误判（已修）

表头关键词里有单字母 `a` / `q`，最初用「包含」判断。
结果 `A.派蒙 B.钟离 C.安柏 D.凯亚` 这种选项串因为含 `a` 被当成表头，整张表按表头规则解析，答案列取到选项列。
现在：长度 ≤2 的关键词必须完全相等，表头单元格限长 12 字。

### 6. 飞极盘秒传不进目录

`getUpToken` 返回 `upToken == "-1"` 表示内容已存在，客户端跳过上传——此时**不会在目标文件夹建条目**。
判断"到底传没传上去"不能只看接口返回，要去分享页看。
`fileSize` 单位是 **MB 整数**，传字节会被判成超大文件拒收。

### 7. Python on Windows 的 /tmp 不一致

bash 的 `/tmp` 是 `C:\Users\Administrator\AppData\Local\Temp`，python 的 `/tmp` 是 `<当前盘>:\tmp`。
脚本临时文件一律放项目内 `.tmp/`，别用 `/tmp`。

### 8. 中文路径的 python 转义

写脚本时 `"D:\projects\..."` 里的 `\u` 会触发 unicodeescape 错误。
用正斜杠或 `chr(92)` 拼。

---

## 不要动

- `/var/www/spark`、`/nbcc-app/`、`/dqc/`(8011)、`/rongtu/`(3000)、`/duel/`(8799)、`nbcckebiao.top`
  都是别的线上服务，别碰。
- `AutoAnswer_REDLAND` 仓库是 Python 原型，别把 Android 代码推过去。

---

## 待办 / 悬而未决

- `quiz-assistant-Expansion` 远端只有一个 `v2.5.8` release，之前的历史版本没归档
- 面板的「统计」页只统计到最近 7 天，更早的滚动删掉了
- Wi-Fi 环境下部分机型截图会拿到黑图，暂未处理
