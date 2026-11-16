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
 * 封裝後台隱藏 WebView，保留 SSL 放行、混合內容、18 歲自動穿透，
 * 並即時抓取左側邊欄分類、右側海報牆短劇資料及嗅探 .m3u8 / .mp4 串流。
 */
class WebScraperBridge(private val context: Context) {

    companion object {
        private const val TAG = "WebScraperBridge"
        private const val INTERFACE_NAME = "AndroidBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val configManager = ConfigManager.getInstance(context)

    // 專職首頁與分類抓取的無頭 WebView
    private var homeWebView: WebView? = null
    // 專職詳情與選集/串流嗅探的無頭 WebView
    private var detailWebView: WebView? = null

    // 首頁短劇與分類抓取回呼
    private var onHomeScrapedListener: ((List<CategoryItem>, List<DramaItem>) -> Unit)? = null
    // 單一分類短劇抓取回呼
    private var onCategoryDramasListener: ((List<DramaItem>) -> Unit)? = null

    // 詳情頁與串流嗅探回呼
    private var onEpisodesLoadedListener: ((List<EpisodeItem>) -> Unit)? = null
    private var onStreamFoundListener: ((String) -> Unit)? = null

    // 防止重複回報同一個串流網址
    private var currentSniffedUrl: String? = null

    init {
        mainHandler.post {
            initHomeWebView()
            initDetailWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBaseWebView(): WebView {
        return WebView(context).apply {
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
    }

    /**
     * 初始化首頁抓取 WebView
     */
    @SuppressLint("AddJavascriptInterface")
    private fun initHomeWebView() {
        val wv = createBaseWebView()
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
    }

    /**
     * 初始化詳情與串流嗅探 WebView
     */
    @SuppressLint("AddJavascriptInterface")
    private fun initDetailWebView() {
        val wv = createBaseWebView()
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
    }

    /**
     * 檢查網址是否為真實影音串流 (.m3u8 / .mp4)，若是則回調給播放器
     */
    private fun checkAndNotifyStreamUrl(url: String) {
        val lower = url.lowercase()
        val isMedia = lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains("/hls/") || lower.contains("/playlist")
        if (isMedia && !lower.contains(".js") && !lower.contains(".css") && !lower.contains(".apk") && !lower.contains("google") && !lower.contains("analytics")) {
            if (currentSniffedUrl != url) {
                currentSniffedUrl = url
                Log.i(TAG, "成功嗅探到影片串流網址: $url")
                mainHandler.post {
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
    }

    /**
     * 抓取首頁資料（包含左側邊欄分類與首頁純短劇清單）
     */
    fun scrapeHomeDataAndCategories(
        url: String,
        callback: (List<CategoryItem>, List<DramaItem>) -> Unit
    ) {
        this.onCategoryDramasListener = null
        this.onHomeScrapedListener = callback
        mainHandler.post {
            if (homeWebView == null) initHomeWebView()
            homeWebView?.loadUrl(url)
        }
    }

    /**
     * 載入指定分類頁面並抓取該分類下的短劇清單
     */
    fun loadCategory(
        categoryUrl: String,
        callback: (List<DramaItem>) -> Unit
    ) {
        this.onHomeScrapedListener = null
        this.onCategoryDramasListener = callback
        mainHandler.post {
            if (homeWebView == null) initHomeWebView()
            homeWebView?.loadUrl(categoryUrl)
        }
    }

    /**
     * 開始嗅探詳情頁選集與影片串流
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
            if (detailWebView == null) initDetailWebView()
            detailWebView?.loadUrl(detailUrl)
        }
    }

    /**
     * 切換選集嗅探指定集數連結
     */
    fun sniffEpisodeStream(episodeUrl: String, onStreamFound: (String) -> Unit) {
        this.currentSniffedUrl = null
        this.onStreamFoundListener = onStreamFound

        mainHandler.post {
            if (detailWebView == null) initDetailWebView()
            detailWebView?.loadUrl(episodeUrl)
        }
    }

    /**
     * 注入首頁與分類抓取核心 JS
     * 徹底實施：
     * 1. 黑名單字眼過濾（Telegram、gmail、關於我們、下載、客服等）
     * 2. 「XX部劇集」提取至分類列表，不放入海報牆
     * 3. 精準提取真實劇名（排除排行榜數字、排除「立即播放」等按鈕字眼）
     * 4. 完整提取封面圖（支援 img 多重屬性與 CSS background-image，補全絕對網址）
     */
    private fun injectDataScrapingScript(wv: WebView) {
        val js = """
            (function() {
                // 1. 自動穿透 18 歲年齡確認與關閉通知
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

                // 2. 頁面向下滾動觸發懶載入
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

                    // 轉換絕對路徑
                    function toAbsoluteUrl(url) {
                        if (!url) return '';
                        try {
                            return new URL(url, location.href).href;
                        } catch(e) {
                            return url;
                        }
                    }

                    // 檢查是否為有效封面圖片
                    function isValidImageUrl(url) {
                        if (!url) return false;
                        var lower = url.toLowerCase();
                        if (lower.indexOf('data:image') === 0 && lower.indexOf('base64') !== -1 && lower.length < 200) return false;
                        if (lower.indexOf('logo') !== -1 || lower.indexOf('avatar') !== -1 || lower.indexOf('icon') !== -1) return false;
                        return true;
                    }

                    // 提取元素或子節點背景圖
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

                    // ==========================================
                    // 一、解析左側分類列表 (導航選單 + 合集專題)
                    // ==========================================
                    var categories = [];
                    var visitedCatUrls = {};

                    // 預設第一項：首頁推薦
                    categories.push({
                        id: 'cat_home',
                        title: '首頁推薦',
                        url: location.href,
                        count: ''
                    });
                    visitedCatUrls[location.href] = true;

                    // 1. 導覽列分類 (van-tab, nav a, menu a, category a 等)
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

                    // 2. 提取「XX部劇集 / XX部剧集」合集專題作為分類
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
                                // 提取合集標題（排除數字與部劇集）
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

                    // ==========================================
                    // 二、解析右側海報牆（過濾雜訊，精準提取真實劇名）
                    // ==========================================
                    var dramaResults = [];
                    var visitedDramaUrls = {};

                    var cards = document.querySelectorAll(
                        'a, .van-card, [class*="drama"], [class*="item"], [class*="card"], .video-card, li'
                    );

                    for (var c = 0; c < cards.length; c++) {
                        var card = cards[c];
                        var cardText = (card.innerText || card.textContent || '').trim();

                        // 1. 黑名單排除
                        if (isBlacklisted(cardText)) continue;

                        // 2. 若為「XX部劇集」合集入口，排除於單部劇海報牆外
                        if (/(\d+)\s*部[劇剧]集/.test(cardText) || /共\s*(\d+)\s*部/.test(cardText)) continue;

                        // 提取跳轉連結
                        var aTag = (card.tagName.toUpperCase() === 'A') ? card : card.querySelector('a');
                        var href = aTag ? aTag.href : '';
                        if (!href || href.indexOf('javascript') === 0 || href.indexOf('.apk') !== -1) continue;
                        href = toAbsoluteUrl(href);
                        if (isBlacklisted(href)) continue;
                        if (visitedDramaUrls[href]) continue;

                        // 3. 提取封面圖 (優先 img 多屬性，次選 CSS background-image)
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

                        // 4. 精準提取真實劇名 (過濾純數字、按鈕字眼與集數角標)
                        var title = '';
                        var badge = '';

                        // 搜尋卡片內所有可能帶文字的節點
                        var textNodes = card.querySelectorAll(
                            '.title, [class*="title"], [class*="name"], h1, h2, h3, h4, p, span, div'
                        );
                        var candidateTitles = [];

                        for (var tn = 0; tn < textNodes.length; tn++) {
                            var el = textNodes[tn];
                            if (el.children.length > 2) continue; // 略過大容器
                            var t = (el.innerText || el.textContent || '').trim();
                            if (!t || isBlacklisted(t)) continue;

                            // 檢查是否為角標 (更新至XX集、全XX集、完結)
                            if (/更新至\s*\d+\s*集/.test(t) || /全\s*\d+\s*集/.test(t) || t === '完結' || t === '完结') {
                                if (!badge) badge = t;
                                continue;
                            }

                            // 檢查是否為排名純數字 (如 1, 2, 3...)
                            if (/^\d{1,3}$/.test(t) || /^TOP\s*\d+/i.test(t)) {
                                if (!badge) badge = 'TOP ' + t.replace(/[^0-9]/g, '');
                                continue;
                            }

                            // 檢查是否為按鈕文字
                            if (t === '立即播放' || t === '立即观看' || t === '播放' || t === '观看' || t === '進入網站' || t === '进入网站') {
                                continue;
                            }

                            candidateTitles.push(t);
                        }

                        // 在候選文字中挑選最長且最合理的作為真實劇名
                        candidateTitles.sort(function(a, b) { return b.length - a.length; });
                        if (candidateTitles.length > 0) {
                            title = candidateTitles[0];
                        }

                        // 備援提取：a 標籤 title 或 img alt
                        if (!title && aTag) {
                            var aTitle = (aTag.getAttribute('title') || '').trim();
                            if (aTitle && !isBlacklisted(aTitle)) title = aTitle;
                        }
                        if (!title && img) {
                            var alt = (img.getAttribute('alt') || '').trim();
                            if (alt && !isBlacklisted(alt)) title = alt;
                        }

                        // 再次檢驗：若劇名仍為純數字或按鈕字眼，判定為無效
                        if (!title || /^\d+$/.test(title) || title === '立即播放' || title === '播放') {
                            continue;
                        }

                        // 若沒有圖片且標題太短，可能是頁尾連結，丟棄
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

                    // 回傳給 Kotlin
                    if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onDataScraped) {
                        window.${INTERFACE_NAME}.onDataScraped(
                            JSON.stringify(categories),
                            JSON.stringify(dramaResults)
                        );
                    }
                }, 1000);
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
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

                // 1. 嗅探 DOM 中的 <video> 元素
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

                // 2. 提取分集按鈕與集數連結
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
        wv.evaluateJavascript(js, null)
    }

    /**
     * 首頁抓取 JS Interface
     */
    inner class HomeScraperJsInterface {
        @JavascriptInterface
        fun onDataScraped(categoriesJson: String, dramasJson: String) {
            mainHandler.post {
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

                    // 分發回呼
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
