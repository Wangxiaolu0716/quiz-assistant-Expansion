package com.quizassist.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.quizassist.cache.AnswerCache
import com.quizassist.engine.QuizSolverEngine
import com.quizassist.model.OverlayState
import com.quizassist.model.SolveEvent
import com.quizassist.model.SolveMode
import com.quizassist.model.SolveSnapshot
import com.quizassist.network.LLMRepository
import com.quizassist.ocr.OcrProcessor
import com.quizassist.questionbank.QuestionBankMatcher
import com.quizassist.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object OverlayController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var renderer: OverlayRenderer? = null
    private var params: WindowManager.LayoutParams? = null
    private var settingsStore: SettingsStore? = null
    private var engine: QuizSolverEngine? = null
    private var appContext: Context? = null
    private var captureSessionReady: Boolean = false
    private var solveJob: Job? = null
    private var autoCaptureJob: Job? = null
    private var solving: Boolean = false
    private var snapshot = SolveSnapshot()

    fun show(context: Context): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) {
            error("未授权悬浮窗权限")
        }
        if (renderer != null) return@runCatching

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val store = SettingsStore(appContext)
        val solver = QuizSolverEngine(
            OcrProcessor(),
            LLMRepository(),
            AnswerCache(appContext),
            QuestionBankMatcher(appContext),
        )
        val overlay = OverlayRenderer(
            context = appContext,
            onCapture = { captureAndSolve() },
            onAutoCaptureToggle = { toggleAutoCapture() },
            onCollapseChanged = { updateOverlayFlags() },
        )
        val lp = overlayParams()

        windowManager = wm
        settingsStore = store
        engine = solver
        this.appContext = appContext
        renderer = overlay
        params = lp
        attachDrag()
        wm.addView(overlay.root, lp)

        scope.launch {
            val settings = store.fresh()
            overlay.root.alpha = settings.overlayAlpha
            updateOverlayFlags()
        }
    }

    fun hide() {
        solveJob?.cancel()
        solveJob = null
        autoCaptureJob?.cancel()
        autoCaptureJob = null
        solving = false
        val wm = windowManager
        val overlay = renderer
        if (wm != null && overlay != null) {
            runCatching { wm.removeView(overlay.root) }
        }
        windowManager = null
        renderer = null
        params = null
        settingsStore = null
        engine = null
        appContext = null
        captureSessionReady = false
        snapshot = SolveSnapshot()
    }

    private fun captureAndSolve() {
        val context = appContext ?: return
        val overlay = renderer ?: return
        solveJob?.cancel()
        updateOverlayFlags()
        snapshot = SolveSnapshot(state = OverlayState.Capturing, notice = "正在启动截图服务")
        overlay.render(snapshot)
        if (ProjectionPermissionStore.data == null) {
            onCaptureFailed("屏幕截图未授权或已失效，请回到主界面重新授权")
            return
        }
        overlay.root.visibility = View.INVISIBLE
        solving = true
        val hiddenAt = SystemClock.uptimeMillis()
        val intent = CaptureSessionService.captureIntent(context, hiddenAt)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            overlay.root.visibility = View.VISIBLE
            onCaptureFailed("启动截图会话失败：${it.message.orEmpty()}")
        }
    }

    fun ensureCaptureSession(context: Context): Result<Unit> = runCatching {
        val app = context.applicationContext
        if (ProjectionPermissionStore.data == null) {
            error("屏幕截图未授权")
        }
        val intent = CaptureSessionService.startIntent(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun onCaptureSessionReady() {
        captureSessionReady = true
        val overlay = renderer ?: return
        snapshot = snapshot.copy(notice = "截图会话已就绪，下次可直接截图")
        overlay.render(snapshot)
    }

    fun onCaptureSessionStopped() {
        captureSessionReady = false
    }

    fun onCaptured(bitmap: Bitmap) {
        val store = settingsStore ?: return
        val solver = engine ?: return
        val overlay = renderer ?: return
        overlay.root.visibility = View.VISIBLE
        solveJob?.cancel()
        solving = true
        solveJob = scope.launch {
            val settings = store.fresh()
            snapshot = snapshot.copy(
                state = OverlayState.Recognizing,
                preview = if (settings.showImagePreview) bitmap else null,
                notice = "正在 OCR，并启动快速模型",
            )
            overlay.render(snapshot)
            solver.solve(bitmap, settings).collect { event -> handleEvent(event) }
        }.also { it.invokeOnCompletion { solving = false } }
    }

    fun onCaptureFailed(message: String) {
        val overlay = renderer ?: return
        solving = false
        overlay.root.visibility = View.VISIBLE
        snapshot = snapshot.copy(state = OverlayState.Error, notice = message)
        overlay.render(snapshot)
    }

    private fun toggleAutoCapture() {
        val overlay = renderer ?: return
        if (autoCaptureJob?.isActive == true) {
            autoCaptureJob?.cancel()
            autoCaptureJob = null
            overlay.setAutoCaptureActive(false)
            snapshot = snapshot.copy(notice = "连续截图已停止")
            overlay.render(snapshot)
        } else {
            autoCaptureJob = scope.launch { autoCaptureLoop() }
            overlay.setAutoCaptureActive(true)
        }
    }

    private suspend fun autoCaptureLoop() {
        val store = settingsStore ?: return
        val overlay = renderer ?: return
        while (true) {
            if (ProjectionPermissionStore.data == null) {
                snapshot = snapshot.copy(notice = "截图未授权，连续截图已停止")
                overlay.render(snapshot)
                overlay.setAutoCaptureActive(false)
                autoCaptureJob = null
                return
            }
            captureAndSolve()
            while (solving) delay(200)
            val interval = store.fresh().autoCaptureIntervalSeconds.coerceIn(1, 5)
            snapshot = snapshot.copy(notice = "连续截图中，${interval} 秒后识别下一题")
            overlay.render(snapshot)
            delay(interval * 1000L)
        }
    }

    private fun handleEvent(event: SolveEvent) {
        val overlay = renderer ?: return
        snapshot = when (event) {
            SolveEvent.Capturing -> snapshot.copy(state = OverlayState.Capturing)
            is SolveEvent.OcrFinished -> snapshot.copy(
                state = OverlayState.Recognizing,
                ocrText = event.text,
                notice = "OCR 置信度 ${(event.confidence * 100).toInt()}%，模式 ${event.mode.displayName()}",
            )
            is SolveEvent.QuestionBankStatus -> snapshot.copy(
                notice = when {
                    !event.enabled -> "题库模式已关闭，启动快速模型"
                    event.entryCount <= 0 -> "题库加载失败，启动快速模型"
                    event.matched -> "题库 ${event.entryCount}条，已命中，不请求模型"
                    else -> "题库 ${event.entryCount}条未命中，启动快速模型"
                },
            )
            is SolveEvent.CacheHit -> snapshot.copy(
                state = OverlayState.FlashReady,
                flashText = event.answer.displayText(),
                notice = "命中本地缓存",
            )
            is SolveEvent.QuestionBankHit -> snapshot.copy(
                state = OverlayState.FlashReady,
                flashText = event.answer.displayText(),
                flashElapsedMs = event.elapsedMs,
                notice = "命中本地题库 ${(event.score * 100).toInt()}%",
            )
            is SolveEvent.FlashToken -> {
                overlay.appendFlash(event.token)
                return
            }
            is SolveEvent.FlashAnswer -> snapshot.copy(
                state = OverlayState.FlashReady,
                flashText = event.answer.flashDisplayText(),
                flashElapsedMs = event.elapsedMs,
                notice = "快速答案已显示",
            )
            is SolveEvent.FlashFailure -> snapshot.copy(
                state = OverlayState.Error,
                notice = "快速模型失败：${event.message}",
            )
            is SolveEvent.Failure -> snapshot.copy(state = OverlayState.Error, notice = event.message)
        }
        overlay.render(snapshot)
    }

    private fun com.quizassist.model.StructuredAnswer.displayText(): String =
        buildString {
            if (answer.isNotBlank()) appendLine("答案：$answer")
            if (confidence.isNotBlank()) appendLine("置信度：$confidence")
            if (reasoning.isNotBlank()) appendLine(reasoning)
            if (isBlank()) append(raw)
        }.trim()

    /**
     * 悬浮窗上真正显示的文本。
     *
     * 以前这里会把模型输出原样铺上去，模型稍微啰嗦一点就占满半个屏幕。
     * 现在按用户选的 [AnswerMode] 裁剪：
     *   ANSWER  只留第一行答案（最多 60 字）
     *   BRIEF   留前两行（答案 + 一行依据）
     * 顺手把「答案：」「解析：」这类前缀剥掉，模型爱加，用户不想看。
     */
    private fun com.quizassist.model.StructuredAnswer.flashDisplayText(): String {
        val text = raw.takeIf { it.isNotBlank() } ?: displayText()
        return text.toOverlayText(com.quizassist.prompt.PromptFactory.answerMode())
    }

    private fun String.toOverlayText(mode: com.quizassist.model.AnswerMode): String {
        val lines = replace(Regex("""(?i)\bassistant\b[:：]?\s*"""), "")
            .lines()
            .map { it.trim().stripAnswerLabel() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        return if (mode == com.quizassist.model.AnswerMode.BRIEF) {
            lines.take(2).joinToString("\n")
        } else {
            lines.first().take(60)
        }
    }

    private fun String.stripAnswerLabel(): String =
        replace(Regex("""^(答案|答|选|选择|正确选项|解析|理由|依据|分析)\s*[:：]\s*"""), "").trim()

    private fun SolveMode.displayName(): String = when (this) {
        SolveMode.TextOnly -> "文本"
        SolveMode.Vision -> "视觉"
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }
    }

    private fun updateOverlayFlags() {
        val store = settingsStore ?: return
        val overlay = renderer ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        scope.launch {
            val settings = store.fresh()
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (settings.clickThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
            overlay.root.alpha = settings.overlayAlpha
            runCatching { wm.updateViewLayout(overlay.root, lp) }
        }
    }

    private fun attachDrag() {
        val overlay = renderer ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        val listener = ViewDragTouchListener(
            getX = { lp.x },
            getY = { lp.y },
            setPosition = { x, y ->
                lp.x = x
                lp.y = y
                runCatching { wm.updateViewLayout(overlay.root, lp) }
            },
        )
        overlay.dragTargets.forEach { it.setOnTouchListener(listener) }
    }

    private class ViewDragTouchListener(
        private val getX: () -> Int,
        private val getY: () -> Int,
        private val setPosition: (Int, Int) -> Unit,
    ) : android.view.View.OnTouchListener {
        private var downX = 0
        private var downY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = getX()
                    downY = getY()
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 6f || kotlin.math.abs(dy) > 6f) moved = true
                    setPosition(downX + dx.toInt(), downY + dy.toInt())
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }
                else -> return false
            }
        }
    }
}
