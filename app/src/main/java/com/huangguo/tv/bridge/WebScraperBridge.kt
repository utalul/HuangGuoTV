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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 隱藏式無頭網頁抓取與串流嗅探引擎
 * 針對 32 位元低記憶體 Android TV 進行深度優化：
 * 1. 延遲初始化 (Lazy Init)：按需建立 WebView，避免同時駐留多個實例引起 OOM。
 * 2. 15 秒超時保護 (Timeout)：避免因伺服器無響應或 DOM 異常導致 Loading 卡死。
 * 3. 系統缺少 WebView 元件防護與明確提示。
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

    // 首頁短劇與分類抓取回呼
    private var onHomeScrapedListener: ((List<CategoryItem>, List<DramaItem>) -> Unit)? = null
    // 單一分類短劇抓取回呼
    private var onCategoryDramasListener: ((List<DramaItem>) -> Unit)? = null

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
     * 檢查網址是否為真實影音串流 (.m3u8 / .mp4)，若是則回調給播放器並取消超時計時
     */
    private fun checkAndNotifyStreamUrl(url: String) {
        val lower = url.lowercase()
        val isMedia = lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains("/hls/") || lower.contains("/playlist")
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
            onHomeScrapedListener?.invoke(emptyList(), emptyList())
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
     * 抓取首頁資料（包含左側邊欄分類與首頁純短劇清單，具備 15s 超時保護）
     */
    fun scrapeHomeDataAndCategories(
        url: String,
        callback: (List<CategoryItem>, List<DramaItem>) -> Unit
    ) {
        this.onCategoryDramasListener = null
        this.onHomeScrapedListener = callback

        mainHandler.post {
            if (!isWebViewSupported) {
                Log.e(TAG, MSG_MISSING_WEBVIEW)
                callback(emptyList(), emptyList())
                return@post
            }

            try {
                if (!initHomeWebView()) {
                    callback(emptyList(), emptyList())
                    return@post
                }
                startHomeTimeout()
                homeWebView?.loadUrl(url) ?: run {
                    cancelHomeTimeout()
                    callback(emptyList(), emptyList())
                }
            } catch (t: Throwable) {
                cancelHomeTimeout()
                Log.e(TAG, "scrapeHomeDataAndCategories 異常: ${t.message}", t)
                callback(emptyList(), emptyList())
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

                window.scrollBy({ top: 1200, behavior: 'instant' });

                setTimeout(function() {
                    autoClickAge();

                    var blacklistWords = [
                        'telegram', 'tg', 'gmail', '@', '域名', '賬號', '帳號',
                        '關於我們', '关于我们', 'app', '下載', '下载', '登錄', '登录',
                        '客服', '聯繫', '联系', '版權', '版权', '廣告', '广告', '官方群'
                    ];

                    function isBlacklisted(str) {
                        if (!str) return false;
                        var lower = str.toLowerCase();
                        for (var i = 0; i < blacklistWords.length; i++) {
                            if (lower.indexOf(blacklistWords[i]) !== -1) return true;
                        }
                        return false;
                    }

                    function toAbsoluteUrl(url) {
                        if (!url) return '';
                        try {
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

                    function extractBackgroundImage(el) {
                        if (!el) return '';
                        var candidates = [el];
                        var children = el.querySelectorAll('div, span, a, section, i');
                        for (var c = 0; c < Math.min(children.length, 6); c++) {
                            candidates.push(children[c]);
                        }
                        for (var i = 0; i < candidates.length; i++) {
                            var target = candidates[i];
                            var bg = target.style.backgroundImage || window.getComputedStyle(target).backgroundImage || '';
                            if (bg && bg !== 'none') {
                                var match = bg.match(/url\(["']?([^"')]+)["']?\)/);
                                if (match && match[1] && isValidImageUrl(match[1])) {
                                    return toAbsoluteUrl(match[1]);
                                }
                            }
                        }
                        return '';
                    }

                    // 1. 解析左側分類列表
                    var categories = [];
                    var visitedCatUrls = {};

                    categories.push({
                        id: 'cat_home',
                        title: '首頁推薦',
                        url: location.href,
                        count: ''
                    });
                    visitedCatUrls[location.href] = true;

                    var navLinks = document.querySelectorAll(
                        '.van-tab, nav a, .nav a, .menu a, [class*="category"] a, [class*="nav"] a, a[href*="cat"], a[href*="type"], a[href*="class"]'
                    );
                    for (var n = 0; n < navLinks.length; n++) {
                        var nEl = navLinks[n];
                        var nTitle = (nEl.innerText || nEl.textContent || '').trim();
                        var nHref = (nEl.tagName.toUpperCase() === 'A') ? nEl.href : (nEl.querySelector('a') ? nEl.querySelector('a').href : '');
                        nHref = toAbsoluteUrl(nHref);

                        if (!nTitle || nTitle.length > 10 || isBlacklisted(nTitle)) continue;
                        if (nTitle === '首頁' || nTitle === '首页' || nTitle === '推薦' || nTitle === '推荐') continue;
                        if (nHref && !visitedCatUrls[nHref]) {
                            visitedCatUrls[nHref] = true;
                            categories.push({
                                id: 'cat_' + categories.length,
                                title: nTitle,
                                url: nHref,
                                count: ''
                            });
                        }
                    }

                    var allElements = document.querySelectorAll('a, .van-card, [class*="item"], [class*="card"], [class*="topic"], div');
                    for (var t = 0; t < allElements.length; t++) {
                        var tEl = allElements[t];
                        var tText = (tEl.innerText || tEl.textContent || '').trim();
                        if (!tText || isBlacklisted(tText)) continue;

                        var matchCount = tText.match(/(\d+)\s*部[劇剧]集/) || tText.match(/共\s*(\d+)\s*部/);
                        if (matchCount) {
                            var tLink = (tEl.tagName.toUpperCase() === 'A') ? tEl.href : (tEl.querySelector('a') ? tEl.querySelector('a').href : '');
                            tLink = toAbsoluteUrl(tLink);
                            if (tLink && !visitedCatUrls[tLink]) {
                                var cleanedTitle = tText.replace(/(\d+)\s*部[劇剧]集/g, '')
                                                        .replace(/共\s*\d+\s*部/g, '')
                                                        .replace(/\n+/g, ' ')
                                                        .trim();
                                if (cleanedTitle.length > 12) cleanedTitle = cleanedTitle.substring(0, 12);
                                if (!cleanedTitle) cleanedTitle = '精選合集';

                                visitedCatUrls[tLink] = true;
                                categories.push({
                                    id: 'cat_topic_' + categories.length,
                                    title: cleanedTitle,
                                    url: tLink,
                                    count: matchCount[1] + '部'
                                });
                            }
                        }
                    }

                    // 2. 解析右側海報牆
                    var dramaResults = [];
                    var visitedDramaUrls = {};

                    var cards = document.querySelectorAll(
                        'a, .van-card, [class*="drama"], [class*="item"], [class*="card"], .video-card, li'
                    );

                    for (var c = 0; c < cards.length; c++) {
                        var card = cards[c];
                        var cardText = (card.innerText || card.textContent || '').trim();

                        if (isBlacklisted(cardText)) continue;
                        if (/(\d+)\s*部[劇剧]集/.test(cardText) || /共\s*(\d+)\s*部/.test(cardText)) continue;

                        var aTag = (card.tagName.toUpperCase() === 'A') ? card : card.querySelector('a');
                        var href = aTag ? aTag.href : '';
                        if (!href || href.indexOf('javascript') === 0 || href.indexOf('.apk') !== -1) continue;
                        href = toAbsoluteUrl(href);
                        if (isBlacklisted(href)) continue;
                        if (visitedDramaUrls[href]) continue;

                        var cover = '';
                        var img = card.querySelector('img');
                        if (img) {
                            var rawSrc = img.getAttribute('data-src') ||
                                         img.getAttribute('data-original') ||
                                         img.getAttribute('data-lazy-src') ||
                                         img.getAttribute('data-background') ||
                                         img.currentSrc ||
                                         img.src || '';
                            if (isValidImageUrl(rawSrc)) {
                                cover = toAbsoluteUrl(rawSrc);
                            }
                        }
                        if (!cover) {
                            cover = extractBackgroundImage(card);
                        }

                        var title = '';
                        var badge = '';

                        var textNodes = card.querySelectorAll(
                            '.title, [class*="title"], [class*="name"], h1, h2, h3, h4, p, span, div'
                        );
                        var candidateTitles = [];

                        for (var tn = 0; tn < textNodes.length; tn++) {
                            var el = textNodes[tn];
                            if (el.children.length > 2) continue;
                            var t = (el.innerText || el.textContent || '').trim();
                            if (!t || isBlacklisted(t)) continue;

                            if (/更新至\s*\d+\s*集/.test(t) || /全\s*\d+\s*集/.test(t) || t === '完結' || t === '完结') {
                                if (!badge) badge = t;
                                continue;
                            }

                            if (/^\d{1,3}$/.test(t) || /^TOP\s*\d+/i.test(t)) {
                                if (!badge) badge = 'TOP ' + t.replace(/[^0-9]/g, '');
                                continue;
                            }

                            if (t === '立即播放' || t === '立即观看' || t === '播放' || t === '观看' || t === '進入網站' || t === '进入网站') {
                                continue;
                            }

                            candidateTitles.push(t);
                        }

                        candidateTitles.sort(function(a, b) { return b.length - a.length; });
                        if (candidateTitles.length > 0) {
                            title = candidateTitles[0];
                        }

                        if (!title && aTag) {
                            var aTitle = (aTag.getAttribute('title') || '').trim();
                            if (aTitle && !isBlacklisted(aTitle)) title = aTitle;
                        }
                        if (!title && img) {
                            var alt = (img.getAttribute('alt') || '').trim();
                            if (alt && !isBlacklisted(alt)) title = alt;
                        }

                        if (!title || /^\d+$/.test(title) || title === '立即播放' || title === '播放') {
                            continue;
                        }

                        if (!cover && title.length < 3) continue;

                        visitedDramaUrls[href] = true;
                        dramaResults.push({
                            id: 'drama_' + dramaResults.length,
                            title: title.replace(/\n+/g, ' ').substring(0, 40),
                            coverUrl: cover,
                            detailUrl: href,
                            badge: badge
                        });
                    }

                    if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onDataScraped) {
                        window.${INTERFACE_NAME}.onDataScraped(
                            JSON.stringify(categories),
                            JSON.stringify(dramaResults)
                        );
                    }
                }, 1000);
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
                sniffVideoDOM();

                function scrapeEpisodes() {
                    var epList = [];
                    var visited = {};
                    var buttons = document.querySelectorAll(
                        '[class*="episode"] a, [class*="chapter"] a, [class*="anthology"] a, .van-grid-item a, [class*="episode"] button, [class*="anthology"] button, .van-grid-item'
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
                    if (sniffVideoDOM() || pollCount > 15) {
                        clearInterval(pollTimer);
                    }
                }, 800);
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
        fun onDataScraped(categoriesJson: String, dramasJson: String) {
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
                                badge = obj.optString("badge", "")
                            )
                        )
                    }

                    Log.i(TAG, "成功解析分類 ${categoryList.size} 項，短劇 ${dramaList.size} 部")

                    onHomeScrapedListener?.invoke(categoryList, dramaList)
                    onCategoryDramasListener?.invoke(dramaList)

                } catch (e: Exception) {
                    Log.e(TAG, "解析網頁抓取 JSON 失敗: ${e.message}")
                    onHomeScrapedListener?.invoke(emptyList(), emptyList())
                    onCategoryDramasListener?.invoke(emptyList())
                }
            }
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
