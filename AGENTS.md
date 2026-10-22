# AGENTS.md

给接手的人（或下一次会话的 AI）看的。改代码前先读这页。

---

## 一句话

Android 截图答题工具。系统截图 → 本地 OCR → 用户自配的 OpenAI 兼容接口 → 悬浮窗显示答案。
不做自动点击、不读内存、不抓包。

---

## 仓库身份

| 项 | 值 |
| --- | --- |
| 本仓库 | `quiz-assistant-Expansion` —— **Android 版，就是这里的代码** |
| 旧仓库 | `AutoAnswer_REDLAND` —— 早期 Python 原型，不要往那边推 Kotlin |
| 包名 | `com.wangxiaolu.quizassist`（namespace 仍是 `com.quizassist`） |

---

## 构建

```bash
export JAVA_HOME="/path/to/jdk17"
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle | head -1)
"$GRADLE" --no-daemon -PskipLint=true assembleRelease
```

约 50 秒。产物 `app/build/outputs/apk/release/app-release.apk`（50 MB）。

签名读 `signing.properties` + `release.keystore`，两者都在 `.gitignore` 里，**不要提交**。

已知无害警告：`OcrProcessor.kt Variable 'latinCount' is never used`。

---

## 代码结构

```
app/src/main/java/com/quizassist/
├── MainActivity.kt            三 Tab 主界面 + 页面转场 + 左滑返回
├── RemoteConfig.kt            远程配置拉取、ETag、缓存回落
├── Dialogs.kt                 公告 / 更新弹窗
├── SponsorScreen.kt           赞助页
├── model/
│   ├── Models.kt              AppSettings / AnswerMode / 事件类型
│   └── ModelPresets.kt        模型预设
├── settings/SettingsStore.kt  SharedPreferences + Keystore 加密 API Key
├── prompt/PromptFactory.kt    提示词：推理指引 + 按 AnswerMode 拼输出契约
├── questionbank/              题库导入与匹配
│   ├── QuestionBankModels.kt  自适应解析器（JSON/CSV/制表符/纯文本）
│   ├── QuestionBankStore.kt   落盘与元数据
│   └── QuestionBankMatcher.kt 模糊匹配
├── network/LLMRepository.kt   OpenAI 兼容接口（chat/completions + responses）
├── overlay/                   悬浮窗、截图、OCR 串起来
│   ├── OverlayController.kt   状态机 + 答案裁剪
│   ├── ScreenshotCapturer.kt  MediaProjection 抓帧
│   ├── CaptureSessionService.kt 前台服务
│   └── OverlayRenderer.kt     悬浮窗 View
├── ocr/OcrProcessor.kt        ML Kit 中文识别
├── cache/AnswerCache.kt       答题历史
└── engine/QuizSolverEngine.kt 主流程编排
```

---

## 踩过的坑（别再踩）

### 1. 远程配置 6 小时不刷新

`RemoteConfig.refresh()` 原来有 `CACHE_TTL_MS` 短路：缓存没过期就直接返回，根本不发请求。
后果是后台改了文案客户端永远看不到。

现在：每次启动必发一次带 `If-None-Match` 的请求，304 只更新时间戳，另加 5 秒防抖。

### 2. 大文件上传 413

如果前面挂了 nginx，`client_max_body_size` 要加在**实际命中的那个 location** 上。
更长的前缀（比如 `/api/admin/`）会覆盖父级配置，只给父级加没用。
同时要 `proxy_request_buffering off`，否则 nginx 先把 50 MB 缓冲到磁盘再转发。

### 3. 题库表头误判

表头关键词里有单字母 `a` / `q`，最初用「包含」判断。
结果 `A.派蒙 B.钟离 C.安柏 D.凯亚` 这种选项串因为含 `a` 被当成表头，
整张表按表头规则解析、答案列取到选项列。

现在：长度 ≤2 的关键词必须完全相等，表头单元格限长 12 字。

### 4. 录屏卡顿

VirtualDisplay 常驻镜像时，每帧都会在主线程分配两张全屏 Bitmap 并拷贝 10 MB。
刷视频一秒几十帧直接卡死。

现在：空闲 `setSurface(null)`，抓图时才接回；解码走独立 HandlerThread。
**别改回常驻镜像。**

### 5. 悬浮窗文本糊满屏幕

模型稍微啰嗦一点就把半个屏幕铺满。
`OverlayController.flashDisplayText()` 现在按 `AnswerMode` 裁剪：
只给答案就取第一行（≤60 字），答案+简析取前两行，并剥掉「答案：」这类前缀。

### 6. Windows 上 python 的 /tmp 不一致

bash 的 `/tmp` 是 `C:\Users\<user>\AppData\Local\Temp`，python 的是 `<当前盘>:\tmp`。
脚本临时文件放项目内 `.tmp/`。

### 7. 中文路径的 python 转义

`"D:\projects\..."` 里的 `\u` 会触发 unicodeescape 错误。用正斜杠或 `chr(92)` 拼。

---

## 题库导入的行为约定

`QuestionBankParser.parseAuto()` 是唯一入口，不要按扩展名分派：

1. `[` / `{` 开头 → JSON（键名认 question/题目/q，answer/答案/a）
2. 前 12 行里某种分隔符能切成一致的 ≥2 列 → 表格（制表符优先，其次逗号/竖线/分号）
3. 都不行 → 纯文本（`题目：`/`答案：` 标记，或两行一组）

列角色：有表头按关键词，没表头按「短的像答案、长的像题干」打分。
选项列会拼进题干（匹配和模型都用得上）。

---

## 发布

产物要同时放三处：GitHub Release、网盘、自有服务器直链。
配置里 `downloadUrl` 指向网盘（主），`downloadUrlAlt` 指向直链（慢，兜底）。

具体命令和凭据见本地 `private/` 目录（不进仓库）。
