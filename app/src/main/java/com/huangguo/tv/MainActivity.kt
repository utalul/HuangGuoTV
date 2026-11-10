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
import android.webkit.WebResourceResponse
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
        private const val DOUBLE_CLICK_INTERVAL = 400L // 雙擊判斷毫秒數
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

        // 啟動時背景異步拉取最新雲端防失聯配置，並靜默檢查更新
        syncCloudConfigAndUpdates()
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
                if (customVideoView == null) return
                cursorContainer.removeView(customVideoView)
                customVideoView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }
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
     * - 背景純黑
     * - 直立短劇播放器居中
     * - 隱藏下載 App 浮動橫幅
     * - 注入輔助控制函數
     */
    private fun injectCustomStylesAndScripts() {
        val config = configManager.getConfig()

        // 1. 注入 CSS (使用 Base64 編碼避免引號跳脫問題)
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

        // 2. 注入 JS 輔助函數
        val js = config.injectJs
        if (js.isNotEmpty()) {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * 背景非同步拉取最新遠端熱更新配置，若更新則在必要時平滑重載
     */
    private fun syncCloudConfigAndUpdates() {
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

            // 檢查 App 自身 OTA 更新
            updateManager.checkForUpdates(isManual = false)
        }
    }

    /**
     * 電視遙控器按鍵處理核心邏輯
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 1. 如果虛擬滑鼠模式開啟，優先交給 CursorLayout 處理 (上下左右與點擊)
        if (cursorContainer.isCursorMode()) {
            if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_MENU) {
                val handled = cursorContainer.dispatchCursorKeyEvent(event, webView)
                if (handled) return true
            }
        }

        // 2. 處理長按「確認鍵」呼出 TV 控制選單
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isCenterLongPressTriggered = false
                    handler.postDelayed(centerLongPressRunnable, 1000L) // 長按 1 秒呼出
                }
            } else if (action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(centerLongPressRunnable)
                if (isCenterLongPressTriggered) {
                    return true
                }
            }
        }

        // 3. 一般情況下的按鍵分發
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                // 遙控器選單鍵：單擊切換虛擬滑鼠模式
                KeyEvent.KEYCODE_MENU -> {
                    val isCursor = cursorContainer.toggleCursorMode()
                    return true
                }

                // 遙控器返回鍵：雙擊切換虛擬滑鼠，單擊後退或退出
                KeyEvent.KEYCODE_BACK -> {
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
                        customViewCallback?.onHideCustomView()
                        return true
                    }

                    // 如果處於虛擬滑鼠模式，單擊先退出虛擬滑鼠
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
                    return true
                }

                // 非游標模式下的【播放快捷模式】
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!cursorContainer.isCursorMode()) {
                        // 向上滾動或滑動切換上一集
                        webView.evaluateJavascript("window.__hg_scrollBy ? window.__hg_scrollBy(-0.8) : window.scrollBy({top: -window.innerHeight * 0.8, behavior: 'smooth'});", null)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!cursorContainer.isCursorMode()) {
                        // 向下滾動或滑動切換下一集
                        webView.evaluateJavascript("window.__hg_scrollBy ? window.__hg_scrollBy(0.8) : window.scrollBy({top: window.innerHeight * 0.8, behavior: 'smooth'});", null)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!cursorContainer.isCursorMode()) {
                        // 快退 10 秒
                        webView.evaluateJavascript("if (window.__hg_seek) { window.__hg_seek(-10); }", null)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!cursorContainer.isCursorMode()) {
                        // 快進 10 秒
                        webView.evaluateJavascript("if (window.__hg_seek) { window.__hg_seek(10); }", null)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!cursorContainer.isCursorMode() && !isCenterLongPressTriggered) {
                        // 自動尋找 video 標籤切換 play() / pause()
                        webView.evaluateJavascript("if (window.__hg_togglePlay) { window.__hg_togglePlay(); }", null)
                        return true
                    }
                }
            }
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

        // 6. 檢查版本更新
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
