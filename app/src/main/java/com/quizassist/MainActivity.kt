package com.quizassist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.quizassist.cache.AnswerCache
import com.quizassist.cache.HistoryItem
import com.quizassist.model.AnswerMode
import com.quizassist.model.AppSettings
import com.quizassist.model.ModelPreset
import com.quizassist.model.ModelPresets
import com.quizassist.model.ProviderConfig
import com.quizassist.model.RoiBox
import com.quizassist.overlay.OverlayController
import com.quizassist.overlay.ProjectionPermissionStore
import com.quizassist.prompt.PromptFactory
import com.quizassist.prompt.RemoteRuntime
import com.quizassist.questionbank.QuestionBankStore
import com.quizassist.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private var importMessage by mutableStateOf("")
    private var showSponsor by mutableStateOf(false)
    private var remoteConfig by mutableStateOf(RemoteConfig())

    /** 待展示公告队列，一次只弹一条，确认后出队。 */
    private var announcementQueue by mutableStateOf(emptyList<Announcement>())
    private var updateVisible by mutableStateOf(false)

    private val importQuestionBank = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { QuestionBankStore(this@MainActivity).import(uri) }
                .onSuccess { result ->
                    settingsStore.update { it.copy(questionBankMode = true) }
                    withContext(Dispatchers.Main) {
                        importMessage = "已识别 ${result.format}（${result.columns}），导入 ${result.count} 道：${result.name}"
                        toast(importMessage)
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        importMessage = "导入失败：${it.message.orEmpty()}"
                        toast(importMessage)
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        // 答题风格要在第一次请求之前就位，否则会用上一轮的默认值。
        PromptFactory.setAnswerMode(settingsStore.currentSettings.answerMode)

        if (runCatching { QuestionBankStore(this).importBuiltInIfEmpty() }.getOrNull() != null) {
            settingsStore.saveSync(settingsStore.currentSettings.copy(questionBankMode = true))
        }
        lifecycleScope.launch {
            // 先用本地缓存把界面渲染出来（毫秒级），再后台刷新，公告不会卡启动。
            applyConfig(RemoteConfigLoader.cached(this@MainActivity))

            val snapshot = RemoteConfigLoader.refresh(this@MainActivity)
            applyConfig(snapshot.config)

            if (RemoteRuntime.enableReport()) {
                RemoteConfigLoader.reportAsync(
                    this@MainActivity,
                    "app_launch",
                    mapOf("source" to snapshot.source, "fromCache" to snapshot.fromCache.toString()),
                )
            }
        }
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        showSponsor = showSponsor,
                        onSponsorChange = { showSponsor = it },
                        store = settingsStore,
                        config = remoteConfig,
                        importMessage = importMessage,
                        onImportQuestionBank = {
                            importQuestionBank.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*"))
                        },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onStartProjection = { requestProjection() },
                        onStartOverlay = { showOverlay() },
                    )

                    announcementQueue.firstOrNull()?.let { announcement ->
                        AnnouncementDialog(
                            announcement = announcement,
                            onConfirm = { confirmAnnouncement(announcement) },
                            onNeverRemind = { neverRemindAnnouncement(announcement) },
                            onOpenLink = { openLink(it) },
                        )
                    }
                    // 公告优先，公告处理完再弹更新，避免两个对话框叠在一起。
                    if (updateVisible && announcementQueue.isEmpty()) {
                        val required = remoteConfig.isUpdateRequired()
                        UpdateDialog(
                            title = if (remoteConfig.latestVersionName.isNotBlank()) {
                                "发现新版本 ${remoteConfig.latestVersionName}"
                            } else {
                                "发现新版本"
                            },
                            content = remoteConfig.updateContent.ifBlank {
                                if (required) {
                                    "当前版本已停止支持，请更新后继续使用。"
                                } else {
                                    "检测到新版本，点击「去下载」查看。"
                                }
                            },
                            onUpdate = { openUpdate(remoteConfig.downloadUrl) },
                            onCancel = { dismissUpdate() },
                            required = required,
                            altUrl = remoteConfig.downloadUrlAlt,
                            onOpenAlt = { openUpdate(remoteConfig.downloadUrlAlt) },
                        )
                    }
                }
            }
        }
    }

    private val startProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ProjectionPermissionStore.update(result.resultCode, result.data)
            OverlayController.ensureCaptureSession(this)
                .onSuccess { toast(Zh.captureAuthorized) }
                .onFailure { toast("${Zh.captureSessionFailed}: ${it.message.orEmpty()}") }
        } else {
            toast(Zh.captureDenied)
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { toast("${Zh.openPermissionFailed}: ${it.message.orEmpty()}") }
    }

    private fun requestProjection() {
        runCatching {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startProjection.launch(manager.createScreenCaptureIntent())
        }.onFailure { toast("${Zh.captureLaunchFailed}: ${it.message.orEmpty()}") }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            toast(Zh.needOverlayPermission)
            return
        }
        runCatching {
            OverlayController.show(this).getOrThrow()
            toast(Zh.overlayStarting)
        }.onFailure { toast("${Zh.overlayStartFailed}: ${it.message.orEmpty()}") }
    }

    /** 应用一份远程配置：同步文案、远程提示词参数、公告队列与更新状态。 */
    private fun applyConfig(config: RemoteConfig) {
        remoteConfig = config
        RemoteRuntime.apply(config)
        announcementQueue = pendingAnnouncements(config)
        updateVisible = shouldShowUpdate(config)
    }

    /** 过滤出当前该弹的公告：版本区间、生效时间窗、是否已「不再提醒」。 */
    private fun pendingAnnouncements(config: RemoteConfig): List<Announcement> {
        val seen = seenAnnouncementIds()
        return config.allAnnouncements().filter { announcement ->
            announcement.isActive() &&
                announcement.matchesVersion(BuildConfig.VERSION_CODE) &&
                !(announcement.once && seen.contains(announcement.id))
        }
    }

    private fun seenAnnouncementIds(): Set<String> =
        getSharedPreferences("announcement", MODE_PRIVATE)
            .getStringSet("seen_ids", emptySet())
            .orEmpty()
            .toSet()

    /** 「确认」：本次关闭；只有 once=false 的公告重启后还会再弹。 */
    private fun confirmAnnouncement(announcement: Announcement) {
        announcementQueue = announcementQueue.drop(1)
        if (RemoteRuntime.enableReport()) {
            RemoteConfigLoader.reportAsync(this, "announcement_ack", mapOf("id" to announcement.id))
        }
    }

    /** 「不再提醒」：记住这条公告 ID，等服务器发新 ID 才会再出现。 */
    private fun neverRemindAnnouncement(announcement: Announcement) {
        if (announcement.once && announcement.id.isNotBlank()) {
            getSharedPreferences("announcement", MODE_PRIVATE)
                .edit()
                .putStringSet("seen_ids", seenAnnouncementIds() + announcement.id)
                .putString("last_seen_id", announcement.id)
                .apply()
        }
        announcementQueue = announcementQueue.drop(1)
    }

    private fun shouldShowUpdate(config: RemoteConfig): Boolean {
        if (config.downloadUrl.isBlank()) return false
        if (config.latestVersionCode <= BuildConfig.VERSION_CODE) return false
        // 低于最低支持版本：强制更新，不给「取消」也不记忽略。
        if (config.isUpdateRequired()) return true
        val ignored = getSharedPreferences("update", MODE_PRIVATE).getInt("ignored_version_code", -1)
        return config.latestVersionCode != ignored
    }

    /** 「取消」：记住当前版本号，直到发布新版本才再次弹出更新。 */
    private fun dismissUpdate() {
        getSharedPreferences("update", MODE_PRIVATE)
            .edit().putInt("ignored_version_code", remoteConfig.latestVersionCode).apply()
        updateVisible = false
    }

    private fun openLink(url: String) {
        if (url.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("打开链接失败：${it.message.orEmpty()}") }
    }

    private fun openUpdate(url: String) {
        if (url.isBlank()) {
            toast("这个下载源还没配好")
            return
        }
        updateVisible = false
        if (RemoteRuntime.enableReport()) {
            RemoteConfigLoader.reportAsync(this, "update_click", mapOf("target" to remoteConfig.latestVersionCode.toString()))
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("打开下载链接失败：${it.message.orEmpty()}") }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------- 配色

private val AppColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F2EC),
    onPrimaryContainer = Color(0xFF07332E),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    background = Color(0xFFF6F7F9),
    surface = Color.White,
    onSurface = Color(0xFF0F1419),
    surfaceVariant = Color(0xFFF1F3F7),
    onSurfaceVariant = Color(0xFF5A6472),
    outline = Color(0xFFE3E7EE),
    outlineVariant = Color(0xFFEDF0F5),
)

private object Ink {
    val primary = Color(0xFF0F1419)
    val muted = Color(0xFF6B7280)
    val faint = Color(0xFF9AA3AF)
    val line = Color(0xFFECEEF3)
    val card = Color.White
    val tint = Color(0xFFF4F6FA)
    val accent = Color(0xFF0F766E)
    val accentSoft = Color(0xFFD7F2EC)
    val indigo = Color(0xFF4F46E5)
    val headerTop = Color(0xFF0D1626)
    val headerBottom = Color(0xFF152238)
}

// ---------------------------------------------------------------- 根容器：转场 + 左滑返回

/**
 * 两个页面之间的切换容器。
 *
 * 进入时从右侧滑入、淡入；返回时向右滑出。赞助页额外支持「从左缘往右划」退出，
 * 拖动过程中页面跟手位移，松手超过 40% 宽度或速度够快就返回，否则回弹。
 */
@Composable
private fun AppRoot(
    showSponsor: Boolean,
    onSponsorChange: (Boolean) -> Unit,
    store: SettingsStore,
    config: RemoteConfig,
    importMessage: String,
    onImportQuestionBank: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartProjection: () -> Unit,
    onStartOverlay: () -> Unit,
) {
    AnimatedContent(
        targetState = showSponsor,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))) togetherWith
                    fadeOut(tween(160))
            } else {
                fadeIn(tween(200)) togetherWith
                    (slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(220)))
            }
        },
        label = "page",
    ) { sponsor ->
        if (sponsor) {
            SwipeBackBox(onBack = { onSponsorChange(false) }) {
                SponsorScreen(config = config, onBack = { onSponsorChange(false) })
            }
        } else {
            SettingsScreen(
                store = store,
                config = config,
                importMessage = importMessage,
                onImportQuestionBank = onImportQuestionBank,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onStartProjection = onStartProjection,
                onStartOverlay = onStartOverlay,
                onSponsor = { onSponsorChange(true) },
            )
        }
    }
}

@Composable
private fun SwipeBackBox(enabled: Boolean = true, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                widthPx = size.width.toFloat().coerceAtLeast(1f)
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val passed = offsetX.value > widthPx * 0.38f
                        scope.launch {
                            if (passed) {
                                offsetX.animateTo(widthPx, tween(200))
                                onBack()
                                offsetX.snapTo(0f)
                            } else {
                                offsetX.animateTo(0f, tween(220))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, tween(200)) }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        // 只允许往右拖（返回方向），往左拖不动。
                        val next = (offsetX.value + dragAmount).coerceIn(0f, widthPx)
                        scope.launch { offsetX.snapTo(next) }
                    },
                )
            }
            .graphicsLayer {
                translationX = offsetX.value
                // 拖得越远，稍微缩小一点，做出「上层页面浮起来」的层次感
                val progress = (offsetX.value / widthPx).coerceIn(0f, 1f)
                scaleX = 1f - progress * 0.04f
                scaleY = 1f - progress * 0.04f
            },
    ) {
        content()
    }
}

// ---------------------------------------------------------------- 设置页

private enum class Tab(val label: String) {
    Model("模型"),
    Answer("答题"),
    Launch("启动"),
}

@Composable
private fun SettingsScreen(
    store: SettingsStore,
    config: RemoteConfig,
    importMessage: String,
    onImportQuestionBank: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onStartProjection: () -> Unit,
    onStartOverlay: () -> Unit,
    onSponsor: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialSettings = remember { store.currentSettings }
    var draft by remember { mutableStateOf(initialSettings) }
    var maxWaitText by remember { mutableStateOf(initialSettings.maxWaitTimeoutSeconds.toString()) }
    var intervalText by remember { mutableStateOf(initialSettings.autoCaptureIntervalSeconds.toString()) }
    var saveMessage by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var showHistory by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Model) }
    val answerCache = remember(context) { AnswerCache(context) }
    val questionBankStore = remember(context) { QuestionBankStore(context) }
    var bankInfo by remember(importMessage) { mutableStateOf(questionBankStore.info()) }

    LaunchedEffect(Unit) {
        history = answerCache.recent()
        bankInfo = questionBankStore.info()
    }

    val toSave = draft.copy(
        maxWaitTimeoutSeconds = maxWaitText.toIntOrNull()?.coerceIn(5, 180) ?: 60,
        autoCaptureIntervalSeconds = intervalText.toIntOrNull()?.coerceIn(1, 5) ?: 2,
    )
    LaunchedEffect(toSave) {
        if (toSave == store.currentSettings) return@LaunchedEffect
        delay(600)
        runCatching { store.save(toSave) }
            .onSuccess { saveMessage = Zh.saved }
            .onFailure { saveMessage = "${Zh.saveFailed}: ${it.message.orEmpty()}" }
    }
    // 立即生效，不用等防抖保存，用户切完马上答题就是新模式。
    LaunchedEffect(draft.answerMode) {
        PromptFactory.setAnswerMode(draft.answerMode)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            title = config.appTitle,
            subtitle = config.subtitle,
            version = "v${BuildConfig.VERSION_NAME}",
        )

        SegmentedTabs(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "tab",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (current) {
                        Tab.Model -> ModelTab(
                            draft = draft,
                            onChange = { draft = it },
                        )

                        Tab.Answer -> AnswerTab(
                            draft = draft,
                            onChange = { draft = it },
                            bankInfo = bankInfo,
                            importMessage = importMessage,
                            maxWaitText = maxWaitText,
                            intervalText = intervalText,
                            onMaxWaitChange = { maxWaitText = it },
                            onIntervalChange = { intervalText = it },
                            onImportQuestionBank = onImportQuestionBank,
                            onClearQuestionBank = {
                                questionBankStore.clear()
                                bankInfo = questionBankStore.info()
                                draft = draft.copy(questionBankMode = false)
                            },
                        )

                        Tab.Launch -> LaunchTab(
                            config = config,
                            hint = saveMessage,
                            historyCount = history.size,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onStartProjection = onStartProjection,
                            onStartOverlay = onStartOverlay,
                            onShowHistory = {
                                scope.launch { history = answerCache.recent() }
                                showHistory = true
                            },
                            onSponsor = onSponsor,
                        )
                    }
                }
            }
        }

        BottomBar(
            onStartProjection = onStartProjection,
            onStartOverlay = onStartOverlay,
        )
    }

    if (showHistory) {
        HistoryDialog(
            history = history,
            onDismiss = { showHistory = false },
            onClear = {
                scope.launch {
                    answerCache.clear()
                    history = emptyList()
                }
            },
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String, version: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Ink.headerTop, Ink.headerBottom)))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399)),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = version,
                    color = Color(0xFF8FA3BC),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
            )
            Text(
                text = subtitle,
                color = Color(0xFF9FB2C8),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SegmentedTabs(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.tint)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { item ->
            val selected = item == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    color = if (selected) Ink.primary else Ink.muted,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(onStartProjection: () -> Unit, onStartOverlay: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 12.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onStartProjection,
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) { Text(Zh.authorizeCapture, fontSize = 13.5.sp) }

            Button(
                onClick = onStartOverlay,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink.primary),
                contentPadding = PaddingValues(vertical = 13.dp),
            ) {
                Text(Zh.startOverlay, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------- 三个 Tab

@Composable
private fun ModelTab(draft: AppSettings, onChange: (AppSettings) -> Unit) {
    Card8("回答风格", "悬浮窗空间有限，选你想要的输出长度") {
        AnswerModePicker(draft.answerMode) { onChange(draft.copy(answerMode = it)) }
    }
    Card8("模型接口", "填兼容 OpenAI 的地址；预设也行") {
        ProviderEditor(
            presets = ModelPresets.flash,
            provider = draft.flashProvider,
            onChange = { onChange(draft.copy(flashProvider = it)) },
        )
    }
}

@Composable
private fun AnswerTab(
    draft: AppSettings,
    onChange: (AppSettings) -> Unit,
    bankInfo: QuestionBankStore.ImportInfo,
    importMessage: String,
    maxWaitText: String,
    intervalText: String,
    onMaxWaitChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onImportQuestionBank: () -> Unit,
    onClearQuestionBank: () -> Unit,
) {
    Card8("本地题库", "导入后先查题库，命中就不用请求模型") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Ink.tint)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    bankInfo.count <= 0 -> Zh.noQuestionBank
                    bankInfo.name == "内置题库" -> "内置题库 · ${bankInfo.count} 道"
                    else -> "${bankInfo.count} 道 · ${bankInfo.name}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onImportQuestionBank,
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink.primary),
            ) { Text(Zh.importBank, fontSize = 13.sp) }
            OutlinedButton(
                onClick = onClearQuestionBank,
                enabled = bankInfo.count > 0,
                shape = RoundedCornerShape(11.dp),
            ) { Text(Zh.clearBank, fontSize = 13.sp) }
        }
        SwitchRow(Zh.enableBank, draft.questionBankMode) { onChange(draft.copy(questionBankMode = it)) }
        ValueSlider(
            label = "匹配阈值",
            value = draft.bankMatchThreshold,
            text = "${(draft.bankMatchThreshold * 100).toInt()}%",
            range = AppSettings.BANK_MATCH_MIN..AppSettings.BANK_MATCH_MAX,
            onValueChange = {
                onChange(draft.copy(bankMatchThreshold = it.coerceIn(AppSettings.BANK_MATCH_MIN, AppSettings.BANK_MATCH_MAX)))
            },
        )
        if (importMessage.isNotBlank()) {
            Text(importMessage, color = Ink.accent, style = MaterialTheme.typography.bodySmall)
        }
    }

    Card8("识别与悬浮窗", "等待时间、截图区域、透明度") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(Zh.maxWaitShort, maxWaitText, onMaxWaitChange, Modifier.weight(1f))
            NumberField(Zh.intervalShort, intervalText, onIntervalChange, Modifier.weight(1f))
        }
        SwitchRow(Zh.visionFallback, draft.useVisionWhenOcrWeak) { onChange(draft.copy(useVisionWhenOcrWeak = it)) }
        SwitchRow(Zh.clickThrough, draft.clickThrough) { onChange(draft.copy(clickThrough = it)) }
        SwitchRow(Zh.useRoi, draft.roi != null) { enabled ->
            onChange(draft.copy(roi = if (enabled) draft.roi ?: RoiBox(0f, 0.35f, 1f, 0.65f) else null))
        }
        ValueSlider(
            label = Zh.overlayAlpha,
            value = draft.overlayAlpha,
            text = "${(draft.overlayAlpha * 100).toInt()}%",
            range = 0.35f..1f,
            onValueChange = { onChange(draft.copy(overlayAlpha = it.coerceIn(0.35f, 1f))) },
        )
    }
}

@Composable
private fun LaunchTab(
    config: RemoteConfig,
    hint: String,
    historyCount: Int,
    onRequestOverlayPermission: () -> Unit,
    onStartProjection: () -> Unit,
    onStartOverlay: () -> Unit,
    onShowHistory: () -> Unit,
    onSponsor: () -> Unit,
) {
    Card8("权限", "首次使用按顺序点一遍") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRequestOverlayPermission, shape = RoundedCornerShape(11.dp)) {
                Text(Zh.overlayPermission, fontSize = 13.sp)
            }
            OutlinedButton(onClick = onStartProjection, shape = RoundedCornerShape(11.dp)) {
                Text(Zh.authorizeCapture, fontSize = 13.sp)
            }
        }
        Text(
            "悬浮窗权限用于显示答案，截图授权用于读取题目。两者都授权后才能启动。",
            color = Ink.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
    }

    Card8("其他", null) {
        ListRow(
            title = Zh.history,
            value = if (historyCount > 0) "$historyCount 条" else Zh.historyEmpty,
            onClick = onShowHistory,
        )
        HorizontalDivider(color = Ink.line)
        ListRow(title = config.sponsorTitle, value = "", onClick = onSponsor)
        HorizontalDivider(color = Ink.line)
        ListRow(
            title = Zh.startOverlay,
            value = "",
            onClick = onStartOverlay,
        )
    }

    if (config.setupGuide.isNotBlank()) {
        Text(
            config.setupGuide,
            color = Ink.faint,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
    }
    if (config.securityNotice.isNotBlank()) {
        Text(
            config.securityNotice,
            color = Ink.faint,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
    }
    if (hint.isNotBlank()) {
        Text(hint, color = Ink.accent, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- 通用小组件

@Composable
private fun Card8(title: String, hint: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.primary,
                )
                if (!hint.isNullOrBlank()) {
                    Text(hint, fontSize = 11.5.sp, color = Ink.faint, lineHeight = 16.sp)
                }
            }
            content()
        }
    }
}

@Composable
private fun AnswerModePicker(value: AnswerMode, onChange: (AnswerMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnswerMode.entries.forEach { mode ->
            val selected = mode == value
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Ink.accentSoft else Ink.tint)
                    .clickable { onChange(mode) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = mode.label,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Ink.accent else Ink.primary,
                )
                Text(
                    text = if (mode == AnswerMode.ANSWER) "一行，只有答案" else "答案 + 一句依据",
                    fontSize = 11.sp,
                    color = if (selected) Color(0xFF3F7A72) else Ink.muted,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ProviderEditor(
    presets: List<ModelPreset>,
    provider: ProviderConfig,
    onChange: (ProviderConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(11.dp)) {
            Text(Zh.choosePreset, fontSize = 13.sp)
        }
        Text(
            provider.modelName.ifBlank { Zh.notConfigured },
            fontSize = 12.5.sp,
            color = Ink.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label, fontSize = 13.5.sp) },
                    onClick = {
                        expanded = false
                        onChange(preset.provider.copy(apiKey = provider.apiKey))
                    },
                )
            }
        }
    }

    SoftField(provider.baseUrl, Zh.baseUrl) { onChange(provider.copy(baseUrl = it)) }
    SoftField(provider.apiKey, Zh.apiKey, password = true) { onChange(provider.copy(apiKey = it)) }
    SoftField(provider.modelName, Zh.modelName) { onChange(provider.copy(modelName = it)) }

    SwitchRow(Zh.searchHint, provider.enableSearchHint) { onChange(provider.copy(enableSearchHint = it)) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { reasoningExpanded = true }, shape = RoundedCornerShape(11.dp)) {
            Text(Zh.reasoningEffort, fontSize = 13.sp)
        }
        Text(provider.reasoningEffort.ifBlank { Zh.reasoningOff }, fontSize = 12.5.sp, color = Ink.muted)
        DropdownMenu(expanded = reasoningExpanded, onDismissRequest = { reasoningExpanded = false }) {
            listOf("", "low", "medium", "high", "xhigh", "max").forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.ifBlank { Zh.reasoningOff }, fontSize = 13.5.sp) },
                    onClick = { reasoningExpanded = false; onChange(provider.copy(reasoningEffort = item)) },
                )
            }
        }
    }
}

@Composable
private fun SoftField(
    value: String,
    label: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.accent,
            unfocusedBorderColor = Ink.line,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Ink.tint,
        ),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.accent,
            unfocusedBorderColor = Ink.line,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Ink.tint,
        ),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.5.sp, color = Ink.primary, modifier = Modifier.weight(1f))
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Ink.accent,
                uncheckedColor = Ink.faint,
            ),
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    text: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 13.sp, color = Ink.primary)
            Text(text, fontSize = 12.5.sp, color = Ink.muted)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Ink.accent,
                activeTrackColor = Ink.accent,
                inactiveTrackColor = Ink.line,
            ),
        )
    }
}

@Composable
private fun ListRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 13.5.sp, color = Ink.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotBlank()) {
                Text(value, fontSize = 12.5.sp, color = Ink.faint)
            }
            Spacer(Modifier.width(6.dp))
            Text("›", fontSize = 17.sp, color = Ink.faint)
        }
    }
}

@Composable
private fun HistoryDialog(
    history: List<HistoryItem>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(Zh.history, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (history.isEmpty()) {
                    Text(Zh.historyEmpty, color = Ink.muted, fontSize = 13.sp)
                } else {
                    history.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                item.questionText.take(120),
                                fontSize = 12.5.sp,
                                color = Ink.muted,
                                lineHeight = 17.sp,
                            )
                            Text(
                                item.answer.answer.ifBlank { item.answer.raw }.take(120),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink.primary,
                            )
                            HorizontalDivider(color = Ink.line)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Zh.close) } },
        dismissButton = {
            TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text(Zh.clearHistory) }
        },
    )
}

private object Zh {
    const val modelSection = "模型配置"
    const val questionBankSection = "题库"
    const val noQuestionBank = "尚未导入题库"
    const val importBank = "导入题库"
    const val clearBank = "清除"
    const val enableBank = "启用本地题库"
    const val maxWaitShort = "最大等待（秒）"
    const val intervalShort = "截图间隔（秒）"
    const val visionFallback = "识图兜底"
    const val clickThrough = "点击穿透"
    const val useRoi = "固定截图区域"
    const val overlayAlpha = "悬浮窗透明度"
    const val searchHint = "允许联网检索"
    const val reasoningEffort = "推理强度"
    const val reasoningOff = "关闭"
    const val apiKey = "密钥"
    const val baseUrl = "接口地址"
    const val modelName = "模型名称"
    const val choosePreset = "预设"
    const val notConfigured = "未配置"
    const val history = "答题记录"
    const val historyEmpty = "暂无记录"
    const val clearHistory = "清空"
    const val close = "关闭"
    const val saved = "已保存"
    const val saveFailed = "保存失败"
    const val overlayPermission = "悬浮窗权限"
    const val authorizeCapture = "授权截图"
    const val startOverlay = "启动悬浮窗"
    const val captureAuthorized = "截图会话已就绪"
    const val captureDenied = "未授权截图"
    const val needOverlayPermission = "请先开启悬浮窗权限"
    const val overlayStarting = "正在启动悬浮窗"
    const val overlayStartFailed = "启动悬浮窗失败"
    const val openPermissionFailed = "打开权限页失败"
    const val captureLaunchFailed = "启动截图授权失败"
    const val captureSessionFailed = "截图会话启动失败"
}
