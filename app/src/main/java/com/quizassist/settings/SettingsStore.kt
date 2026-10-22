package com.quizassist.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.quizassist.model.AppSettings
import com.quizassist.model.ProviderConfig
import com.quizassist.model.RoiBox
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quiz_assist_settings_v2", Context.MODE_PRIVATE)
    private val keyCipher = ApiKeyCipher()

    init {
        migratePlaintextApiKeys()
        migrateRoiDefault()
    }

    private val state = MutableStateFlow(load())

    val settings: Flow<AppSettings> = state.asStateFlow()

    /** 当前已保存的配置快照（同步读取，无需订阅）。 */
    val currentSettings: AppSettings get() = state.value

    /** 重新从磁盘读取最新配置（供悬浮窗等长期存活组件避免使用过期快照）。 */
    fun fresh(): AppSettings = load()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(state.value))
    }

    suspend fun save(next: AppSettings) = persist(next)

    fun saveSync(next: AppSettings) = persist(next)

    private fun persist(next: AppSettings) {
        validateBaseUrl(next.flashProvider.baseUrl)
        val ok = prefs.edit()
            .putString(Keys.flashBaseUrl, next.flashProvider.baseUrl)
            .putString(Keys.flashApiKey, keyCipher.encrypt(next.flashProvider.apiKey))
            .putString(Keys.flashModel, next.flashProvider.modelName)
            .putString(Keys.flashTemperature, next.flashProvider.temperature.toString())
            .putString(Keys.flashSearch, next.flashProvider.enableSearchHint.toString())
            .putString(Keys.flashReasoning, next.flashProvider.reasoningEffort)
            .putString(Keys.maxWait, next.maxWaitTimeoutSeconds.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS).toString())
            .putString(Keys.useVision, next.useVisionWhenOcrWeak.toString())
            .putString(Keys.showPreview, next.showImagePreview.toString())
            .putString(Keys.overlayAlpha, next.overlayAlpha.coerceIn(0.35f, 1f).toString())
            .putString(Keys.clickThrough, next.clickThrough.toString())
            .putString(Keys.questionBankMode, next.questionBankMode.toString())
            .putString(Keys.doubleCheck, next.doubleCheck.toString())
            .putString(Keys.bankMatchThreshold, next.bankMatchThreshold.coerceIn(AppSettings.BANK_MATCH_MIN, AppSettings.BANK_MATCH_MAX).toString())
            .putString(Keys.autoCaptureInterval, next.autoCaptureIntervalSeconds.coerceIn(1, 5).toString())
            .putString(Keys.answerMode, next.answerMode.key)
            .applyRoi(next.roi)
            .commit()
        if (!ok) error("SharedPreferences commit failed")
        state.value = load()
    }

    private fun load(): AppSettings {
        val default = AppSettings()
        val roi = if (prefs.contains(Keys.roiLeft)) {
            RoiBox(
                leftRatio = prefs.readFloat(Keys.roiLeft, 0f),
                topRatio = prefs.readFloat(Keys.roiTop, 0.35f),
                widthRatio = prefs.readFloat(Keys.roiWidth, 1f),
                heightRatio = prefs.readFloat(Keys.roiHeight, 0.65f),
            ).takeIf { it.isValid() }
        } else {
            null
        }

        return default.copy(
            flashProvider = ProviderConfig(
                baseUrl = prefs.readString(Keys.flashBaseUrl, default.flashProvider.baseUrl),
                apiKey = readApiKey(Keys.flashApiKey, default.flashProvider.apiKey),
                modelName = prefs.readString(Keys.flashModel, default.flashProvider.modelName),
                temperature = prefs.readDouble(Keys.flashTemperature, default.flashProvider.temperature),
                enableSearchHint = prefs.readBoolean(Keys.flashSearch, default.flashProvider.enableSearchHint),
                reasoningEffort = prefs.readString(Keys.flashReasoning, default.flashProvider.reasoningEffort),
            ),
            maxWaitTimeoutSeconds = prefs.readInt(Keys.maxWait, default.maxWaitTimeoutSeconds)
                .coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS),
            autoCaptureIntervalSeconds = prefs.readInt(Keys.autoCaptureInterval, default.autoCaptureIntervalSeconds)
                .coerceIn(1, 5),
            useVisionWhenOcrWeak = prefs.readBoolean(Keys.useVision, default.useVisionWhenOcrWeak),
            showImagePreview = prefs.readBoolean(Keys.showPreview, default.showImagePreview),
            overlayAlpha = prefs.readFloat(Keys.overlayAlpha, default.overlayAlpha).coerceIn(0.35f, 1f),
            clickThrough = prefs.readBoolean(Keys.clickThrough, default.clickThrough),
            questionBankMode = prefs.readBoolean(Keys.questionBankMode, default.questionBankMode),
            doubleCheck = prefs.readBoolean(Keys.doubleCheck, default.doubleCheck),
            bankMatchThreshold = prefs.readFloat(Keys.bankMatchThreshold, default.bankMatchThreshold)
                .coerceIn(AppSettings.BANK_MATCH_MIN, AppSettings.BANK_MATCH_MAX),
            roi = roi,
            answerMode = com.quizassist.model.AnswerMode.from(prefs.readString(Keys.answerMode, "")),
        )
    }

    private fun readApiKey(key: String, default: String): String =
        prefs.readString(key, default).let { stored ->
            if (stored.startsWith(ApiKeyCipher.PREFIX)) {
                keyCipher.decrypt(stored).orEmpty()
            } else {
                stored
            }
        }

    private fun migratePlaintextApiKeys() {
        listOf(Keys.flashApiKey).forEach { key ->
            val stored = prefs.getString(key, null).orEmpty()
            if (stored.isNotBlank() && !stored.startsWith(ApiKeyCipher.PREFIX)) {
                prefs.edit().putString(key, keyCipher.encrypt(stored)).commit()
            }
        }
    }

    /** 旧版本默认截图区域为「上 14% 起、高 65%、宽 98%」，现统一迁移为「屏幕底部 65%」。 */
    private fun migrateRoiDefault() {
        if (!prefs.contains(Keys.roiLeft)) return
        val left = prefs.getString(Keys.roiLeft, null)?.toFloatOrNull()
        val top = prefs.getString(Keys.roiTop, null)?.toFloatOrNull()
        val width = prefs.getString(Keys.roiWidth, null)?.toFloatOrNull()
        val height = prefs.getString(Keys.roiHeight, null)?.toFloatOrNull()
        val isOldDefault = left != null && top != null && width != null && height != null &&
            kotlin.math.abs(left - 0.01f) < 1e-4f &&
            kotlin.math.abs(top - 0.14f) < 1e-4f &&
            kotlin.math.abs(width - 0.98f) < 1e-4f &&
            kotlin.math.abs(height - 0.65f) < 1e-4f
        if (isOldDefault) {
            prefs.edit()
                .putString(Keys.roiLeft, "0.0")
                .putString(Keys.roiTop, "0.35")
                .putString(Keys.roiWidth, "1.0")
                .putString(Keys.roiHeight, "0.65")
                .apply()
        }
    }

    private fun validateBaseUrl(value: String) {
        val normalized = value.trim()
        if (normalized.isNotBlank() && !normalized.startsWith("https://", ignoreCase = true)) {
            error("接口地址必须使用 HTTPS")
        }
    }

    private fun android.content.SharedPreferences.Editor.applyRoi(roi: RoiBox?): android.content.SharedPreferences.Editor =
        if (roi == null) {
            remove(Keys.roiLeft)
                .remove(Keys.roiTop)
                .remove(Keys.roiWidth)
                .remove(Keys.roiHeight)
        } else {
            putString(Keys.roiLeft, roi.leftRatio.toString())
                .putString(Keys.roiTop, roi.topRatio.toString())
                .putString(Keys.roiWidth, roi.widthRatio.toString())
                .putString(Keys.roiHeight, roi.heightRatio.toString())
        }

    private fun android.content.SharedPreferences.readString(key: String, default: String): String =
        runCatching { getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() } ?: default

    private fun android.content.SharedPreferences.readBoolean(key: String, default: Boolean): Boolean =
        runCatching { getString(key, null)?.toBooleanStrictOrNull() }.getOrNull() ?: default

    private fun android.content.SharedPreferences.readInt(key: String, default: Int): Int =
        runCatching { getString(key, null)?.toIntOrNull() }.getOrNull() ?: default

    private fun android.content.SharedPreferences.readFloat(key: String, default: Float): Float =
        runCatching { getString(key, null)?.toFloatOrNull() }.getOrNull() ?: default

    private fun android.content.SharedPreferences.readDouble(key: String, default: Double): Double =
        runCatching { getString(key, null)?.toDoubleOrNull() }.getOrNull() ?: default

    private object Keys {
        const val flashBaseUrl = "flash_base_url"
        const val flashApiKey = "flash_api_key"
        const val flashModel = "flash_model"
        const val flashTemperature = "flash_temperature"
        const val flashSearch = "flash_search"
        const val flashReasoning = "flash_reasoning"
        const val maxWait = "max_wait"
        const val useVision = "use_vision"
        const val showPreview = "show_preview"
        const val overlayAlpha = "overlay_alpha"
        const val clickThrough = "click_through"
        const val questionBankMode = "question_bank_mode"
        const val doubleCheck = "double_check"
        const val bankMatchThreshold = "bank_match_threshold"
        const val autoCaptureInterval = "auto_capture_interval"
        const val roiLeft = "roi_left"
        const val roiTop = "roi_top"
        const val roiWidth = "roi_width"
        const val roiHeight = "roi_height"
        const val answerMode = "answer_mode"
    }

    private companion object {
        const val MIN_WAIT_SECONDS = 5
        const val MAX_WAIT_SECONDS = 180
    }
}

private class ApiKeyCipher {
    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv ?: error("Keystore 未返回加密 IV")
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array()
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? = runCatching {
        val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_SIZE)
        val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFIX = "enc:v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "quiz_assist_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
