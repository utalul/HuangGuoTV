package com.huangguo.tv

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.huangguo.tv.config.ConfigManager
import com.huangguo.tv.update.UpdateManager
import com.huangguo.tv.view.CursorLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Main"
        private const val DOUBLE_CLICK_INTERVAL = 400L // 雙擊返回鍵時間間隔
        private const val EXIT_INTERVAL = 2000L
    }

    private lateinit var cursorContainer: CursorLayout
    private lateinit var webView: WebView
    private lateinit var loadingBar: ProgressBar
    private lateinit var tvModeHint: TextView

    private lateinit var configManager: ConfigManager
    private lateinit var updateManager: UpdateManager

    private val handler = Handler(Looper.getMainLooper())
    private var lastBackPressTime = 0L
    private var exitToastTime = 0L

    // 全螢幕自訂視圖（當影片要求系統全螢幕時備用）
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // 長按監聽
    private var isCenterLongPressTriggered = false
    private val centerLongPressRunnable = Runnable {
        isCenterLongPressTriggered = true
        showTvControlMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持全螢幕與螢幕常亮
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        configManager = ConfigManager.getInstance(this)
        updateManager = UpdateManager(this)

        initViews()
        setupWebView()
        loadInitialPage()

        // 確保啟動時 WebView 立即獲取焦點
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        // 啟動時僅背景非同步拉取最新雲端防失聯配置，不再主動跳出更新彈窗
        syncCloudConfigOnly()
    }

    private fun initViews() {
        cursorContainer = findViewById(R.id.cursorContainer)
        webView = findViewById(R.id.webView)
        loadingBar = findViewById(R.id.loadingBar)
        tvModeHint = findViewById(R.id.tvModeHint)

        cursorContainer.onCursorModeChangeListener = { isEnabled ->
            showModeHint(if (isEnabled) getString(R.string.msg_cursor_on) else getString(R.string.msg_cursor_off))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // 啟用硬體加速並設定純黑底色
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setBackgroundColor(0xFF000000.toInt())

        // 確保電視端焦點能進入 WebView
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // 允許影片自動播放，無需使用者手勢觸發
        settings.mediaPlaybackRequiresUserGesture = false

        // 支援混合內容 (HTTP/HTTPS 同時存在)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 快取策略
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 設定 User-Agent
        settings.userAgentString = configManager.getEffectiveUserAgent()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlIntercept(url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlIntercept(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadingBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingBar.visibility = View.GONE

                // 頁面載入完成後重新確保焦點聚焦於 WebView
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                webView.requestFocus()

                // 自動注入自訂 CSS/JS、成年驗證自動穿透與可焦點化處理
                injectCustomStylesAndScripts()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    loadingBar.visibility = View.VISIBLE
                    loadingBar.progress = newProgress
                } else {
                    loadingBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customViewCallback = callback
                cursorContainer.addView(view, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideCustomVideoView()
            }
        }
    }

    /**
     * 退出全螢幕自訂影片視圖
     */
    private fun hideCustomVideoView() {
        val view = customVideoView ?: return
        cursorContainer.removeView(view)
        customVideoView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        webView.requestFocus()
    }

    /**
     * 攔截特殊 scheme、第三方跳轉及 APK 自動下載
     */
    private fun handleUrlIntercept(url: String): Boolean {
        val lower = url.lowercase()

        // 1. 攔截結尾為 .apk 的下載連結，防止網頁強制下載手機版 APK
        if (lower.endsWith(".apk") || lower.contains(".apk?") || lower.contains("/download/apk")) {
            Log.w(TAG, "已安全攔截手機版 APK 自動下載請求: $url")
            Toast.makeText(this, "已攔截手機版下載，維持電視端運行", Toast.LENGTH_SHORT).show()
            return true
        }

        // 2. 攔截所有非 http/https 自訂協議 (如 intent://, market://, alipays://, weixin://)
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") {
            Log.w(TAG, "已攔截非網頁自訂協定跳轉: $url")
            return true
        }

        return false
    }

    /**
     * 載入首頁
     */
    private fun loadInitialPage() {
        val targetUrl = configManager.getCurrentTargetUrl()
        Log.i(TAG, "正在載入主網址: $targetUrl")
        webView.loadUrl(targetUrl)
    }

    /**
     * 注入自訂 CSS 與 JS 腳本
     * 包含：
     * 1. 背景純黑、直立播放器置中、隱藏「下載 App」浮動橫幅
     * 2. 自動穿透「年滿 18 歲」年齡確認彈窗並寫入 localStorage
     * 3. 網頁可點擊元素支援 :focus 鮮紅高亮框與 tabindex 自動補全
     */
    private fun injectCustomStylesAndScripts() {
        val config = configManager.getConfig()

        // 1. 注入 CSS (使用 Base64 編碼避免字元跳脫問題)
        val css = config.injectCss
        if (css.isNotEmpty()) {
            val encodedCss = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
            val cssJs = """
                (function() {
                    var styleId = '__hg_injected_style';
                    var styleEl = document.getElementById(styleId);
                    if (!styleEl) {
                        styleEl = document.createElement('style');
                        styleEl.id = styleId;
                        styleEl.type = 'text/css';
                        document.head.appendChild(styleEl);
                    }
                    styleEl.innerHTML = window.atob('$encodedCss');
                })();
            """.trimIndent()
            webView.evaluateJavascript(cssJs, null)
        }

        // 2. 注入 JS 輔助函數與穿透腳本
        val js = config.injectJs
        if (js.isNotEmpty()) {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 啟動時僅同步雲端配置熱更新，不在啟動時彈出更新視窗
     */
    private fun syncCloudConfigOnly() {
        lifecycleScope.launch {
            val updated = configManager.fetchRemoteConfig()
            if (updated) {
                // 如果目前網址失效或配置更新，套用新設定
                val newConfig = configManager.getConfig()
                val currentUrl = webView.url
                if (currentUrl == null || !currentUrl.startsWith("http")) {
                    webView.loadUrl(newConfig.primaryUrl)
                }
            }
        }
    }

    /**
     * 電視遙控器按鍵處理核心邏輯
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 1. 遙控器選單鍵 (MENU)：單擊即刻切換/呼出紅色虛擬滑鼠游標
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (action == KeyEvent.ACTION_DOWN) {
                cursorContainer.toggleCursorMode()
            }
            return true
        }

        // 2. 如果虛擬滑鼠模式開啟，優先交給 CursorLayout 處理 (平滑移動與座標點擊注入)
        if (cursorContainer.isCursorMode()) {
            if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_MENU) {
                val handled = cursorContainer.dispatchCursorKeyEvent(event, webView)
                if (handled) return true
            }
        }

        // 3. 處理長按「確認鍵」呼出 TV 控制選單
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isCenterLongPressTriggered = false
                    handler.postDelayed(centerLongPressRunnable, 1000L) // 長按 1 秒呼出
                }
            } else if (action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(centerLongPressRunnable)
                if (isCenterLongPressTriggered) {
                    isCenterLongPressTriggered = false
                    return true
                }
            }
        }

        // 4. 返回鍵邏輯
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < DOUBLE_CLICK_INTERVAL) {
                    // 雙擊成功，切換虛擬滑鼠模式
                    cursorContainer.toggleCursorMode()
                    lastBackPressTime = 0L
                    return true
                }
                lastBackPressTime = now

                // 如果處於自訂全螢幕影片視圖中，退出全螢幕
                if (customVideoView != null) {
                    hideCustomVideoView()
                    return true
                }

                // 如果處於虛擬滑鼠模式，單擊優先關閉虛擬滑鼠
                if (cursorContainer.isCursorMode()) {
                    cursorContainer.setCursorMode(false)
                    return true
                }

                // 如果 WebView 可後退，優先在網頁內後退
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }

                // 連續單擊兩次退出 App
                if (now - exitToastTime < EXIT_INTERVAL) {
                    finish()
                } else {
                    exitToastTime = now
                    Toast.makeText(this, getString(R.string.msg_press_again_exit), Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }

        // 5. 非虛擬滑鼠模式下的 D-Pad 鍵盤導航：
        // 優先讓事件直接派發給 WebView，使網頁內按鈕能獲取焦點並顯示高亮外框；
        // 若 WebView 未消費或聚焦在 video，由 webView 內部注入的 keydown 處理滾動或快退快進
        if (webView.dispatchKeyEvent(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * 呼出 Android TV 專用控制面板
     */
    private fun showTvControlMenu() {
        val dialog = Dialog(this, R.style.Dialog_HuangGuoTV)
        dialog.setContentView(R.layout.dialog_tv_menu)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val btnToggleCursor = dialog.findViewById<Button>(R.id.btnToggleCursor)
        val btnSwitchLine = dialog.findViewById<Button>(R.id.btnSwitchLine)
        val btnReloadConfig = dialog.findViewById<Button>(R.id.btnReloadConfig)
        val btnSwitchUa = dialog.findViewById<Button>(R.id.btnSwitchUa)
        val btnClearCache = dialog.findViewById<Button>(R.id.btnClearCache)
        val btnCheckUpdate = dialog.findViewById<Button>(R.id.btnCheckUpdate)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseMenu)

        // 依據目前狀態更新按鈕文字
        val cursorState = if (cursorContainer.isCursorMode()) "【已開啟】" else "【已關閉】"
        btnToggleCursor.text = "虛擬滑鼠：$cursorState (按確認鍵切換)"

        val isDesktop = configManager.isDesktopUa()
        btnSwitchUa.text = String.format(getString(R.string.menu_switch_ua), if (isDesktop) "桌面版" else "手機版")

        // 預設第一項按鈕取得電視焦點
        btnToggleCursor.requestFocus()

        // 1. 切換虛擬滑鼠
        btnToggleCursor.setOnClickListener {
            val enabled = cursorContainer.toggleCursorMode()
            btnToggleCursor.text = "虛擬滑鼠：" + (if (enabled) "【已開啟】" else "【已關閉】")
            dialog.dismiss()
        }

        // 2. 切換備用線路
        btnSwitchLine.setOnClickListener {
            dialog.dismiss()
            showSwitchLineDialog()
        }

        // 3. 強制重載雲端熱更新配置
        btnReloadConfig.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "正在拉取最新雲端配置...", Toast.LENGTH_SHORT).show()
                val ok = configManager.fetchRemoteConfig()
                if (ok) {
                    val url = configManager.getCurrentTargetUrl()
                    webView.loadUrl(url)
                    Toast.makeText(this@MainActivity, getString(R.string.msg_config_updated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "拉取失敗，使用目前快取配置", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 4. 切換 User-Agent
        btnSwitchUa.setOnClickListener {
            val targetDesktop = !configManager.isDesktopUa()
            configManager.setDesktopUa(targetDesktop)
            webView.settings.userAgentString = configManager.getEffectiveUserAgent()
            webView.reload()
            dialog.dismiss()
            showModeHint(if (targetDesktop) "已切換至【桌面版】排版" else "已切換至【手機版】排版")
        }

        // 5. 清除快取
        btnClearCache.setOnClickListener {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            Toast.makeText(this, getString(R.string.msg_cache_cleared), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            webView.reload()
        }

        // 6. 檢查版本更新（此處為手動檢查）
        btnCheckUpdate.setOnClickListener {
            dialog.dismiss()
            updateManager.checkForUpdates(isManual = true)
        }

        // 7. 關閉
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 切換線路選單
     */
    private fun showSwitchLineDialog() {
        val config = configManager.getConfig()
        val urls = config.backupUrls
        val current = configManager.getCurrentTargetUrl()

        val items = urls.toTypedArray()
        val checkedItem = urls.indexOf(current).let { if (it >= 0) it else 0 }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_HuangGuoTV)
        builder.setTitle("選擇播放線路")
        builder.setSingleChoiceItems(items, checkedItem) { d, which ->
            val selectedUrl = items[which]
            configManager.setCurrentTargetUrl(selectedUrl)
            webView.loadUrl(selectedUrl)
            d.dismiss()
            Toast.makeText(this, "已切換至線路: $selectedUrl", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("取消", null)
        val alert = builder.create()
        alert.show()
    }

    private fun showModeHint(message: String) {
        tvModeHint.text = message
        tvModeHint.visibility = View.VISIBLE
        tvModeHint.alpha = 1f
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 2500L)
    }

    private val hideHintRunnable = Runnable {
        tvModeHint.animate().alpha(0f).setDuration(400).withEndAction {
            tvModeHint.visibility = View.GONE
        }.start()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
