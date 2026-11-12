package com.huangguo.tv

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
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
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
        // 確保硬體加速維持開啟，並設為透明底色（避免在內容載入前強制純黑遮蔽渲染）
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setBackgroundColor(Color.TRANSPARENT)

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
        settings.javaScriptCanOpenWindowsAutomatically = true
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
                Log.d(TAG, "開始載入頁面: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingBar.visibility = View.GONE
                Log.i(TAG, "頁面載入完成: $url")

                // 頁面載入完成後重新確保焦點聚焦於 WebView
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                webView.requestFocus()

                // 注入安全修正後的 CSS/JS、精準年齡確認點擊與公告關閉邏輯
                injectCustomStylesAndScripts()
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 避免舊電視因系統根憑證過期或資源憑證異常導致整頁中斷黑屏
                Log.w(
                    TAG,
                    "SSL 憑證異常已放行: url=${error?.url}, primaryError=${error?.primaryError}"
                )
                handler?.proceed()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(
                        TAG,
                        "WebView 資源載入錯誤: url=${request?.url}, isMainFrame=${request?.isForMainFrame}, errorCode=${error?.errorCode}, description=${error?.description}"
                    )
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(
                    TAG,
                    "WebView 載入錯誤 (Legacy): url=$failingUrl, errorCode=$errorCode, description=$description"
                )
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(
                        TAG,
                        "WebView HTTP 錯誤: url=${request?.url}, isMainFrame=${request?.isForMainFrame}, statusCode=${errorResponse?.statusCode}, reason=${errorResponse?.reasonPhrase}"
                    )
                }
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

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let { msg ->
                    val logText = "[WebConsole][${msg.sourceId()}:${msg.lineNumber()}] ${msg.message()}"
                    when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, logText)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, logText)
                        else -> Log.d(TAG, logText)
                    }
                }
                return super.onConsoleMessage(consoleMessage)
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
     * 注入安全修正後的 CSS 與 JS 腳本
     * 1. 移除過激全域隱藏語法，不強制覆蓋 body/app 結構與全域 van-overlay
     * 2. 針對「年滿18 / 進入網站」與「公告關閉」精準點擊確認按鈕，殘留時僅移除彈窗本身
     * 3. 保留電視遙控器焦點外框與影片播放控制輔助函數
     */
    private fun injectCustomStylesAndScripts() {
        // 1. 注入安全 CSS（使用 Base64 編碼避免字元跳脫問題）
        val safeCss = """
            video {
                display: block !important;
                margin: 0 auto !important;
                max-height: 100vh !important;
                object-fit: contain !important;
            }
            ::-webkit-scrollbar {
                display: none !important;
                width: 0px !important;
            }
            .open-app-banner, .footer-app, .app-download-bar, a[href*='.apk'] {
                display: none !important;
            }
            :focus, :focus-visible, a:focus, button:focus, [tabindex]:focus, [role='button']:focus, .van-button:focus {
                outline: 3.5px solid #FF1744 !important;
                outline-offset: 2px !important;
                box-shadow: 0 0 16px #FF1744 !important;
            }
        """.trimIndent()

        val encodedCss = Base64.encodeToString(safeCss.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cssJs = """
            (function() {
                var head = document.head || document.documentElement;
                if (!head) return;
                var styleId = '__hg_injected_style';
                var styleEl = document.getElementById(styleId);
                if (!styleEl) {
                    styleEl = document.createElement('style');
                    styleEl.id = styleId;
                    styleEl.type = 'text/css';
                    head.appendChild(styleEl);
                }
                styleEl.textContent = window.atob('$encodedCss');
            })();
        """.trimIndent()
        webView.evaluateJavascript(cssJs, null)

        // 2. 注入精準定向年齡確認、公告關閉與 TV 輔助 JS
        val safeJs = """
            (function() {
                try {
                    var keys = ['isAdult', 'adult', 'is_adult', 'age_verify', 'has_confirmed_age', 'age_confirmed'];
                    keys.forEach(function(k) {
                        localStorage.setItem(k, 'true');
                        sessionStorage.setItem(k, 'true');
                    });
                } catch (e) {}

                var ageKeywords = ['年滿18', '年满18', '進入網站', '进入网站'];
                var noticeKeywords = ['我知道了', '關閉公告', '关闭公告', '關閉', '关闭'];

                function matchesSelector(el, selector) {
                    if (!el || el.nodeType !== 1) return false;
                    var fn = el.matches || el.webkitMatchesSelector;
                    return fn ? fn.call(el, selector) : false;
                }

                function findClosest(el, selector) {
                    if (!el) return null;
                    if (el.closest) return el.closest(selector);
                    var cur = el;
                    while (cur && cur.nodeType === 1) {
                        if (matchesSelector(cur, selector)) return cur;
                        cur = cur.parentElement;
                    }
                    return null;
                }

                function isProtectedRoot(el) {
                    if (!el || !el.tagName) return true;
                    var tag = el.tagName.toUpperCase();
                    if (tag === 'HTML' || tag === 'BODY' || tag === 'MAIN') return true;
                    if (el.id === 'app' || el.id === 'root') return true;
                    return false;
                }

                function isVisible(el) {
                    if (!el) return false;
                    var style = window.getComputedStyle(el);
                    return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
                }

                function clickElement(el) {
                    try {
                        el.click();
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                    } catch (e) {}
                }

                function removeResidualPopup(popupEl) {
                    if (!popupEl || isProtectedRoot(popupEl)) return;
                    if (popupEl.querySelector('#app, #root, main')) return;
                    if (isVisible(popupEl)) {
                        popupEl.remove();
                    }
                    // 僅當畫面上已無可見的 van-dialog / van-popup 時，才精準移除殘留的遮罩層元素本身
                    var remainingPopups = document.querySelectorAll('.van-dialog, .van-popup, [role="dialog"]');
                    var hasVisiblePopup = false;
                    for (var i = 0; i < remainingPopups.length; i++) {
                        if (!isProtectedRoot(remainingPopups[i]) && isVisible(remainingPopups[i])) {
                            hasVisiblePopup = true;
                            break;
                        }
                    }
                    if (!hasVisiblePopup) {
                        var overlays = document.querySelectorAll('body > .van-overlay, body > .modal-mask');
                        for (var j = 0; j < overlays.length; j++) {
                            if (!isProtectedRoot(overlays[j]) && !overlays[j].querySelector('#app, #root')) {
                                overlays[j].remove();
                            }
                        }
                    }
                }

                function handleConfirmAndNotice() {
                    var acted = false;
                    var candidates = document.querySelectorAll(
                        'button, a, [role="button"], .van-button, .van-dialog__confirm, .van-popup__close-icon, [class*="close-btn"], [class*="close-icon"], span, div'
                    );

                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        if (isProtectedRoot(el) || !isVisible(el)) continue;

                        var tag = el.tagName.toUpperCase();
                        // div / span 僅比對葉節點，避免誤中整個頁面父容器
                        if ((tag === 'DIV' || tag === 'SPAN') && el.children.length > 1) continue;

                        var isIconClose = matchesSelector(el, '.van-popup__close-icon, [class*="close-btn"], [class*="close-icon"]');
                        var txt = (el.innerText || el.textContent || '').trim();
                        if (!isIconClose && (!txt || txt.length > 24)) continue;

                        var matchedAge = false;
                        for (var a = 0; a < ageKeywords.length; a++) {
                            if (txt.indexOf(ageKeywords[a]) !== -1) {
                                matchedAge = true;
                                break;
                            }
                        }

                        var popupBox = findClosest(el, '.van-dialog, .van-popup, [role="dialog"], .age-modal, .notice-modal');
                        var matchedNotice = false;
                        if (popupBox && !isProtectedRoot(popupBox)) {
                            if (isIconClose) {
                                matchedNotice = true;
                            } else {
                                for (var n = 0; n < noticeKeywords.length; n++) {
                                    if (txt.indexOf(noticeKeywords[n]) !== -1) {
                                        matchedNotice = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (matchedAge || matchedNotice) {
                            clickElement(el);
                            acted = true;
                            if (popupBox) {
                                (function(targetPopup) {
                                    setTimeout(function() {
                                        removeResidualPopup(targetPopup);
                                    }, 350);
                                })(popupBox);
                            }
                        }
                    }
                    return acted;
                }

                handleConfirmAndNotice();
                var attempts = 0;
                var timer = setInterval(function() {
                    attempts++;
                    handleConfirmAndNotice();
                    if (attempts >= 15) clearInterval(timer);
                }, 400);

                if (window.MutationObserver && !window.__hg_popup_observer) {
                    var obsTarget = document.body || document.documentElement;
                    if (obsTarget) {
                        window.__hg_popup_observer = new MutationObserver(function() {
                            handleConfirmAndNotice();
                        });
                        window.__hg_popup_observer.observe(obsTarget, { childList: true, subtree: true });
                    }
                }

                function makeFocusable() {
                    var els = document.querySelectorAll('a, button, [role="button"], .van-button, .van-tab');
                    for (var i = 0; i < els.length; i++) {
                        if (!els[i].hasAttribute('tabindex')) {
                            els[i].setAttribute('tabindex', '0');
                        }
                    }
                }
                makeFocusable();

                window.__hg_findVideo = function() {
                    var videos = document.getElementsByTagName('video');
                    if (videos && videos.length > 0) {
                        for (var i = 0; i < videos.length; i++) {
                            if (videos[i].readyState > 0 || !videos[i].paused || videos[i].currentTime > 0) {
                                return videos[i];
                            }
                        }
                        return videos[0];
                    }
                    return null;
                };
                window.__hg_togglePlay = function() {
                    var v = window.__hg_findVideo();
                    if (v) {
                        if (v.paused) v.play(); else v.pause();
                        return !v.paused;
                    }
                    return false;
                };
                window.__hg_seek = function(deltaSeconds) {
                    var v = window.__hg_findVideo();
                    if (v && v.duration) {
                        v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + deltaSeconds));
                    }
                };
                window.__hg_scrollBy = function(ratio) {
                    window.scrollBy({ top: window.innerHeight * ratio, behavior: 'smooth' });
                };

                console.log('HuangGuoTV Safe Helper JS injected, url=' + location.href + ', bodyChildren=' + (document.body ? document.body.children.length : -1));
            })();
        """.trimIndent()
        webView.evaluateJavascript(safeJs, null)
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