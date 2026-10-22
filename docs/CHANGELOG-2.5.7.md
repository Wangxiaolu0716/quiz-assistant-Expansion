# 2.5.7 改造记录

> 工程位置：`D:\projects\QuizAssist`（原 `D:\downloads\答题助手` 保持原样未动，可随时对照/回滚）
> 旧版远端配置备份：见本文末尾「旧配置对照」

---

## 一、公告 / 远程接口

### 改之前的痛点

| 问题 | 后果 |
| --- | --- |
| 单一 Gitee raw 地址硬编码 | Gitee CDN 有缓存，公告改了不一定立刻生效；Gitee 抽风就全瞎 |
| 无 ETag / 无 TTL | 每次启动都完整下载一遍 JSON |
| 公告只有一条平铺字段 | 想同时推两条公告做不到；不能按版本、按时间定向 |
| 点「确认」不记忆 | 每次启动都弹同一条公告（`announcementId: "1"` 就一直弹） |
| 更新弹窗只能「取消」 | 无法强制老版本用户升级 |
| 提示词写死在 APK 里 | 调提示词必须重新打包发版 |

### 改之后的架构

```
启动
 ├─ RemoteConfigLoader.cached()   ← 读本地缓存，立即渲染（0 网络等待）
 └─ RemoteConfigLoader.refresh()  ← 后台
      ├─ 主源  BuildConfig.CONFIG_PRIMARY_URL   （你的服务器，带 If-None-Match）
      ├─ 备源  BuildConfig.CONFIG_FALLBACK_URL  （Gitee，服务器挂了照样发公告）
      ├─ 缓存  （全部失败时用上次的）
      └─ 默认值（连缓存都没有）
```

新增能力：

- **多公告队列**：`announcements: []`，一条条弹，不叠窗。
- **公告分级**：`level: normal | important | force`，force 只能点「我知道了」。
- **定向推送**：`minVersionCode` / `maxVersionCode` / `startAt` / `endAt`。
- **已读记忆**：`once: true` 时点「不再提醒」写 `seen_ids` 集合，同 ID 永不再弹。
- **强制更新**：`minSupportedVersionCode` 高于当前版本时，弹窗无取消按钮。
- **ETag 条件请求**：服务端返回 304 时只刷新时间戳，几乎零流量。
- **匿名上报**：`app_launch` / `announcement_ack` / `update_click`，可在远端用 `features.enableReport=false` 一键关掉。
- **远程参数**：`features`（搜索开关、题库存否、等待时长）+ `prompt`（提示词正文、maxTokens、temperature）。

### 要动的文件

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle.kts` | 新增 `CONFIG_PRIMARY_URL` / `CONFIG_FALLBACK_URL` / `CONFIG_REPORT_URL` 三个 `buildConfigField`；versionCode `20506→20507` |
| `RemoteConfig.kt` | 重写：`Announcement`、`FeatureFlags`、`PromptOverride` 模型；stale-while-revalidate 加载器；ETag；上报 |
| `Dialogs.kt` | 公告弹窗改吃 `Announcement`，支持强制型与链接按钮；更新弹窗支持 `required` |
| `MainActivity.kt` | 缓存先渲染 + 后台刷新；公告队列；`seen_ids`；强制更新；上报 |
| `prompt/PromptFactory.kt` | 提示词重写 + `RemoteRuntime`（远程覆盖入口） |
| `network/LLMRepository.kt` | `maxTokens` / `temperature` 改从 `RemoteRuntime` 取，可远端调 |

### 你要做的唯一一件事

在 `app/build.gradle.kts` 里把服务器地址填上：

```kotlin
buildConfigField("String", "CONFIG_PRIMARY_URL", "\"https://api.你的域名.com/api/v1/client/config\"")
buildConfigField("String", "CONFIG_REPORT_URL", "\"https://api.你的域名.com/api/v1/client/report\"")
```

留空就只走 Gitee，行为跟现在一样（但已经带上多公告/强制更新能力，只是数据源还是 Gitee）。

---

## 二、AI 提示词

### 改之前

```
你是答题活动助手。根据 OCR 文本判断题干和选项，无视其他无用信息，只输出最可能答案；
如果是选择题，优先输出选项字母和内容；附带不超过15字理由。
不确定时输出"无法判断"。
若OCR结果中没有ABCD，则不要显示ABCD。
...格式输出
```

问题：

1. **「不确定时输出无法判断」** —— 模型遇到 OCR 噪声就摆烂，直接交白卷，而这恰恰是最需要它猜的时候。
2. **没说怎么处理 OCR 脏数据** —— 题干选项粘连、按钮文字混入、形近字，全靠模型自由发挥。
3. **字母越界** —— 只说了「没有 ABCD 就别显示」，没说「有 ABCD 时不能输出 E/F/G」。
4. **题型规则混在一起** —— 单选要求「字母+内容」，多选/判断/填空没有明确格式。
5. **解析上限 15 字却给 120 tokens** —— 一半预算浪费在模型自言自语上。

### 改之后

`PromptFactory.FLASH_SYSTEM_BUILTIN` 重写为四段式：还原题干 → 判题型 → 输出契约 → 硬性要求。

关键变化：

| 变化 | 目的 |
| --- | --- |
| 明确「OCR 会把题干、选项、按钮文字混在一起」并要求先语义还原 | 题干预处理交给模型，比正则硬切稳 |
| 题型分开写：单选/多选/判断/填空/无选项简答 | 多选输出 `A,C`，判断只出「正确/错误」，填空只出内容 |
| **字母白名单**：只能取 OCR 里真实出现的选项 | 堵住模型编 E/F 和「A. 内容」式输出 |
| 删掉「无法判断」，改成「概率低也给最可能答案，只有完全无法判断才输出 ?」 | 提升有效作答率 |
| 输出契约压到两行、无前缀、无 markdown | 直接对上 `OverlayController.cleanFlashText()`，少解析歧义 |
| `maxTokens` 120 → 96（可远端调） | 两行答案足够，省 token、省延迟 |

`userPrompt()` 也加了运行时信号：

```
补充信息（供判断，不要输出）：
- 检测到选项字母：A,B,C,D     ← 或「未检测到 A/B/C/D 选项，请直接给出答案内容」
- 已附带题目截图 / 仅文本输入
- 可能混入界面按钮文字（下一题/提交/返回），属于噪声
```

`searchSystem()`（联网模式）同样重写：要求先提取可检索实体（作品名/角色名/活动名/版本号）再查证，冲突时以最新官方资料为准，输出单行 JSON。

### 提示词怎么调（不用发版）

1. 改 `server/data/config.json` 的 `prompt.flashSystem`（非空即覆盖内置）；
2. `prompt.version` 加一；
3. `POST /api/v1/admin/config` 推上去；
4. 手机重启 App 生效；不满意就清空字段回滚。

---

## 三、服务端

`server/index.js`：零依赖 Node 18+，直接 `node server/index.js` 就能跑。

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/client/config` | 下发配置，支持 ETag / 304 |
| `POST /api/v1/client/report` | 匿名上报，追加 `data/reports.ndjson` |
| `GET /api/v1/admin/config` | 读当前配置（Bearer token） |
| `POST /api/v1/admin/config` | 热更新配置，原子写入（Bearer token） |
| `GET /healthz` | 健康检查 |

配置热更新示例：

```bash
export ADMIN_TOKEN=换成你的随机串
node server/index.js

curl -X POST http://127.0.0.1:8080/api/v1/admin/config \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @server/data/config.json
```

完整契约见 `docs/remote-config-api.md`。

---

## 四、验证情况

- `assembleDebug` / `assembleRelease` 均通过（Gradle 8.9 + JDK 17 + compileSdk 35）。
- 服务端实测：`200` + `ETag`、`If-None-Match → 304`、上报落盘、管理接口 `401/200` 全部正常。

---

## 五、旧配置对照

当前 Gitee 上那份（2.5.6）是平铺单条公告：

```json
{
  "announcementId": "1",
  "announcementTitle": "下次再见 主角！！",
  "announcementContent": "致敬我们的@多不会老师~...",
  "latestVersionCode": 20506,
  "downloadUrl": "https://...123pan...",
  "updateContent": "..."
}
```

新版客户端仍然能读它（`allAnnouncements()` 会自动转成单元素列表），所以过渡期不用急着换。
但**旧版客户端（≤2.5.6）不认识 `announcements` 数组**，如果想让还装着老版本的人看到公告，
服务器上要同时保留 `announcementId/announcementTitle/announcementContent` 三个字段。

---

## 六、回滚

```bash
# 整个工程回滚到原始状态
rm -rf D:\projects\QuizAssist
cp -r "D:\downloads\答题助手" D:\projects\QuizAssist
```

原目录自始至终没有被修改过。

---

## 七、线上部署（2026-10-22 完成）

| 项 | 值 |
| --- | --- |
| 客户端配置源 | `https://xuhuohua.xyz/quiz-api/client/config` |
| 客户端上报 | `https://xuhuohua.xyz/quiz-api/client/report` |
| 网页后台 | `https://xuhuohua.xyz/quiz-admin/`（Basic `admin` / `841117Dqc`，页面内再填同一串 ADMIN_TOKEN） |
| 服务 | systemd `quizassist-config`，仅监听 `127.0.0.1:18080` |
| 代码 | `/opt/quizassist-config/index.js` |
| 配置 | `/opt/quizassist-config/data/config.json` |
| 日志 | `/var/log/quizassist-config.log` |
| nginx 备份 | `/etc/nginx/conf.d/xuhuohua.conf.bak-quizassist-*` |

为什么不直接用 IP 或子域名：见 `docs/服务器部署.md` 第六节（证书 CN 不匹配 + 无通配符）。

`agent.py` 覆盖风险已处理：线上 `xuhuohua.conf` 已回写进 `xuhuohua.conf.txt`，
以后跑 `agent.py nginx` 不会丢掉 quiz-api 段。

### 实测结果

- 客户端拉配置：`200`，`ETag: "9dee5c8a2e74bc22e51e"`
- 条件请求：`If-None-Match` → `304`
- 后台页面：无鉴权 `401`，带鉴权 `200`（25.8 KB）
- 管理接口：Basic + `X-Admin-Token` → `200`；缺 token → `401`
- 写入 → 客户端读到新 `configVersion` → 回滚，全链路通过
- 上报：`{"ok":true}`，落盘 `data/reports.ndjson`

### 追加（同一批次）

- **统计页**：后台新增「统计」页签，服务端 `GET /api/v1/admin/stats` 聚合 `reports.ndjson`
  （独立设备、累计/今日启动、版本分布、近 30 天柱状图、最近 30 条事件），结果缓存 60 秒。
- **APK 直链**：`/var/www/quizassist/quizassist-2.5.7.apk` 经 nginx `location /quizassist/`
  直出，`Content-Type: application/vnd.android.package-archive`，支持 Range 断点续传；
  后台 `downloadUrl` 已从 123pan 改成该直链，`apkSha256` 同步填好。

### 追加 2（2026-10-22 下午）

- **Gitee 备源彻底移除**：`CONFIG_FALLBACK_URL` 置空，dex 里已无 `giteeusercontent.com`
  字符串（构建产物实测 x0）。加载链路变为「主源 → 缓存 → 默认值」。
- **双下载源**：`RemoteConfig` 新增 `downloadUrlAlt`；更新弹窗主按钮走 `downloadUrl`，
  多出「直链下载」按钮走 `downloadUrlAlt`（留空则不显示）。后台「版本更新」加了对应字段。
- **APK 重传**：新包 sha256 `3ebfa17f…0b7775`，已覆盖 `/var/www/quizassist/quizassist-2.5.7.apk`，
  线上配置 `apkSha256` 同步更新，`configVersion` 6。
- **网盘**：小飞机网盘无公开上传 API，前端 `/ws/share/*` 请求需 `encryptHex` 签名 + 登录态
  `userId`，脚本无法代传；改为「直链兜底 + 手动拖网盘 + 后台双源」流程，详见 `服务器部署.md`。

### 追加 3：发版流程对齐课表项目

参考 `/opt/kebiao-site` 的「发版」：`deploy.sh` 只发页面，`admin_server.py` 的 `/api/upload`
负责把 APK 落到服务器（裸字节流、边收边算 sha256），网盘地址 `MIRROR_URL` 是手写常量，
`/mirror` 只做 302 + 计数——**课表那边也没有自动传网盘**，一直是人工放。

据此给 QuizAssist 补了同样的上传入口：

- 后端 `POST /api/v1/admin/upload?filename=xxx.apk`（裸 body 流式写盘 + sha256，鉴权同管理接口）
- 后台新增「发版」页：拖拽/选择 APK → 进度条 → 完成后自动回填 `downloadUrlAlt` 与 `apkSha256`
- nginx：`/quiz-api/admin/` 块内单独放开 `client_max_body_size 300m` + `proxy_request_buffering off`
  （第一次只在 `/quiz-api/` 加，被更长的 admin 前缀覆盖，导致 50 MB 上传 413，已修）
- 实测：1 MB 与 50 MB 均上传成功，sha256 与本地一致；无 token 返回 401

### 追加 4：题库自适应 + GitHub 发布（2.5.8）

- **题库导入重写**：`QuestionBankParser.parseAuto()` 不再依赖扩展名，JSON / CSV / 制表符 /
  竖线 / 分号 / 纯文本全部自认；表头关键词扩展（题目/题干/问题/question、答案/答案/answer、
  选项/options）；无表头时用「短的是答案、长的是题干」打分猜列；选项列拼进题干。
- **修了一个误判**：表头关键词含单字母 `a`，原来用 contains 匹配，导致
  `A.派蒙 B.钟离 …` 这样的选项串被当成表头，整表按表头解析、答案列取错。
  现在 ≤2 字符的关键词必须完全相等，且表头单元格限长 12 字。7 种排法自测全通过。
- **导入提示**：成功时回显识别到的格式与列角色，例如「已识别 CSV（逗号）（题目 / 选项 / 答案）」。
- **新增 `tools/publish_github.py`**：发 GitHub Release + 挂 APK 附件，纯标准库实现。
- **README 重写**：功能、两种下载渠道、题库格式说明（含示例）、构建与发布步骤。
- **`.gitignore`** 补 `.env`、`server/data/*.ndjson`、`.check/`。
