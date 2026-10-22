package com.quizassist.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.quizassist.model.OverlayState
import com.quizassist.model.SolveSnapshot

class OverlayRenderer(
    context: Context,
    private val onCapture: () -> Unit,
    private val onAutoCaptureToggle: () -> Unit,
    private val onCollapseChanged: (Boolean) -> Unit,
) {
    val root: FrameLayout = FrameLayout(context)
    private val ball = TextView(context)
    private val panel = LinearLayout(context)
    private val status = TextView(context)
    private val flashTitle = TextView(context)
    private val flash = TextView(context)
    private val copy = Button(context)
    private val autoCapture = Button(context)
    private var copyText = ""

    val dragTargets: List<View>
        get() = listOf(ball, status)

    init {
        root.setPadding(6, 6, 6, 6)
        setupBall()
        setupPanel(context)
        root.addView(ball)
        root.addView(panel)
        render(SolveSnapshot())
        setCollapsed(true, notify = false)
    }

    fun render(snapshot: SolveSnapshot) {
        status.text = when (snapshot.state) {
            OverlayState.Idle -> "等待"
            OverlayState.Capturing -> "正在截图..."
            OverlayState.Recognizing -> "正在识别..."
            OverlayState.FlashReady -> "快速答案已返回"
            OverlayState.Error -> "发生错误"
        } + snapshot.notice.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        flashTitle.text = "快速回答 ${snapshot.flashElapsedMs?.let { "(${it}ms)" }.orEmpty()}"
        flash.text = snapshot.flashText.ifBlank { "-" }
        copyText = snapshot.flashText
    }

    fun appendFlash(token: String) {
        if (flash.text == "-") flash.text = ""
        flash.append(token)
        copyText = flash.text.toString()
    }

    fun setCollapsed(value: Boolean, notify: Boolean = true) {
        ball.visibility = if (value) View.VISIBLE else View.GONE
        panel.visibility = if (value) View.GONE else View.VISIBLE
        if (notify) onCollapseChanged(value)
    }

    fun setAutoCaptureActive(active: Boolean) {
        autoCapture.text = if (active) "停止连拍" else "连续截图"
        autoCapture.setTextColor(if (active) 0xFFE5484D.toInt() else 0xFF0F766E.toInt())
    }

    private fun setupBall() {
        ball.text = "A"
        ball.textSize = 26f
        ball.typeface = Typeface.DEFAULT_BOLD
        ball.gravity = Gravity.CENTER
        ball.setTextColor(Color.WHITE)
        ball.setShadowLayer(6f, 0f, 2f, 0x66000000)
        ball.background = ballBackground()
        ball.setOnClickListener { setCollapsed(false) }
        ball.layoutParams = FrameLayout.LayoutParams(72, 72)
    }

    private fun setupPanel(context: Context) {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(16, 14, 16, 14)
        panel.background = rounded(0xF21B2430.toInt(), 18f)
        panel.layoutParams = FrameLayout.LayoutParams(680, FrameLayout.LayoutParams.WRAP_CONTENT)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val capture = Button(context).apply {
            text = "截图答题"
            setOnClickListener { onCapture() }
        }
        autoCapture.text = "连续截图"
        autoCapture.setTextColor(0xFF0F766E.toInt())
        autoCapture.setOnClickListener { onAutoCaptureToggle() }
        row.addView(capture)
        row.addView(autoCapture)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val collapse = Button(context).apply {
            text = "收起"
            setOnClickListener { setCollapsed(true) }
        }
        copy.text = "复制"
        copy.setOnClickListener {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("quiz-answer", copyText))
        }
        actionRow.addView(copy)
        actionRow.addView(collapse)

        setupText(status, 13f, 0xFFD7DEE8.toInt(), bold = false)
        setupText(flashTitle, 13f, 0xFF8FE3CF.toInt(), bold = true)
        setupText(flash, 20f, 0xFFFFFFFF.toInt(), bold = false)

        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(actionRow)
            addView(status)
            addDivider(context)
            addView(flashTitle)
            addView(flash)
        }
        scroll.addView(content)
        panel.addView(scroll)
    }

    private fun setupText(view: TextView, size: Float, color: Int, bold: Boolean) {
        view.setTextColor(color)
        view.textSize = size
        view.includeFontPadding = true
        view.setPadding(0, 8, 0, 8)
        if (bold) view.typeface = Typeface.DEFAULT_BOLD
    }

    private fun LinearLayout.addDivider(context: Context) {
        addView(
            View(context).apply {
                setBackgroundColor(0x334B5563)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1,
                ).apply {
                    topMargin = 6
                    bottomMargin = 6
                }
            },
        )
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun ballBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0xFF4F46E5.toInt(),
                0xFF7C3AED.toInt(),
                0xFF0EA5E9.toInt(),
            ),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(2, 0x40FFFFFF)
        }
}
