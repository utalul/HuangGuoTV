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
 * 支援平滑移動、加速度模擬、座標邊界限制以及真實 MotionEvent 點擊注入
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

    // 監聽模式變化
    var onCursorModeChangeListener: ((Boolean) -> Unit)? = null

    companion object {
        private const val BASE_SPEED = 10f       // 初始移動速度 (px/frame)
        private const val MAX_SPEED = 32f        // 最大加速速度 (px/frame)
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
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorPosition.x == 0f && cursorPosition.y == 0f) {
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
                view.visibility = View.VISIBLE
                view.bringToFront()
                // 如果第一次開啟，置中
                if (cursorPosition.x == 0f && cursorPosition.y == 0f) {
                    setCursorPosition(width / 2f, height / 2f)
                } else {
                    updateCursorView()
                }
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
        val radius = (cursorView?.width ?: 36) / 2f
        cursorPosition.x = max(radius, min(width - radius, x))
        cursorPosition.y = max(radius, min(height - radius, y))
        updateCursorView()
    }

    private fun updateCursorView() {
        cursorView?.let { view ->
            val halfW = view.width / 2f
            val halfH = view.height / 2f
            view.x = cursorPosition.x - halfW
            view.y = cursorPosition.y - halfH
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
                if (action == KeyEvent.ACTION_DOWN) {
                    // 按下時給予游標縮小反饋
                    cursorView?.animate()?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(80)?.start()
                    injectTouchEvent(targetView, MotionEvent.ACTION_DOWN)
                } else if (action == KeyEvent.ACTION_UP) {
                    // 釋放時恢復原尺寸
                    cursorView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(100)?.start()
                    injectTouchEvent(targetView, MotionEvent.ACTION_UP)
                }
                return true
            }
        }

        return false
    }

    /**
     * 向目標視圖 (WebView) 注入真實的觸控點擊事件
     */
    private fun injectTouchEvent(targetView: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val x = cursorPosition.x
        val y = cursorPosition.y

        val event = MotionEvent.obtain(
            now,
            now,
            action,
            x,
            y,
            0
        )
        try {
            targetView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
