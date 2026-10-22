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
        val text = raw.takeIf { it.isNotBlank() }
            ?: answer.takeIf { it.isNotBlank() }
            ?: displayText()
        return text.toOverlayText(com.quizassist.prompt.PromptFactory.answerMode())
    }

    private fun String.toOverlayText(mode: com.quizassist.model.AnswerMode): String {
        val answer = extractAnswer()
        if (answer.isBlank()) return "没抓到答案，重拍一次"
        return if (mode == com.quizassist.model.AnswerMode.BRIEF) {
            val lines = answer.lines().map { it.trim() }.filter { it.isNotBlank() }
            lines.take(2).joinToString("\n").take(160)
        } else {
            answer.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60)
        }
    }

    /**
     * 从模型输出里捞出真正的答案。
     *
     * 带思考的模型会把「我们需要回答用户：…」「首先看题干…」写进正文，最后才给答案；
     * maxTokens 不够时甚至只剩思考。这里按优先级兜底：
     *   1. 显式的「答案：xxx」「Answer: xxx」——取最后一次出现
     *   2. 长得像答案的行（单个字母 / A,C / 正确 / 错误 / √）——从后往前找
     *   3. 过滤掉明显的思考行后，取最后一行
     */
    /**
     * 从模型输出里捞出真正的答案。
     *
     * 优先级从硬到软：
     *   1. 锚点 `ANSWER: B` —— 提示词要求「写了推理就必须单独一行给锚点」，最可信
     *   2. JSON 的 `"answer"` 字段（联网模式，截断也能捞）
     *   3. `答案：B` / `答案是 B` 这类标记 —— 但会收集**全部**候选做一致性判断
     *   4. 长得像答案的独立行
     *   5. 去掉自言自语后的末行（太长就判为没抓到）
     *
     * 第 3 步是这次的重点：模型左右互搏时会先写「答案可能是 A」再改成 B，
     * 直接取最后一个太赌；现在先看候选是否唯一，不唯一才靠转折词裁决，
     * 仍然拿不准就明说「AI 拿不准」，不把中间那个错答案顶上去。
     */
    private fun String.extractAnswer(): String {
        val cleaned = replace(Regex("""(?i)\bassistant\b[:：]?\s*"""), "").trim()
        if (cleaned.isEmpty()) return ""

        // 1) 锚点
        ANCHOR.findAll(cleaned).lastOrNull()
            ?.groupValues?.getOrNull(1)?.trim()?.stripAnswerLabel()
            ?.takeIf { it.isNotBlank() }?.let { return it }

        // 2) JSON
        Regex("\"answer\"\\s*:\\s*\"([^\"]{1,40})\"")
            .findAll(cleaned).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
            ?.stripAnswerLabel()?.takeIf { it.isNotBlank() }?.let { return it }

        // 3) 标记答案：收集全部候选
        val labelled = LABELLED.findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.stripAnswerLabel() }
            .filter { it.isNotBlank() }
            .toList()
        if (labelled.isNotEmpty()) {
            val distinct = labelled.map { normalizeAnswer(it) }.distinct()
            if (distinct.size == 1) return labelled.last()
            // 多个不同候选：有转折词就信最后一个，否则不敢猜
            return if (CONTRADICTION.containsMatchIn(cleaned)) labelled.last() else UNCERTAIN
        }

        val lines = cleaned.lines().map { it.trim().stripAnswerLabel() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // 4) 长得像答案的独立行
        lines.lastOrNull { ANSWER_SHAPE.matches(it) }?.let { return it }

        // 5) 去掉思考行后的末行
        val meaningful = lines.filterNot { THINKING_HINT.containsMatchIn(it) }
        val last = meaningful.lastOrNull() ?: return ""
        return if (last.length > 30) "" else last
    }

    /** 只保留字母/判断词的骨架，用来判断两个候选是不是同一个答案。 */
    private fun normalizeAnswer(value: String): String {
        val letters = Regex("[A-Da-d]").findAll(value).map { it.value.uppercase() }.toList()
        if (letters.size == 1 && value.trim().length <= 4) return letters.first()
        if (letters.size >= 2 && value.trim().length <= 8) return letters.sorted().joinToString(",")
        return value.trim().lowercase()
    }

    private fun String.stripAnswerLabel(): String =
        replace(Regex("""^(答案|答|选|选择|正确选项|解析|理由|依据|分析)\s*[:：]\s*"""), "").trim()

    /** 提示词要求的锚点：写了推理就必须单独一行给这个。 */
    private val ANCHOR = Regex("""(?i)\bANSWER\s*[:：]\s*([^\n]{1,30})""")

    /** 「答案：B」「答案是 B」「正确答案为 B」 */
    private val LABELLED = Regex(
        """(?:最终答案|正确答案|答案)\s*(?:是|为|[:：])?\s*([A-D](?:\s*[,，、/]\s*[A-D])*|正确|错误|对|错|√|×|[^\n。；;，,]{1,30})""",
    )

    /** 出现这些词，说明前面那个被推翻了。 */
    private val CONTRADICTION = Regex("""(?:但|不过|可是|其实|应该是|改为|修正|不对|推翻|重新看)""")

    private const val UNCERTAIN = "AI 拿不准，重拍一次"

    /** 单个字母、字母组合、判断词，或者很短的选项式文本。 */
    private val ANSWER_SHAPE = Regex(
        """^(?:[A-D](?:\s*[,，、/]\s*[A-D])*|[A-D][\.、．:：]\s*\S{0,20}|正确|错误|对|错|√|×|T|F|true|false|[^\p{Punct}\s]{1,12})$""",
    )

    /** 明显的自言自语开头，命中的行在兜底时会被丢掉。 */
    private val THINKING_HINT = Regex(
        """^(?:我们需要|我们|首先|接下来|然后|分析|根据题目|题干|用户|需要|先|让我|好的|嗯|这个题目|考虑到|综上|因此|所以要求|按照要求之)""",
    )

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
