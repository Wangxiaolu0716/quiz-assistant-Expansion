package com.quizassist

import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 云控文案的默认值，与当前界面文案保持一致。 */
object RemoteConfigDefaults {
    const val appTitle = "小多答题助手拓展版@cjzwwxl"
    const val subtitle = "截图识题、快速回答、联网搜索、题库检索"
    const val securityNotice = "应用不会上传你的密钥。请仅通过官方发布渠道获取安装包，非官方安装包可能导致密钥泄露。"
    const val setupGuide = "密钥可以不用填 已内置题库 按步骤开始即可"
    const val sponsorTitle = "我想赞助"
    const val sponsorText = "感谢支持！你的打赏是我持续更新下去的动力。"
    const val sponsorHint = "长按图片即可保存到相册"
}

/** 单条公告。新服务器版本下发 announcements 数组；旧 Gitee 单条字段仍然兼容。 */
@Serializable
data class Announcement(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    /** normal | important | force（force = 必须点确认，没有「不再提醒」） */
    val level: String = "normal",
    /** true：点过「不再提醒」后不再出现；false：每次启动都弹 */
    val once: Boolean = true,
    val buttonText: String = "",
    val linkUrl: String = "",
    /** 只对 [minVersionCode, maxVersionCode] 区间内的版本展示，0 表示不限 */
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    /** 生效时间窗，Unix 秒；0 表示不限 */
    val startAt: Long = 0L,
    val endAt: Long = 0L,
) {
    fun isForce(): Boolean = level.equals("force", ignoreCase = true)

    fun matchesVersion(versionCode: Int): Boolean =
        (minVersionCode <= 0 || versionCode >= minVersionCode) &&
            (maxVersionCode <= 0 || versionCode <= maxVersionCode)

    fun isActive(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (startAt > 0 && nowSeconds < startAt) return false
        if (endAt > 0 && nowSeconds > endAt) return false
        return id.isNotBlank() && (title.isNotBlank() || content.isNotBlank())
    }
}

/** 远程开关：服务端可以不发版就调参数。 */
@Serializable
data class FeatureFlags(
    val enableSearchDefault: Boolean = true,
    val enableVision: Boolean = true,
    val maxWaitSeconds: Int = 0,
    val captureIntervalSeconds: Int = 0,
    val promptProfile: String = "",
    val disableQuestionBank: Boolean = false,
    val enableReport: Boolean = true,
    /** 允许客户端把题目文本上报做错题统计（默认关） */
    val allowQuestionTelemetry: Boolean = false,
)

/** 远程提示词覆盖。留空表示使用客户端内置提示词。 */
@Serializable
data class PromptOverride(
    val version: Int = 0,
    val flashSystem: String = "",
    val searchSystem: String = "",
    val maxTokens: Int = 0,
    val temperature: Double? = null,
)

@Serializable
data class RemoteConfig(
    val appTitle: String = RemoteConfigDefaults.appTitle,
    val subtitle: String = RemoteConfigDefaults.subtitle,
    val securityNotice: String = RemoteConfigDefaults.securityNotice,
    val setupGuide: String = RemoteConfigDefaults.setupGuide,
    val sponsorTitle: String = RemoteConfigDefaults.sponsorTitle,
    val sponsorText: String = RemoteConfigDefaults.sponsorText,
    val sponsorHint: String = RemoteConfigDefaults.sponsorHint,
    // ---- 旧版单条公告字段（Gitee 兼容）----
    val announcementId: String = "",
    val announcementTitle: String = "",
    val announcementContent: String = "",
    // ---- 新版多公告 ----
    val announcements: List<Announcement> = emptyList(),
    // ---- 版本更新 ----
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val downloadUrl: String = "",
    /** 备用下载源（通常是服务器直链）。网盘慢/失效时用户可切这个。 */
    val downloadUrlAlt: String = "",
    val updateContent: String = "",
    /** 低于该 versionCode 必须更新，且弹窗不可取消 */
    val minSupportedVersionCode: Int = 0,
    /** 可选：APK 的 sha256，用于更新前校验 */
    val apkSha256: String = "",
    // ---- 远程参数 ----
    val configVersion: Int = 0,
    val features: FeatureFlags = FeatureFlags(),
    val prompt: PromptOverride = PromptOverride(),
) {
    /** 把旧版单条公告转成列表，统一走一套展示逻辑。 */
    fun allAnnouncements(): List<Announcement> {
        if (announcements.isNotEmpty()) return announcements
        if (announcementId.isBlank()) return emptyList()
        if (announcementTitle.isBlank() && announcementContent.isBlank()) return emptyList()
        return listOf(
            Announcement(
                id = announcementId,
                title = announcementTitle,
                content = announcementContent,
            ),
        )
    }

    fun isUpdateRequired(versionCode: Int = BuildConfig.VERSION_CODE): Boolean =
        minSupportedVersionCode > 0 && versionCode < minSupportedVersionCode
}

/**
 * 远程配置加载器。
 *
 * 读取策略（stale-while-revalidate）：
 *   1. [cached] 立刻返回本地缓存，界面零等待；
 *   2. [refresh] 后台走 主源(自己的服务器) → 备源(Gitee) → 缓存 → 默认值，
 *      带 ETag 条件请求，304 直接复用缓存；
 *   3. 全部失败时用缓存，没有缓存用内置默认值，绝不让启动流程报错。
 */
object RemoteConfigLoader {

    data class Snapshot(
        val config: RemoteConfig,
        val source: String,
        val fromCache: Boolean,
    )

    private const val TAG = "RemoteConfig"
    private const val PREFS = "remote_config_cache_v2"
    private const val KEY_JSON = "json"
    private const val KEY_ETAG = "etag"
    private const val KEY_TS = "fetched_at"
    private const val KEY_DEVICE = "device_id"

    /**
     * 缓存「新鲜度」阈值，只用于 [isStale] 观测，**不再阻断网络刷新**。
     *
     * 曾经用它在 TTL 内直接返回缓存，结果后台改了文案要等 6 小时才生效 —— 这是个坑，
     * 已改成每次启动都发一次带 ETag 的条件请求（304 几乎零开销）。
     */
    private const val CACHE_TTL_MS = 6 * 60L * 60 * 1000

    /** 同一进程内的重复刷新防抖：5 秒内只发一次请求。 */
    private const val MIN_REQUEST_INTERVAL_MS = 5_000L

    @Volatile
    private var lastAttemptAt = 0L

    private const val TIMEOUT_SECONDS = 6L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val configClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS + 3, TimeUnit.SECONDS)
        .build()

    private val reportClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 主源：自己的服务器。为空时直接跳过。 */
    private val primaryUrl: String get() = BuildConfig.CONFIG_PRIMARY_URL.trim()

    /** 备源：已下线（Gitee 那套不再维护），留字段是为了以后换镜像源时不用改结构。 */
    private val fallbackUrl: String get() = BuildConfig.CONFIG_FALLBACK_URL.trim()

    // ---------------- 对外 API ----------------

    /** 同步读缓存（毫秒级，可在主线程调用）。没有缓存返回默认值。 */
    fun cached(context: Context): RemoteConfig = readCache(context.applicationContext)?.first ?: RemoteConfig()

    /** 缓存是否已超过新鲜度阈值（仅观测用，不影响刷新）。 */
    fun isStale(context: Context): Boolean {
        val ts = prefs(context).getLong(KEY_TS, 0L)
        return System.currentTimeMillis() - ts > CACHE_TTL_MS
    }

    /**
     * 拉取最新配置。失败时自动降级，不抛异常。
     * @param force true = 忽略 TTL 和 ETag，强制重新下载
     */
    suspend fun refresh(context: Context, force: Boolean = false): Snapshot =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val cachedPair = readCache(appContext)
            val cachedConfig = cachedPair?.first
            val now = System.currentTimeMillis()

            // 只防抖同一进程内的连续调用（比如配置页多次触发），不做长 TTL 拦截：
            // 后台改完文案，用户下一次启动就该看到。
            if (!force && cachedConfig != null && now - lastAttemptAt < MIN_REQUEST_INTERVAL_MS) {
                return@withContext Snapshot(cachedConfig, "debounce", fromCache = true)
            }
            lastAttemptAt = now

            val etag = if (force) null else prefs(appContext).getString(KEY_ETAG, null)
            val urls = listOfNotNull(primaryUrl.takeIf { it.isNotBlank() }, fallbackUrl.takeIf { it.isNotBlank() })

            for ((index, url) in urls.withIndex()) {
                val isPrimary = url == primaryUrl
                val result = runCatching { fetch(url, if (isPrimary) etag else null) }
                val http = result.getOrNull()
                if (http != null) {
                    if (http.notModified && cachedConfig != null) {
                        touch(appContext)
                        return@withContext Snapshot(cachedConfig, "not-modified", fromCache = true)
                    }
                    if (http.config != null) {
                        cache(appContext, http.config, http.etag)
                        return@withContext Snapshot(http.config, if (index == 0) "primary" else "fallback", fromCache = false)
                    }
                } else {
                    Log.w(TAG, "配置源不可用[$url]: ${result.exceptionOrNull()?.message}")
                }
            }

            if (cachedConfig != null) {
                Log.w(TAG, "全部配置源失败，使用本地缓存")
                return@withContext Snapshot(cachedConfig, "cache-stale", fromCache = true)
            }
            Log.w(TAG, "全部配置源失败且无缓存，使用内置默认值")
            Snapshot(RemoteConfig(), "default", fromCache = true)
        }

    /** 兼容旧调用点：能拿到缓存就用缓存，否则等网络。 */
    suspend fun load(context: Context): RemoteConfig {
        val snapshot = refresh(context)
        return snapshot.config
    }

    /**
     * 匿名上报（发版统计、公告曝光）。失败静默，绝不阻塞主流程。
     */
    fun reportAsync(context: Context, event: String, extra: Map<String, String> = emptyMap()) {
        val url = BuildConfig.CONFIG_REPORT_URL.trim()
        if (url.isBlank()) return
        val appContext = context.applicationContext
        val payload = buildJsonObject {
            put("event", event)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("versionName", BuildConfig.VERSION_NAME)
            put("deviceId", deviceId(appContext))
            put("at", System.currentTimeMillis())
            if (extra.isNotEmpty()) {
                put("extra", JsonObject(extra.mapValues { JsonPrimitive(it.value) }))
            }
        }.toString()
        runCatching {
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            reportClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.d(TAG, "上报失败($event): ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        }.onFailure { Log.d(TAG, "上报异常($event): ${it.message}") }
    }

    // ---------------- 内部实现 ----------------

    private class FetchResult(
        val config: RemoteConfig?,
        val etag: String?,
        val notModified: Boolean = false,
    )

    private fun fetch(url: String, etag: String?): FetchResult {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        configClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 304) return FetchResult(null, etag, notModified = true)
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty().removePrefix("\uFEFF")
            if (body.isBlank()) error("empty body")
            val parsed = json.decodeFromString(RemoteConfig.serializer(), body)
            return FetchResult(parsed, response.header("ETag"))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readCache(context: Context): Pair<RemoteConfig, Long>? {
        val raw = prefs(context).getString(KEY_JSON, null) ?: return null
        val parsed = runCatching {
            json.decodeFromString(RemoteConfig.serializer(), raw)
        }.getOrNull() ?: return null
        return parsed to prefs(context).getLong(KEY_TS, 0L)
    }

    private fun cache(context: Context, config: RemoteConfig, etag: String?) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_JSON, json.encodeToString(RemoteConfig.serializer(), config))
                .putLong(KEY_TS, System.currentTimeMillis())
                .apply { if (!etag.isNullOrBlank()) putString(KEY_ETAG, etag) }
                .apply()
        }
    }

    /** 304：只刷新时间戳，证明服务端内容没变，避免下次启动又请求。 */
    private fun touch(context: Context) {
        prefs(context).edit().putLong(KEY_TS, System.currentTimeMillis()).apply()
    }

    private fun deviceId(context: Context): String {
        val store = prefs(context)
        val existing = store.getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(24)
        store.edit().putString(KEY_DEVICE, created).apply()
        return created
    }
}
