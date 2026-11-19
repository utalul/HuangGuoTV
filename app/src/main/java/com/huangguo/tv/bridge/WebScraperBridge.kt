package com.huangguo.tv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.huangguo.tv.config.ConfigManager
import com.huangguo.tv.model.CategoryItem
import com.huangguo.tv.model.DramaItem
import com.huangguo.tv.model.EpisodeItem
import com.huangguo.tv.model.SubTabItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 隱藏式無頭網頁抓取與串流嗅探引擎
 * 針對 32 位元低記憶體 Android TV 進行深度優化：
 * 1. 延遲初始化 (Lazy Init)：按需建立 WebView，避免同時駐留多個實例引起 OOM。
 * 2. 15 秒超時保護 (Timeout)：避免因伺服器無響應或 DOM 異常導致 Loading 卡死。
 * 3. 離屏 Canvas 轉 Base64：克服加密圖床與防盜鏈，直接由 WebView 渲染像素導出。
 * 4. DOM <video> 直連網址嗅探與高頻輪巡點擊播放。
 */
class WebScraperBridge(private val context: Context) {

    companion object {
        private const val TAG = "WebScraperBridge"
        private const val INTERFACE_NAME = "AndroidBridge"
        private const val SCRAPE_TIMEOUT_MS = 15000L // 15 秒超時保護
        const val MSG_MISSING_WEBVIEW = "系統缺少 Android System WebView 元件，請安裝或更新 WebView"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val configManager = ConfigManager.getInstance(context)

    // 延遲初始化的無頭 WebView (只有在對應業務呼叫時才建立)
    private var homeWebView: WebView? = null
    private var detailWebView: WebView? = null

    // WebView 支援與缺失狀態
    var isWebViewSupported: Boolean = true
        private set

    // 首頁短劇、分類與橫向子分類抓取回呼
    private var onHomeScrapedListener: ((List<CategoryItem>, List<SubTabItem>, List<DramaItem>) -> Unit)? = null
    // 單一分類短劇抓取回呼
    private var onCategoryDramasListener: ((List<DramaItem>) -> Unit)? = null

    // 發布頁跳轉真實主站成功回呼
    private var onTargetUrlResolvedListener: ((String) -> Unit)? = null

    fun setOnTargetUrlResolvedListener(listener: ((String) -> Unit)?) {
        this.onTargetUrlResolvedListener = listener
    }

    // 詳情頁與串流嗅探回呼
    private var onEpisodesLoadedListener: ((List<EpisodeItem>) -> Unit)? = null
    private var onStreamFoundListener: ((String) -> Unit)? = null

    // 15 秒超時 Runnable 追蹤
    private var homeTimeoutRunnable: Runnable? = null
    private var detailTimeoutRunnable: Runnable? = null

    // 防止重複回報同一個串流網址
    private var currentSniffedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBaseWebView(): WebView? {
        return try {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mediaPlaybackRequiresUserGesture = false

                // 允許跨來源存取，協助 Canvas 讀取圖片導出 Base64
                try {
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true
                } catch (e: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                settings.userAgentString = configManager.getEffectiveUserAgent()
            }
        } catch (t: Throwable) {
            isWebViewSupported = false
            Log.e(TAG, "建立 WebView 實例失敗: ${t.message}", t)
            null
        }
    }

    /**
     * 延遲初始化首頁抓取 WebView
     */
    @SuppressLint("AddJavascriptInterface")
    private fun initHomeWebView(): Boolean {
        if (homeWebView != null) return true
        if (!isWebViewSupported) return false

        val wv = createBaseWebView() ?: return false
        wv.addJavascriptInterface(HomeScraperJsInterface(), INTERFACE_NAME)
        wv.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "無頭頁面載入完成: $url，注入資料抓取腳本")

                // 若從 .pages.dev 防封發布頁跳轉到了真實官方主站，自動同步新地址
                if (url != null && !url.contains("pages.dev") && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        val uri = android.net.Uri.parse(url)
                        val resolvedHost = "${uri.scheme}://${uri.host}"
                        val currentTarget = configManager.getCurrentTargetUrl()
                        if (currentTarget.contains("pages.dev") && resolvedHost.isNotEmpty() && resolvedHost != currentTarget) {
                            Log.i(TAG, "成功由發布頁跳轉至真實目標主站: $resolvedHost")
                            configManager.setCurrentTargetUrl(resolvedHost)
                            mainHandler.post {
                                onTargetUrlResolvedListener?.invoke(resolvedHost)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "解析發布頁跳轉網址異常: ${e.message}")
                    }
                }

                injectDataScrapingScript(wv)
            }
        }
        homeWebView = wv
        return true
    }

    /**
     * 延遲初始化詳情與串流嗅探 WebView
     */
    @SuppressLint("AddJavascriptInterface")
    private fun initDetailWebView(): Boolean {
        if (detailWebView != null) return true
        if (!isWebViewSupported) return false

        val wv = createBaseWebView() ?: return false
        wv.addJavascriptInterface(DetailSnifferJsInterface(), INTERFACE_NAME)
        wv.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                checkAndNotifyStreamUrl(reqUrl)
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (url != null) {
                    checkAndNotifyStreamUrl(url)
                }
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "詳情無頭載入完成: $url，注入選集與播放器嗅探腳本")
                injectDetailAndSnifferScript(wv)
            }
        }
        detailWebView = wv
        return true
    }

    /**
     * 檢查網址是否為真實影音串流 (.m3u8 / .mp4 / .flv / hls / stream)，若是則回調給播放器並取消超時計時
     */
    private fun checkAndNotifyStreamUrl(url: String) {
        val lower = url.lowercase()
        val isMedia = lower.contains(".m3u8") || lower.contains(".mp4") ||
                      lower.contains("/hls/") || lower.contains("/playlist") ||
                      lower.contains(".flv") || lower.contains("/video/") ||
                      lower.contains("/stream/") || lower.contains("video/")
        if (isMedia && !lower.contains(".js") && !lower.contains(".css") && !lower.contains(".apk") && !lower.contains("google") && !lower.contains("analytics")) {
            if (currentSniffedUrl != url) {
                currentSniffedUrl = url
                Log.i(TAG, "成功嗅探到影片串流網址: $url")
                mainHandler.post {
                    cancelDetailTimeout()
                    pauseDetailVideo()
                    onStreamFoundListener?.invoke(url)
                }
            }
        }
    }

    /**
     * 暫停背景無頭 WebView 中的 video 播放
     */
    private fun pauseDetailVideo() {
        try {
            detailWebView?.evaluateJavascript(
                """
                (function() {
                    try {
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            videos[i].pause();
                            videos[i].muted = true;
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "暫停背景影片異常: ${t.message}")
        }
    }

    private fun startHomeTimeout() {
        cancelHomeTimeout()
        homeTimeoutRunnable = Runnable {
            Log.w(TAG, "首頁/分類抓取超時 (15s)")
            onHomeScrapedListener?.invoke(emptyList(), emptyList(), emptyList())
            onCategoryDramasListener?.invoke(emptyList())
            onHomeScrapedListener = null
            onCategoryDramasListener = null
        }.also {
            mainHandler.postDelayed(it, SCRAPE_TIMEOUT_MS)
        }
    }

    private fun cancelHomeTimeout() {
        homeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        homeTimeoutRunnable = null
    }

    private fun startDetailTimeout() {
        cancelDetailTimeout()
        detailTimeoutRunnable = Runnable {
            Log.w(TAG, "詳情/串流嗅探超時 (15s)")
            onStreamFoundListener?.invoke("")
            onEpisodesLoadedListener?.invoke(emptyList())
            onStreamFoundListener = null
            onEpisodesLoadedListener = null
        }.also {
            mainHandler.postDelayed(it, SCRAPE_TIMEOUT_MS)
        }
    }

    private fun cancelDetailTimeout() {
        detailTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        detailTimeoutRunnable = null
    }

    /**
     * 抓取首頁資料（包含左側邊欄分類、上方橫向子分類與首頁短劇清單，具備 15s 超時保護）
     */
    fun scrapeHomeDataAndCategories(
        url: String,
        callback: (List<CategoryItem>, List<SubTabItem>, List<DramaItem>) -> Unit
    ) {
        this.onCategoryDramasListener = null
        this.onHomeScrapedListener = callback

        mainHandler.post {
            if (!isWebViewSupported) {
                Log.e(TAG, MSG_MISSING_WEBVIEW)
                callback(emptyList(), emptyList(), emptyList())
                return@post
            }

            try {
                if (!initHomeWebView()) {
                    callback(emptyList(), emptyList(), emptyList())
                    return@post
                }
                startHomeTimeout()
                homeWebView?.stopLoading()
                homeWebView?.loadUrl(url) ?: run {
                    cancelHomeTimeout()
                    callback(emptyList(), emptyList(), emptyList())
                }
            } catch (t: Throwable) {
                cancelHomeTimeout()
                Log.e(TAG, "scrapeHomeDataAndCategories 異常: ${t.message}", t)
                callback(emptyList(), emptyList(), emptyList())
            }
        }
    }

    /**
     * 載入指定分類頁面並抓取該分類下的短劇清單（具備 15s 超時保護）
     */
    fun loadCategory(
        categoryUrl: String,
        callback: (List<DramaItem>) -> Unit
    ) {
        this.onHomeScrapedListener = null
        this.onCategoryDramasListener = callback

        mainHandler.post {
            if (!isWebViewSupported) {
                Log.e(TAG, MSG_MISSING_WEBVIEW)
                callback(emptyList())
                return@post
            }

            try {
                if (!initHomeWebView()) {
                    callback(emptyList())
                    return@post
                }
                startHomeTimeout()
                homeWebView?.loadUrl(categoryUrl) ?: run {
                    cancelHomeTimeout()
                    callback(emptyList())
                }
            } catch (t: Throwable) {
                cancelHomeTimeout()
                Log.e(TAG, "loadCategory 異常: ${t.message}", t)
                callback(emptyList())
            }
        }
    }

    /**
     * 透過無頭 WebView 點擊指定分類、標籤或關鍵字
     */
    fun clickNavOrTag(keyword: String, callback: (List<DramaItem>) -> Unit) {
        mainHandler.post {
            val wv = homeWebView ?: run {
                callback(emptyList())
                return@post
            }
            this.onCategoryDramasListener = callback
            startHomeTimeout()

            val escapedKeyword = keyword.replace("'", "\\'")
            val js = """
                (function() {
                    var kw = '$escapedKeyword';
                    var links = document.querySelectorAll('a, button, [role="button"], span, div');
                    for (var i = 0; i < links.length; i++) {
                        var el = links[i];
                        var txt = (el.innerText || el.textContent || '').trim();
                        if (txt === kw || (txt.indexOf(kw) !== -1 && txt.length <= kw.length + 3)) {
                            if (el.tagName.toUpperCase() === 'A' && el.href && el.href.indexOf('javascript') === -1) {
                                location.href = el.href;
                                return;
                            }
                            try {
                                el.click();
                                return;
                            } catch(e) {}
                        }
                    }
                    if (typeof doFullScrape === 'function') {
                        doFullScrape();
                    }
                })();
            """.trimIndent()
            try {
                wv.evaluateJavascript(js, null)
            } catch (t: Throwable) {
                Log.e(TAG, "clickNavOrTag 執行異常: ${t.message}")
                callback(emptyList())
            }
        }
    }

    /**
     * 開始嗅探詳情頁選集與影片串流（延遲初始化 + 15s 超時保護）
     */
    fun loadDramaDetail(
        detailUrl: String,
        onEpisodesLoaded: (List<EpisodeItem>) -> Unit,
        onStreamFound: (String) -> Unit
    ) {
        this.currentSniffedUrl = null
        this.onEpisodesLoadedListener = onEpisodesLoaded
        this.onStreamFoundListener = onStreamFound

        mainHandler.post {
            if (!isWebViewSupported) {
                Log.e(TAG, MSG_MISSING_WEBVIEW)
                onStreamFound("")
                onEpisodesLoaded(emptyList())
                return@post
            }

            try {
                if (!initDetailWebView()) {
                    onStreamFound("")
                    onEpisodesLoaded(emptyList())
                    return@post
                }
                Log.i(TAG, "無頭 WebView 開始載入詳情頁: $detailUrl")
                startDetailTimeout()
                detailWebView?.loadUrl(detailUrl)
            } catch (t: Throwable) {
                cancelDetailTimeout()
                Log.e(TAG, "loadDramaDetail 異常: ${t.message}", t)
                onStreamFound("")
                onEpisodesLoaded(emptyList())
            }
        }
    }

    /**
     * 切換選集嗅探指定集數連結（具備 15s 超時保護）
     */
    fun sniffEpisodeStream(episodeUrl: String, onStreamFound: (String) -> Unit) {
        this.currentSniffedUrl = null
        this.onStreamFoundListener = onStreamFound

        mainHandler.post {
            if (!isWebViewSupported) {
                Log.e(TAG, MSG_MISSING_WEBVIEW)
                onStreamFound("")
                return@post
            }

            try {
                if (!initDetailWebView()) {
                    onStreamFound("")
                    return@post
                }
                Log.i(TAG, "無頭 WebView 開始嗅探指定集數: $episodeUrl")
                startDetailTimeout()
                detailWebView?.loadUrl(episodeUrl)
            } catch (t: Throwable) {
                cancelDetailTimeout()
                Log.e(TAG, "sniffEpisodeStream 異常: ${t.message}", t)
                onStreamFound("")
            }
        }
    }

    /**
     * 注入首頁與分類抓取核心 JS
     * 支援：
     * 1. 4 次多段滾動 (每 450ms 滾動 window.innerHeight) 觸發懶載入完整 DOM
     * 2. 滾動後等待 800ms 確保圖片解碼，利用離屏 Canvas (240px) 轉 Base64 穿透防盜鏈
     * 3. 嚴格過濾播放量 (236.4w / 550.8w 等) 避免誤抓為劇名，將播放量轉為角標
     * 4. 向上與向下智慧解析真實 detailUrl，杜絕空值或 # 連結
     */
    private fun injectDataScrapingScript(wv: WebView) {
        val js = """
            (function() {
                try {
                    ['isAdult', 'adult', 'is_adult', 'age_verify', 'has_confirmed_age', 'age_confirmed'].forEach(function(k) {
                        localStorage.setItem(k, 'true');
                        sessionStorage.setItem(k, 'true');
                    });
                } catch(e) {}

                function autoClickAge() {
                    var btns = document.querySelectorAll('button, a, div, span, [role="button"], .van-button');
                    var kws = ['年滿18', '年满18', '進入網站', '进入网站', '我知道了'];
                    for (var i = 0; i < btns.length; i++) {
                        var txt = (btns[i].innerText || btns[i].textContent || '').trim();
                        if (!txt) continue;
                        for (var j = 0; j < kws.length; j++) {
                            if (txt.indexOf(kws[j]) !== -1) {
                                try { btns[i].click(); } catch(e) {}
                                break;
                            }
                        }
                    }
                }
                autoClickAge();

                // 若為 .pages.dev 防封發布頁，立即掃描跳轉至頁內最新官方主站地址
                if (location.hostname.indexOf('pages.dev') !== -1) {
                    var publishLinks = document.querySelectorAll('a, button, [role="button"], div');
                    var foundRealUrl = '';
                    for (var pl = 0; pl < publishLinks.length; pl++) {
                        var pEl = publishLinks[pl];
                        var pHref = (pEl.tagName.toUpperCase() === 'A') ? pEl.href : '';
                        if (pHref && pHref.indexOf('http') === 0 && pHref.indexOf('pages.dev') === -1) {
                            foundRealUrl = pHref;
                            break;
                        }
                    }
                    if (foundRealUrl) {
                        console.log('由發布頁解析到最新官網地址: ' + foundRealUrl);
                        location.href = foundRealUrl;
                        return;
                    }
                }

                // 1. 多段式自動滾動，確保懶載入的「精選推薦」、「最近上新」、「熱播短劇」板塊全數渲染
                var scrollCount = 0;
                var maxScrolls = 4;
                var scrollInterval = setInterval(function() {
                    scrollCount++;
                    autoClickAge();
                    window.scrollBy({ top: window.innerHeight || 900, behavior: 'instant' });
                    try {
                        window.dispatchEvent(new Event('scroll'));
                    } catch(e) {}
                    if (scrollCount >= maxScrolls) {
                        clearInterval(scrollInterval);
                        setTimeout(function() {
                            doFullScrape();
                        }, 800);
                    }
                }, 450);

                window.doFullScrape = doFullScrape;

                function doFullScrape() {
                    autoClickAge();

                    var nonDramaKeywords = [
                        '排行榜', '回家的路', '個人中心', '个人中心', '上眾享', '上众享', '賺佣金', '赚佣金',
                        'telegram', 'tg', 'gmail', '@', '域名', '賬號', '帳號', '账号', '帐号',
                        '關於我們', '关于我们', 'app', '下載', '下载', '登錄', '登录',
                        '客服', '聯繫', '联系', '版權', '版权', '廣告', '广告', '官方群',
                        '收藏', '歷史記錄', '历史记录', '加入我們', '加入我们', '立即播放', '立即观看',
                        '播放', '观看'
                    ];

                    function isBlacklisted(str) {
                        if (!str) return false;
                        var lower = str.toLowerCase();
                        for (var i = 0; i < nonDramaKeywords.length; i++) {
                            if (lower.indexOf(nonDramaKeywords[i].toLowerCase()) !== -1) return true;
                        }
                        return false;
                    }

                    function isStrictDramaTitleBlacklisted(str) {
                        if (!str) return true;
                        var lower = str.toLowerCase().trim();
                        for (var i = 0; i < nonDramaKeywords.length; i++) {
                            var kw = nonDramaKeywords[i].toLowerCase();
                            if (lower === kw || lower.indexOf(kw) !== -1) return true;
                        }
                        return false;
                    }

                    // 檢查文字是否為播放量、熱度、時間或純數字（徹底防止 236.4w 等被抓成劇名）
                    function isViewCountOrMetaText(str) {
                        if (!str) return false;
                        var s = str.trim();
                        if (/^d+(.d+)?[wWkK万萬億亿]?s*(次?播放|熱度|热度|人看過|人看过|次)?$/i.test(s)) return true;
                        if (/^(熱度|热度|播放|點擊|点击)s*[:：]?s*d+(.d+)?[wWkK万萬]?$/i.test(s)) return true;
                        if (/^d+(.d+)?[wWkK万萬]?$/i.test(s)) return true;
                        if (/^[0-9.:-s]+$/.test(s)) return true;
                        if (/^TOPs*d+/i.test(s)) return true;
                        return false;
                    }

                    function toAbsoluteUrl(url) {
                        if (!url) return '';
                        url = url.trim();
                        try {
                            if (url.indexOf('//') === 0) return location.protocol + url;
                            return new URL(url, location.href).href;
                        } catch(e) {
                            return url;
                        }
                    }

                    function isValidImageUrl(url) {
                        if (!url) return false;
                        var lower = url.toLowerCase();
                        if (lower.indexOf('data:image') === 0 && lower.indexOf('base64') !== -1 && lower.length < 200) return false;
                        if (lower.indexOf('logo') !== -1 || lower.indexOf('avatar') !== -1 || lower.indexOf('icon') !== -1) return false;
                        return true;
                    }

                    // 將已載入的 img 元素繪製到 240px 離屏 Canvas 並輸出 Base64
                    function extractCanvasBase64(imgEl) {
                        if (!imgEl) return '';
                        try {
                            var nw = imgEl.naturalWidth || imgEl.width || 0;
                            var nh = imgEl.naturalHeight || imgEl.height || 0;
                            if (nw <= 0 || nh <= 0) return '';
                            var targetW = 240;
                            var targetH = Math.round((nh / nw) * targetW);
                            if (targetH <= 0 || isNaN(targetH)) targetH = 340;
                            var canvas = document.createElement('canvas');
                            canvas.width = targetW;
                            canvas.height = targetH;
                            var ctx = canvas.getContext('2d');
                            ctx.drawImage(imgEl, 0, 0, targetW, targetH);
                            var dataUrl = canvas.toDataURL('image/jpeg', 0.75);
                            if (dataUrl && dataUrl.length > 200) {
                                return dataUrl;
                            }
                        } catch(e) {}
                        return '';
                    }

                    function extractBackgroundImage(el) {
                        if (!el) return '';
                        var candidates = [el];
                        if (el.parentElement) candidates.push(el.parentElement);
                        var children = el.querySelectorAll('div, span, a, section, i');
                        for (var c = 0; c < Math.min(children.length, 6); c++) {
                            candidates.push(children[c]);
                        }
                        for (var i = 0; i < candidates.length; i++) {
                            var target = candidates[i];
                            var bg = target.style.backgroundImage || window.getComputedStyle(target).backgroundImage || '';
                            if (bg && bg !== 'none') {
                                var match = bg.match(/url(["']?([^"')]+)["']?)/);
                                if (match && match[1] && isValidImageUrl(match[1])) {
                                    return toAbsoluteUrl(match[1]);
                                }
                            }
                        }
                        return '';
                    }

                    function extractCoverFromElement(card) {
                        // 1. 若卡片內本身就包含現成 canvas（JS 解密直接畫在 canvas 上）
                        var canvasEl = card.querySelector('canvas');
                        if (canvasEl) {
                            try {
                                var cData = canvasEl.toDataURL('image/jpeg', 0.75);
                                if (cData && cData.length > 200) return cData;
                            } catch(e) {}
                        }

                        // 2. 查找 img 標籤
                        var img = card.querySelector('img');
                        if (img) {
                            // 若圖片已解密或渲染完畢，嘗試轉成 Base64
                            var b64 = extractCanvasBase64(img);
                            if (b64) return b64;

                            // 若圖片 src 本身已經是 data:image
                            var rawSrc = img.currentSrc || img.src || '';
                            if (rawSrc && rawSrc.indexOf('data:image') === 0 && rawSrc.length > 200) return rawSrc;

                            var attrSrc = img.getAttribute('data-src') ||
                                          img.getAttribute('data-original') ||
                                          img.getAttribute('data-lazy-src') ||
                                          img.getAttribute('data-background') ||
                                          img.getAttribute('data-srcset') || '';
                            if (attrSrc) {
                                if (attrSrc.indexOf(',') !== -1) {
                                    var parts = attrSrc.split(',');
                                    attrSrc = parts[parts.length - 1].trim().split(' ')[0];
                                }
                                if (isValidImageUrl(attrSrc)) return toAbsoluteUrl(attrSrc);
                            }

                            if (rawSrc && isValidImageUrl(rawSrc)) {
                                return toAbsoluteUrl(rawSrc);
                            }
                        }

                        // 3. 查找背景圖片
                        return extractBackgroundImage(card);
                    }

                    function isValidDramaUrl(href) {
                        if (!href) return false;
                        var lower = href.toLowerCase();
                        if (lower.indexOf('javascript') === 0 || lower.indexOf('.apk') !== -1) return false;
                        try {
                            var u = new URL(href, location.href);
                            var path = u.pathname;
                            if (path === '/' || path === '') return false;
                            if (path.indexOf('/category') === 0 || path.indexOf('/cat') === 0 || path.indexOf('/type') === 0) return false;
                            if (path.indexOf('/tag') === 0 || path.indexOf('/tags') === 0) return false;
                            if (path.indexOf('/topic') === 0 || path.indexOf('/topics') === 0) return false;
                            if (path.indexOf('/search') === 0) return false;
                            if (path.indexOf('/member') === 0 || path.indexOf('/user') === 0) return false;

                            if (lower.indexOf('play') !== -1 || lower.indexOf('video') !== -1 ||
                                lower.indexOf('detail') !== -1 || lower.indexOf('vod') !== -1 ||
                                lower.indexOf('drama') !== -1 || lower.indexOf('item') !== -1 ||
                                /d+/.test(path) || path.indexOf('.html') !== -1) {
                                return true;
                            }
                            var segments = path.split('/').filter(Boolean);
                            return segments.length >= 1;
                        } catch(e) {
                            return false;
                        }
                    }

                    // 智慧向上與向下定位真實有效的卡片詳情/播放連結
                    function extractValidLink(el) {
                        if (!el) return '';
                        if (el.tagName && el.tagName.toUpperCase() === 'A' && el.href) {
                            var h = el.href;
                            if (h.indexOf('javascript') === -1 && h.indexOf('#') === -1 && h.indexOf('.apk') === -1) {
                                return h;
                            }
                        }
                        var parentA = el.closest ? el.closest('a[href]:not([href^="javascript"]):not([href^="#"])') : null;
                        if (parentA && parentA.href) return parentA.href;

                        var links = el.querySelectorAll ? el.querySelectorAll('a[href]:not([href^="javascript"]):not([href^="#"])') : [];
                        for (var i = 0; i < links.length; i++) {
                            var lh = links[i].href;
                            if (isValidDramaUrl(lh)) return lh;
                        }
                        if (links.length > 0) return links[0].href;

                        var anyParent = el.closest ? el.closest('a') : null;
                        if (anyParent && anyParent.href && anyParent.href.indexOf('javascript') === -1) return anyParent.href;
                        var anyChild = el.querySelector ? el.querySelector('a') : null;
                        if (anyChild && anyChild.href && anyChild.href.indexOf('javascript') === -1) return anyChild.href;

                        return '';
                    }

                    function hasEpisodeBadge(badge) {
                        if (!badge) return false;
                        return /更新至s*d+s*集/.test(badge) ||
                               /全s*d+s*集/.test(badge) ||
                               badge.indexOf('集') !== -1 ||
                               badge.indexOf('完結') !== -1 ||
                               badge.indexOf('完结') !== -1 ||
                               badge.indexOf('更新') !== -1 ||
                               /^d+(.d+)?[wWkK万萬]/.test(badge) ||
                               /^TOPs*d+/i.test(badge);
                    }

                    // A. 左側邊欄一級大分類（首頁推薦、黃果原創、短劇、漫劇、專題、排行榜、博客、黑料吃瓜）
                    var categories = [];
                    var visitedCatUrls = {};
                    var standardCategories = [
                        { id: 'cat_home', title: '首頁推薦', path: '/' },
                        { id: 'cat_orig', title: '黃果原創', path: '/original' },
                        { id: 'cat_short', title: '短劇', path: '/short' },
                        { id: 'cat_comic', title: '漫劇', path: '/comic' },
                        { id: 'cat_topic', title: '專題', path: '/topic' },
                        { id: 'cat_rank', title: '排行榜', path: '/rank' },
                        { id: 'cat_blog', title: '博客', path: '/blog' },
                        { id: 'cat_gossip', title: '黑料吃瓜', path: '/gossip' }
                    ];

                    var navLinks = document.querySelectorAll(
                        '.van-tab, nav a, .nav a, .menu a, [class*="category"] a, [class*="nav"] a, a[href*="cat"], a[href*="type"], a[href*="class"], a'
                    );
                    var foundNavMap = {};
                    for (var n = 0; n < navLinks.length; n++) {
                        var nEl = navLinks[n];
                        var nTitle = (nEl.innerText || nEl.textContent || '').trim();
                        var nHref = (nEl.tagName.toUpperCase() === 'A') ? nEl.href : (nEl.querySelector('a') ? nEl.querySelector('a').href : '');
                        nHref = toAbsoluteUrl(nHref);
                        if (!nTitle || !nHref || nTitle.length > 10 || isBlacklisted(nTitle)) continue;
                        foundNavMap[nTitle] = nHref;
                    }

                    for (var s = 0; s < standardCategories.length; s++) {
                        var sc = standardCategories[s];
                        var realUrl = foundNavMap[sc.title] || '';
                        if (!realUrl) {
                            for (var nk in foundNavMap) {
                                if (nk.indexOf(sc.title) !== -1 || sc.title.indexOf(nk) !== -1) {
                                    realUrl = foundNavMap[nk];
                                    break;
                                }
                            }
                        }
                        if (!realUrl) {
                            realUrl = toAbsoluteUrl(sc.path);
                        }
                        categories.push({
                            id: sc.id,
                            title: sc.title,
                            url: realUrl,
                            count: ''
                        });
                        visitedCatUrls[realUrl] = true;
                    }

                    // B. 首頁上方橫向子分類 Tab（主板塊、專題推薦、熱門標籤）
                    var subTabs = [];
                    subTabs.push({ id: 'tab_all', title: '全部精選', url: '', type: 'SECTION' });
                    subTabs.push({ id: 'tab_featured', title: '精選推薦', url: '', type: 'SECTION' });
                    subTabs.push({ id: 'tab_latest', title: '最近上新', url: '', type: 'SECTION' });
                    subTabs.push({ id: 'tab_hot', title: '熱播短劇', url: '', type: 'SECTION' });

                    var defaultTopics = ['81部精品高分', '68部家庭倫理', '100部天菜男同'];
                    var foundTopics = {};
                    var topicElements = document.querySelectorAll('a, .van-card, [class*="topic"], [class*="collection"], div');
                    for (var t = 0; t < topicElements.length; t++) {
                        var tEl = topicElements[t];
                        var tText = (tEl.innerText || tEl.textContent || '').trim();
                        if (!tText || isBlacklisted(tText)) continue;
                        var matchCount = tText.match(/(d+)s*部([^
]+)/);
                        if (matchCount) {
                            var tLink = (tEl.tagName.toUpperCase() === 'A') ? tEl.href : (tEl.querySelector('a') ? tEl.querySelector('a').href : '');
                            tLink = toAbsoluteUrl(tLink);
                            var topicTitle = (matchCount[1] + '部' + matchCount[2]).trim().substring(0, 12);
                            if (!foundTopics[topicTitle]) {
                                foundTopics[topicTitle] = true;
                                subTabs.push({
                                    id: 'tab_topic_' + subTabs.length,
                                    title: topicTitle,
                                    url: tLink,
                                    type: 'TOPIC'
                                });
                            }
                        }
                    }
                    for (var dt = 0; dt < defaultTopics.length; dt++) {
                        if (!foundTopics[defaultTopics[dt]]) {
                            subTabs.push({
                                id: 'tab_topic_' + subTabs.length,
                                title: defaultTopics[dt],
                                url: '',
                                type: 'TOPIC'
                            });
                        }
                    }

                    var defaultTags = [
                        '都市', '古風', '奇幻', '現代', '職場', '逆襲', '校園', '綠帽', '熟女', '母子', '亂倫',
                        '系統', '仙俠', '后宮', '大男主', '性轉', '腦洞', '懸疑', 'NTR', '武俠'
                    ];
                    var foundTags = {};
                    var tagElements = document.querySelectorAll('[class*="tag"] a, .tags a, [class*="tag"] span, .tags span, a[href*="tag"]');
                    for (var tg = 0; tg < tagElements.length; tg++) {
                        var tagText = (tagElements[tg].innerText || tagElements[tg].textContent || '').trim();
                        var tagHref = (tagElements[tg].tagName.toUpperCase() === 'A') ? tagElements[tg].href : '';
                        if (tagText && tagText.length <= 6 && !isBlacklisted(tagText)) {
                            if (!foundTags[tagText]) {
                                foundTags[tagText] = true;
                                subTabs.push({
                                    id: 'tab_tag_' + subTabs.length,
                                    title: tagText,
                                    url: toAbsoluteUrl(tagHref),
                                    type: 'TAG'
                                });
                            }
                        }
                    }
                    for (var dti = 0; dti < defaultTags.length; dti++) {
                        if (!foundTags[defaultTags[dti]]) {
                            subTabs.push({
                                id: 'tab_tag_' + subTabs.length,
                                title: defaultTags[dti],
                                url: '',
                                type: 'TAG'
                            });
                        }
                    }

                    // C. 全量卡片掃描與分組歸類
                    function detectSection(el, index, total) {
                        var current = el;
                        while (current && current !== document.body) {
                            var prev = current;
                            while (prev) {
                                var headings = prev.querySelectorAll ? prev.querySelectorAll('h1, h2, h3, h4, .van-cell__title, [class*="title"], [class*="header"]') : [];
                                for (var h = 0; h < headings.length; h++) {
                                    var ht = (headings[h].innerText || headings[h].textContent || '').trim();
                                    if (ht) {
                                        if (ht.indexOf('精選') !== -1 || ht.indexOf('精选') !== -1 || ht.indexOf('推薦') !== -1 || ht.indexOf('推荐') !== -1) return '精選推薦';
                                        if (ht.indexOf('上新') !== -1 || ht.indexOf('最新') !== -1) return '最近上新';
                                        if (ht.indexOf('熱播') !== -1 || ht.indexOf('热播') !== -1 || ht.indexOf('火熱') !== -1) return '熱播短劇';
                                    }
                                }
                                prev = prev.previousElementSibling;
                            }
                            current = current.parentElement;
                        }
                        if (total > 0) {
                            var ratio = index / total;
                            if (ratio < 0.35) return '精選推薦';
                            if (ratio < 0.70) return '最近上新';
                            return '熱播短劇';
                        }
                        return '精選推薦';
                    }

                    function detectTag(text) {
                        if (!text) return '';
                        for (var i = 0; i < defaultTags.length; i++) {
                            if (text.indexOf(defaultTags[i]) !== -1) return defaultTags[i];
                        }
                        return '';
                    }

                    var dramaMap = {};
                    var dramaIndex = 0;
                    var cards = document.querySelectorAll(
                        'a, .van-card, [class*="drama"], [class*="item"], [class*="card"], .video-card, li'
                    );

                    for (var c = 0; c < cards.length; c++) {
                        var card = cards[c];
                        var cardText = (card.innerText || card.textContent || '').trim();

                        if (isBlacklisted(cardText)) continue;
                        if (/(d+)s*部[劇剧]集/.test(cardText) || /共s*(d+)s*部/.test(cardText)) continue;

                        var href = extractValidLink(card);
                        if (!href) continue;
                        href = toAbsoluteUrl(href);
                        if (!href || isBlacklisted(href)) continue;

                        var cover = extractCoverFromElement(card);
                        var title = '';
                        var badge = '';

                        var primaryTitles = [];
                        var aTag = (card.tagName.toUpperCase() === 'A') ? card : card.querySelector('a');
                        if (aTag) {
                            var aTitle = (aTag.getAttribute('title') || '').trim();
                            if (aTitle && !isBlacklisted(aTitle) && !isViewCountOrMetaText(aTitle) && aTitle.length >= 2) {
                                primaryTitles.push({ text: aTitle, weight: 100 });
                            }
                        }
                        var imgEl = card.querySelector('img');
                        if (imgEl) {
                            var alt = (imgEl.getAttribute('alt') || '').trim();
                            if (alt && !isBlacklisted(alt) && !isViewCountOrMetaText(alt) && alt.length >= 2) {
                                primaryTitles.push({ text: alt, weight: 90 });
                            }
                        }

                        var textNodes = card.querySelectorAll(
                            '.title, [class*="title"], [class*="name"], h1, h2, h3, h4, p, span, div'
                        );

                        for (var tn = 0; tn < textNodes.length; tn++) {
                            var el = textNodes[tn];
                            if (el.children.length > 2) continue;
                            var t = (el.innerText || el.textContent || '').trim();
                            if (!t || isBlacklisted(t)) continue;

                            if (/更新至s*d+s*集/.test(t) || /全s*d+s*集/.test(t) || t === '完結' || t === '完结') {
                                if (!badge) badge = t;
                                continue;
                            }

                            // 排除播放量/熱度/純數字，並將播放量留存為角標
                            if (isViewCountOrMetaText(t)) {
                                if (!badge && /d/.test(t)) {
                                    badge = t;
                                }
                                continue;
                            }

                            if (t === '立即播放' || t === '立即观看' || t === '播放' || t === '观看' || t === '進入網站' || t === '进入网站') {
                                continue;
                            }

                            var weight = 10;
                            var cls = (el.className || '') + ' ' + (el.parentElement ? el.parentElement.className : '');
                            var tag = el.tagName.toUpperCase();
                            if (cls.indexOf('title') !== -1 || cls.indexOf('name') !== -1) weight += 50;
                            if (tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4') weight += 40;
                            if (/[《【]/.test(t)) weight += 30;
                            if (/[一-龥]/.test(t)) weight += 20;

                            primaryTitles.push({ text: t, weight: weight });
                        }

                        primaryTitles.sort(function(a, b) {
                            if (b.weight !== a.weight) return b.weight - a.weight;
                            return b.text.length - a.text.length;
                        });

                        if (primaryTitles.length > 0) {
                            title = primaryTitles[0].text;
                        }

                        if (!title || /^d+$/.test(title)) continue;
                        if (isStrictDramaTitleBlacklisted(title)) continue;

                        var cleanedTitle = title.replace(/[《》【】[]()s-_]/g, '').trim();
                        if (cleanedTitle.length < 2) continue;

                        var badgeValid = hasEpisodeBadge(badge);
                        var urlValid = isValidDramaUrl(href);
                        if (!badgeValid && !urlValid) continue;

                        var section = detectSection(card, c, cards.length);
                        var dramaTag = detectTag(cardText + ' ' + title);

                        var normalizedKey = cleanedTitle.toLowerCase();
                        var score = 0;
                        var lowerHref = href.toLowerCase();
                        if (lowerHref.indexOf('play') !== -1 || lowerHref.indexOf('video') !== -1) score += 100;
                        if (lowerHref.indexOf('detail') !== -1 || lowerHref.indexOf('vod') !== -1) score += 50;
                        if (/\d+/.test(lowerHref)) score += 20;
                        if (cover) score += 15;
                        if (badgeValid) score += 10;

                        if (!dramaMap[normalizedKey] || score > dramaMap[normalizedKey].score) {
                            var finalCover = cover || (dramaMap[normalizedKey] ? dramaMap[normalizedKey].item.coverUrl : '');
                            var finalBadge = badge || (dramaMap[normalizedKey] ? dramaMap[normalizedKey].item.badge : '');
                            var finalSection = section || (dramaMap[normalizedKey] ? dramaMap[normalizedKey].item.section : '');
                            var finalTag = dramaTag || (dramaMap[normalizedKey] ? dramaMap[normalizedKey].item.tag : '');

                            dramaMap[normalizedKey] = {
                                item: {
                                    id: 'drama_' + (dramaIndex++),
                                    title: title.replace(/\n+/g, ' ').substring(0, 40),
                                    coverUrl: finalCover,
                                    detailUrl: href,
                                    badge: finalBadge,
                                    section: finalSection,
                                    tag: finalTag
                                },
                                score: score
                            };
                        }
                    }

                    var dramaResults = [];
                    for (var k in dramaMap) {
                        if (dramaMap.hasOwnProperty(k)) {
                            dramaResults.push(dramaMap[k].item);
                        }
                    }

                    if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onDataScraped) {
                        window.${INTERFACE_NAME}.onDataScraped(
                            JSON.stringify(categories),
                            JSON.stringify(subTabs),
                            JSON.stringify(dramaResults)
                        );
                    }
                }
            })();
        """.trimIndent()
        try {
            wv.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Log.e(TAG, "evaluateJavascript 異常: ${t.message}")
        }
    }

    /**
     * 注入詳情頁選集與播放器嗅探 JS
     * 1. 輪詢檢查 DOM <video> 標籤，若有 direct video src 立即通報
     * 2. 500ms 高頻輪巡自動點擊「立即播放」、「第1集」、「開始播放」等按鈕
     * 3. 完整抓取多集集數清單
     */
    private fun injectDetailAndSnifferScript(wv: WebView) {
        val js = """
            (function() {
                try {
                    ['isAdult', 'adult', 'is_adult', 'age_verify', 'has_confirmed_age', 'age_confirmed'].forEach(function(k) {
                        localStorage.setItem(k, 'true');
                        sessionStorage.setItem(k, 'true');
                    });
                } catch(e) {}

                function autoClickAge() {
                    var btns = document.querySelectorAll('button, a, div, span, [role="button"], .van-button');
                    var kws = ['年滿18', '年满18', '進入網站', '进入网站', '我知道了'];
                    for (var i = 0; i < btns.length; i++) {
                        var txt = (btns[i].innerText || btns[i].textContent || '').trim();
                        if (!txt) continue;
                        for (var j = 0; j < kws.length; j++) {
                            if (txt.indexOf(kws[j]) !== -1) {
                                try { btns[i].click(); } catch(e) {}
                                break;
                            }
                        }
                    }
                }
                autoClickAge();

                function triggerPlayClick() {
                    autoClickAge();
                    var playKeywords = ['立即播放', '第1集', '开始播放', '開始播放', '立即观看', '立即觀看', '播放', '观看', '觀看', '全集播放', '选集', '選集'];
                    var clickables = document.querySelectorAll('button, a, div, span, [role="button"], .van-button, .play-btn, .btn-play, [class*="play"], [class*="start"]');
                    for (var i = 0; i < clickables.length; i++) {
                        var el = clickables[i];
                        var txt = (el.innerText || el.textContent || '').trim();
                        if (txt) {
                            for (var k = 0; k < playKeywords.length; k++) {
                                if (txt === playKeywords[k] || (txt.indexOf(playKeywords[k]) !== -1 && txt.length <= 10)) {
                                    try {
                                        el.click();
                                        console.log('自動點擊播放按鈕: ' + txt);
                                        return true;
                                    } catch(e) {}
                                }
                            }
                        }
                        var cls = el.className || '';
                        if (typeof cls === 'string' && (cls.indexOf('btn-play') !== -1 || cls.indexOf('play-icon') !== -1 || cls.indexOf('video-play') !== -1)) {
                            try {
                                el.click();
                                return true;
                            } catch(e) {}
                        }
                    }
                    return false;
                }

                function sniffVideoDOM() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        var src = v.currentSrc || v.src;
                        if (!src) {
                            var source = v.querySelector('source');
                            if (source) src = source.src;
                        }
                        if (src && src.indexOf('http') === 0) {
                            try { v.pause(); v.muted = true; } catch(e) {}
                            if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onStreamSniffed) {
                                window.${INTERFACE_NAME}.onStreamSniffed(src);
                                return true;
                            }
                        }
                    }
                    return false;
                }

                if (!sniffVideoDOM()) {
                    triggerPlayClick();
                }

                function scrapeEpisodes() {
                    var epList = [];
                    var visited = {};
                    var buttons = document.querySelectorAll(
                        '[class*="episode"] a, [class*="chapter"] a, [class*="anthology"] a, .van-grid-item a, [class*="episode"] button, [class*="anthology"] button, .van-grid-item, [class*="playlist"] a'
                    );

                    for (var i = 0; i < buttons.length; i++) {
                        var el = buttons[i];
                        var aTag = (el.tagName.toUpperCase() === 'A') ? el : el.querySelector('a');
                        var href = aTag ? aTag.href : location.href;
                        var txt = (el.innerText || el.textContent || '').trim();
                        if (!txt) continue;

                        var match = txt.match(/\d+/);
                        if (match || txt.indexOf('集') !== -1) {
                            var epIndex = match ? parseInt(match[0]) : (epList.length + 1);
                            var key = href + '_' + epIndex;
                            if (!visited[key]) {
                                visited[key] = true;
                                epList.push({
                                    index: epIndex,
                                    title: txt.replace(/\s+/g, ' '),
                                    url: href
                                });
                            }
                        }
                    }

                    epList.sort(function(a, b) { return a.index - b.index; });

                    if (epList.length === 0) {
                        epList.push({
                            index: 1,
                            title: '第 1 集 (全集)',
                            url: location.href
                        });
                    }

                    if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onEpisodesScraped) {
                        window.${INTERFACE_NAME}.onEpisodesScraped(JSON.stringify(epList));
                    }
                }

                scrapeEpisodes();

                var pollCount = 0;
                var pollTimer = setInterval(function() {
                    pollCount++;
                    if (!sniffVideoDOM()) {
                        triggerPlayClick();
                    }
                    if (sniffVideoDOM() || pollCount >= 30) {
                        clearInterval(pollTimer);
                    }
                }, 500);
            })();
        """.trimIndent()
        try {
            wv.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Log.e(TAG, "evaluateJavascript 嗅探異常: ${t.message}")
        }
    }

    /**
     * 首頁抓取 JS Interface
     */
    inner class HomeScraperJsInterface {
        @JavascriptInterface
        fun onDataScraped(categoriesJson: String, subTabsJson: String, dramasJson: String) {
            mainHandler.post {
                cancelHomeTimeout()
                try {
                    val categoryList = mutableListOf<CategoryItem>()
                    val catArray = JSONArray(categoriesJson)
                    for (i in 0 until catArray.length()) {
                        val obj = catArray.getJSONObject(i)
                        categoryList.add(
                            CategoryItem(
                                id = obj.optString("id", "cat_$i"),
                                title = obj.optString("title", "分類"),
                                url = obj.optString("url", ""),
                                count = obj.optString("count", "")
                            )
                        )
                    }

                    val subTabList = mutableListOf<SubTabItem>()
                    val subTabArray = JSONArray(subTabsJson)
                    for (i in 0 until subTabArray.length()) {
                        val obj = subTabArray.getJSONObject(i)
                        val typeStr = obj.optString("type", "SECTION")
                        val type = when (typeStr) {
                            "TOPIC" -> SubTabItem.Type.TOPIC
                            "TAG" -> SubTabItem.Type.TAG
                            else -> SubTabItem.Type.SECTION
                        }
                        subTabList.add(
                            SubTabItem(
                                id = obj.optString("id", "tab_$i"),
                                title = obj.optString("title", ""),
                                url = obj.optString("url", ""),
                                type = type
                            )
                        )
                    }

                    val dramaList = mutableListOf<DramaItem>()
                    val dramaArray = JSONArray(dramasJson)
                    for (i in 0 until dramaArray.length()) {
                        val obj = dramaArray.getJSONObject(i)
                        dramaList.add(
                            DramaItem(
                                id = obj.optString("id", "drama_$i"),
                                title = obj.optString("title", "短劇"),
                                coverUrl = obj.optString("coverUrl", ""),
                                detailUrl = obj.optString("detailUrl", ""),
                                badge = obj.optString("badge", ""),
                                section = obj.optString("section", ""),
                                tag = obj.optString("tag", "")
                            )
                        )
                    }

                    Log.i(TAG, "成功解析分類 ${categoryList.size} 項，子標籤 ${subTabList.size} 個，短劇 ${dramaList.size} 部 (已去重)")
                    dramaList.take(3).forEach {
                        val previewCover = if (it.coverUrl.length > 60) it.coverUrl.take(60) + "..." else it.coverUrl
                        Log.d(TAG, "抓取短劇範例: [${it.title}] badge=[${it.badge}] detailUrl=[${it.detailUrl}] -> cover: $previewCover")
                    }

                    onHomeScrapedListener?.invoke(categoryList, subTabList, dramaList)
                    onCategoryDramasListener?.invoke(dramaList)

                } catch (e: Exception) {
                    Log.e(TAG, "解析網頁抓取 JSON 失敗: ${e.message}")
                    onHomeScrapedListener?.invoke(emptyList(), emptyList(), emptyList())
                    onCategoryDramasListener?.invoke(emptyList())
                }
            }
        }

        @JavascriptInterface
        fun onDataScraped(categoriesJson: String, dramasJson: String) {
            onDataScraped(categoriesJson, "[]", dramasJson)
        }
    }

    /**
     * 詳情頁與串流嗅探 JS Interface
     */
    inner class DetailSnifferJsInterface {
        @JavascriptInterface
        fun onEpisodesScraped(jsonStr: String) {
            mainHandler.post {
                try {
                    val list = mutableListOf<EpisodeItem>()
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            EpisodeItem(
                                index = obj.optInt("index", i + 1),
                                title = obj.optString("title", "第 ${i + 1} 集"),
                                url = obj.optString("url", "")
                            )
                        )
                    }
                    Log.i(TAG, "成功解析集數清單共 ${list.size} 集")
                    onEpisodesLoadedListener?.invoke(list)
                } catch (e: Exception) {
                    Log.e(TAG, "解析選集 JSON 失敗: ${e.message}")
                    onEpisodesLoadedListener?.invoke(emptyList())
                }
            }
        }

        @JavascriptInterface
        fun onStreamSniffed(streamUrl: String) {
            Log.i(TAG, "收到 JS Interface onStreamSniffed: $streamUrl")
            if (streamUrl.isNotBlank() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
                val lower = streamUrl.lowercase()
                if (!lower.contains(".js") && !lower.contains(".css") && !lower.contains(".apk") && !lower.contains("google") && !lower.contains("analytics")) {
                    if (currentSniffedUrl != streamUrl) {
                        currentSniffedUrl = streamUrl
                        Log.i(TAG, "DOM <video> 成功取得直連影片網址: $streamUrl")
                        mainHandler.post {
                            cancelDetailTimeout()
                            pauseDetailVideo()
                            onStreamFoundListener?.invoke(streamUrl)
                        }
                        return
                    }
                }
            }
            checkAndNotifyStreamUrl(streamUrl)
        }
    }

    fun destroy() {
        mainHandler.post {
            cancelHomeTimeout()
            cancelDetailTimeout()
            try {
                homeWebView?.stopLoading()
                homeWebView?.destroy()
                homeWebView = null

                detailWebView?.stopLoading()
                detailWebView?.destroy()
                detailWebView = null
            } catch (e: Exception) {
                Log.e(TAG, "銷毀 WebScraperBridge 異常: ${e.message}")
            }
        }
    }
}
