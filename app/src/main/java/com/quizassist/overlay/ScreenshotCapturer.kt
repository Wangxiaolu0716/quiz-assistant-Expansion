package com.quizassist.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 屏幕抓帧。
 *
 * 卡顿的根源和修法（2.5.9）：
 *
 * 原来 VirtualDisplay 一建好就常驻镜像，屏幕每变化一帧都会在 **主线程** 上分配两张
 * 全屏 ARGB_8888（1080×2400 ≈ 10 MB）再做一次 `copyPixelsFromBuffer`。刷视频时有动画，
 * 一秒能来几十帧，主线程直接被打满——表现就是「授权完就卡」。
 *
 * 现在：
 *  1. 空闲时 `setSurface(null)` 把镜像停掉，VD 留着但不再产帧，CPU/内存归零；
 *  2. 抓图时才接回 surface，拿到帧立刻断开；
 *  3. 解码搬到独立 HandlerThread，主线程不参与；
 *  4. `rowStride` 对齐时跳过第二次 `createBitmap`，少一次全屏分配；
 *  5. `maxImages` 降到 2，并且 300ms 内不重复处理同一批帧。
 *
 * 代价是每次截图多几毫秒唤醒开销，换来待机不卡。
 */
class ScreenshotCapturer(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("quiz-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var latestFrame: Bitmap? = null
    private var latestFrameAt: Long = 0L
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0

    /** 是否正在等一帧。只有 true 时监听器才做解码，避免白跑。 */
    @Volatile
    private var awaitingFrame = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            cleanupDisplay()
            mediaProjection = null
            ProjectionPermissionStore.clear()
        }
    }

    fun ensureProjection(): Boolean {
        if (mediaProjection != null) return true
        val code = ProjectionPermissionStore.resultCode
        val data = ProjectionPermissionStore.data ?: return false
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = runCatching {
            manager.getMediaProjection(code, data).also {
                it.registerCallback(projectionCallback, mainHandler)
            }
        }.getOrNull()
        return mediaProjection != null
    }

    suspend fun captureFullScreen(): Bitmap = capture(afterUptimeMs = 0L)

    suspend fun captureFreshFrame(afterUptimeMs: Long): Bitmap = capture(afterUptimeMs)

    /**
     * 抓一张此刻的屏幕。
     *
     * @param afterUptimeMs 只接受这个时间点之后产生的帧；0 表示最近一帧即可。
     */
    suspend fun capture(afterUptimeMs: Long = 0L): Bitmap {
        if (!ensureProjection()) error("MediaProjection permission missing")
        val reader = ensureDisplay() ?: error("No screen frame available")

        val deadline = afterUptimeMs.coerceAtLeast(0L)
        val frame = withTimeoutOrNull(if (deadline > 0) 2500L else 1500L) {
            wake()
            try {
                while (latestFrame == null || latestFrameAt < deadline) {
                    delay(if (deadline > 0) 32L else 24L)
                }
                latestFrame
            } finally {
                sleep()
            }
        } ?: latestFrame

        // 无论成功与否都断开镜像，别让它在后台空转
        sleep()
        awaitingFrame = false

        val bitmap = frame ?: error("No screen frame available")
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("No screen frame available")
    }

    fun stop() {
        cleanupDisplay()
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        mediaProjection?.stop()
        mediaProjection = null
        captureThread.quitSafely()
    }

    /** 接回 surface，让 VirtualDisplay 开始出帧。 */
    private fun wake() {
        val display = virtualDisplay ?: return
        val surface = imageReader?.surface ?: return
        awaitingFrame = true
        runCatching { display.surface = surface }
    }

    /** 断开 surface：VD 保留，但不再往 ImageReader 写帧，空闲时零开销。 */
    private fun sleep() {
        val display = virtualDisplay ?: return
        runCatching { display.surface = null }
    }

    private fun ensureDisplay(): ImageReader? {
        imageReader?.let { return it }
        val projection = mediaProjection ?: return null

        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        // maxImages 只要 2：够「最新一帧 + 一帧缓冲」，多出来的只会占内存
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ available ->
            if (!awaitingFrame) {
                // 不是我们要的帧，直接丢掉，别解码
                available.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.uptimeMillis()
            image.use {
                // 同一批帧只处理一次，省掉重复解码
                if (now - latestFrameAt < 300L && latestFrame != null) return@use
                runCatching {
                    val bitmap = decode(it)
                    latestFrame?.recycle()
                    latestFrame = bitmap
                    latestFrameAt = now
                }
            }
        }, captureHandler)

        // 先建 VD 但不接 surface —— 建完就是「暂停」状态
        virtualDisplay = projection.createVirtualDisplay(
            "quiz-assist-capture",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            captureHandler,
        )
        return reader
    }

    /**
     * Image → Bitmap。
     *
     * 常见机型 `rowStride == width * pixelStride`（4 字节对齐），这时可以直接用
     * 目标尺寸的 Bitmap 接收，省掉「先建 padding 位图、再裁一次」的全屏分配。
     */
    private fun decode(image: android.media.Image): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val expectedRow = captureWidth * pixelStride
        if (rowStride == expectedRow) {
            val bitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        // 有行填充：先按物理行宽接收，再裁掉右侧填充
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight)
        padded.recycle()
        return cropped
    }

    private fun cleanupDisplay() {
        runCatching { virtualDisplay?.surface = null }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        latestFrame?.recycle()
        latestFrame = null
        latestFrameAt = 0L
        awaitingFrame = false
    }
}
