package com.huangguo.tv.view

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.huangguo.tv.R
import kotlin.math.max
import kotlin.math.min

/**
 * 專為 Android TV 設計的虛擬滑鼠容器
 * 支援平滑加速移動、座標邊界限制、高層級渲染以及真實 MotionEvent (Down + Up) 點擊注入
 */
class CursorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cursorView: ImageView? = null
    private var isCursorEnabled: Boolean = false

    // 游標當前座標（中心點）
    private val cursorPosition = PointF(0f, 0f)

    // 移動速度與方向狀態
    private var moveDx = 0f
    private var moveDy = 0f
    private var currentSpeed = BASE_SPEED
    private val handler = Handler(Looper.getMainLooper())
    private var isMoveLoopRunning = false

    // 點擊手勢序列的 downTime 追蹤
    private var lastDownTime = 0L

    // 監聽模式變化
    var onCursorModeChangeListener: ((Boolean) -> Unit)? = null

    companion object {
        private const val BASE_SPEED = 12f       // 初始移動速度 (px/frame)
        private const val MAX_SPEED = 36f        // 最大加速速度 (px/frame)
        private const val ACCELERATION = 1.25f   // 持續按壓時的加速度倍率
        private const val FRAME_INTERVAL_MS = 16L // 約 60 FPS
    }

    private val moveRunnable = object : Runnable {
        override fun run() {
            if (!isCursorEnabled || (moveDx == 0f && moveDy == 0f)) {
                isMoveLoopRunning = false
                currentSpeed = BASE_SPEED
                return
            }

            // 平滑加速
            if (currentSpeed < MAX_SPEED) {
                currentSpeed = min(MAX_SPEED, currentSpeed * ACCELERATION)
            }

            val newX = cursorPosition.x + (moveDx * currentSpeed)
            val newY = cursorPosition.y + (moveDy * currentSpeed)

            setCursorPosition(newX, newY)

            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        cursorView = findViewById(R.id.cursorView)
        setupCursorViewElevation()
    }

    private fun setupCursorViewElevation() {
        cursorView?.let { view ->
            view.elevation = 9999f
            view.translationZ = 9999f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorPosition.x == 0f && cursorPosition.y == 0f && w > 0 && h > 0) {
            // 初始化在畫面中央
            setCursorPosition(w / 2f, h / 2f)
        }
    }

    /**
     * 開啟或關閉虛擬滑鼠模式
     */
    fun setCursorMode(enabled: Boolean) {
        if (this.isCursorEnabled == enabled) return
        this.isCursorEnabled = enabled

        cursorView?.let { view ->
            if (enabled) {
                setupCursorViewElevation()
                view.visibility = View.VISIBLE
                view.bringToFront()
                bringChildToFront(view)
                // 如果第一次開啟，置中
                if (cursorPosition.x == 0f && cursorPosition.y == 0f && width > 0 && height > 0) {
                    setCursorPosition(width / 2f, height / 2f)
                } else {
                    updateCursorView()
                }
                invalidate()
            } else {
                view.visibility = View.GONE
                stopMoving()
            }
        }
        onCursorModeChangeListener?.invoke(enabled)
    }

    fun isCursorMode(): Boolean = isCursorEnabled

    fun toggleCursorMode(): Boolean {
        setCursorMode(!isCursorEnabled)
        return isCursorEnabled
    }

    /**
     * 更新游標絕對座標並限制在螢幕內
     */
    private fun setCursorPosition(x: Float, y: Float) {
        val radius = ((cursorView?.width ?: 38) / 2f).coerceAtLeast(10f)
        val maxW = if (width > 0) width.toFloat() else 1920f
        val maxH = if (height > 0) height.toFloat() else 1080f

        cursorPosition.x = max(radius, min(maxW - radius, x))
        cursorPosition.y = max(radius, min(maxH - radius, y))
        updateCursorView()
    }

    private fun updateCursorView() {
        cursorView?.let { view ->
            val halfW = (view.width / 2f).coerceAtLeast(19f)
            val halfH = (view.height / 2f).coerceAtLeast(19f)
            view.x = cursorPosition.x - halfW
            view.y = cursorPosition.y - halfH
            view.bringToFront()
        }
    }

    /**
     * 開始連續平滑移動
     */
    private fun startMoving(dx: Float, dy: Float) {
        moveDx = dx
        moveDy = dy
        if (!isMoveLoopRunning) {
            isMoveLoopRunning = true
            currentSpeed = BASE_SPEED
            handler.post(moveRunnable)
        }
    }

    /**
     * 停止移動
     */
    private fun stopMoving() {
        moveDx = 0f
        moveDy = 0f
        isMoveLoopRunning = false
        currentSpeed = BASE_SPEED
        handler.removeCallbacks(moveRunnable)
    }

    /**
     * 處理虛擬滑鼠模式下的按鍵事件
     * @return true 代表該按鍵已被虛擬滑鼠消化，不再傳遞給底層視圖
     */
    fun dispatchCursorKeyEvent(event: KeyEvent, targetView: View): Boolean {
        if (!isCursorEnabled) return false

        val action = event.action
        val keyCode = event.keyCode

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    startMoving(0f, -1f)
                } else if (action == KeyEvent.ACTION_UP) {
                    if (moveDy < 0) stopMoving()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    startMoving(0f, 1f)
                } else if (action == KeyEvent.ACTION_UP) {
                    if (moveDy > 0) stopMoving()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    startMoving(-1f, 0f)
                } else if (action == KeyEvent.ACTION_UP) {
                    if (moveDx < 0) stopMoving()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    startMoving(1f, 0f)
                } else if (action == KeyEvent.ACTION_UP) {
                    if (moveDx > 0) stopMoving()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val now = SystemClock.uptimeMillis()
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        lastDownTime = now
                        cursorView?.animate()?.scaleX(0.75f)?.scaleY(0.75f)?.setDuration(60)?.start()
                        injectTouchEvent(targetView, MotionEvent.ACTION_DOWN, lastDownTime, now)
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    cursorView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(80)?.start()
                    val downTime = if (lastDownTime > 0) lastDownTime else now
                    injectTouchEvent(targetView, MotionEvent.ACTION_UP, downTime, now)
                    lastDownTime = 0L
                }
                return true
            }
        }

        return false
    }

    /**
     * 向目標視圖 (WebView) 注入精準且合法的 MotionEvent 觸控事件
     */
    private fun injectTouchEvent(targetView: View, action: Int, downTime: Long, eventTime: Long) {
        val targetLocation = IntArray(2)
        targetView.getLocationOnScreen(targetLocation)

        val cursorLocation = IntArray(2)
        cursorView?.getLocationOnScreen(cursorLocation)

        val cView = cursorView
        val cursorCenterX = if (cView != null && cView.width > 0) {
            cursorLocation[0] + cView.width / 2f
        } else {
            cursorPosition.x
        }

        val cursorCenterY = if (cView != null && cView.height > 0) {
            cursorLocation[1] + cView.height / 2f
        } else {
            cursorPosition.y
        }

        // 計算轉換為 targetView 內部的精確相對座標
        val localX = cursorCenterX - targetLocation[0]
        val localY = cursorCenterY - targetLocation[1]

        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = localX
            y = localY
            pressure = 1.0f
            size = 1.0f
        })

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            0,
            0
        )

        try {
            targetView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
