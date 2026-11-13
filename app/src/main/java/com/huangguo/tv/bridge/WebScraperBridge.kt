package com.huangguo.tv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import com.huangguo.tv.model.DramaItem
import com.huangguo.tv.model.EpisodeItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 隱藏式無頭網頁抓取與串流嗅探引擎
 * 封裝後台隱藏 WebView，保留 SSL 放行、混合內容、18 歲自動穿透，
 * 並即時抓取海報牆短劇資料及嗅探 .m3u8 / .mp4 串流。
 */
class WebScraperBridge(private val context: Context) {

    companion object {
        private const val TAG = "WebScraperBridge"
        private const val INTERFACE_NAME = "AndroidBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val configManager = ConfigManager.getInstance(context)

    // 專職首頁抓取的無頭 WebView
    private var homeWebView: WebView? = null
    // 專職詳情與選集/串流嗅探的無頭 WebView
    private var detailWebView: WebView? = null

    // 首頁短劇抓取回呼
    private var onHomeDramasScrapedListener: ((List<DramaItem>) -> Unit)? = null

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
                Log.d(TAG, "首頁無頭載入完成: $url，注入抓取腳本")
                injectHomeScrapingScript(wv)
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
                    // 暫停無頭 WebView 內的影片，避免背景發聲
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
     * 開始抓取首頁劇集海報牆清單
     */
    fun scrapeHomeDramas(url: String, callback: (List<DramaItem>) -> Unit) {
        this.onHomeDramasScrapedListener = callback
        mainHandler.post {
            if (homeWebView == null) initHomeWebView()
            homeWebView?.loadUrl(url)
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
     * 注入首頁抓取 JS
     */
    private fun injectHomeScrapingScript(wv: WebView) {
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

                // 2. 滾動頁面觸發圖片與內容懶載入
                window.scrollBy({ top: 1200, behavior: 'instant' });

                setTimeout(function() {
                    autoClickAge();
                    var results = [];
                    var visitedUrls = {};

                    // 遍歷包含圖片與連結的元素
                    var cards = document.querySelectorAll('a, .van-card, [class*="drama"], [class*="item"], [class*="card"], .video-card');
                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        var aTag = (card.tagName.toUpperCase() === 'A') ? card : card.querySelector('a');
                        var href = aTag ? aTag.href : '';
                        if (!href || href.indexOf('javascript') === 0 || href.indexOf('.apk') !== -1) continue;
                        if (visitedUrls[href]) continue;

                        var img = card.querySelector('img');
                        var cover = '';
                        if (img) {
                            cover = img.src || img.getAttribute('data-src') || img.getAttribute('data-original') || img.dataset.src || '';
                        }
                        if (!cover) {
                            var bgEl = card.querySelector('[style*="background"]');
                            if (bgEl) {
                                var match = bgEl.style.backgroundImage.match(/url\(["']?([^"']*)["']?\)/);
                                if (match && match[1]) cover = match[1];
                            }
                        }

                        // 提取短劇標題
                        var title = '';
                        var titleEl = card.querySelector('.title, [class*="title"], h2, h3, h4, p, span');
                        if (titleEl) {
                            title = (titleEl.innerText || titleEl.textContent || '').trim();
                        }
                        if (!title && aTag) {
                            title = (aTag.getAttribute('title') || aTag.innerText || '').trim();
                        }
                        if (!title && img) {
                            title = (img.getAttribute('alt') || '').trim();
                        }

                        // 提取角標 (例如：更新至XX集、全集等)
                        var badge = '';
                        var badgeEl = card.querySelector('[class*="badge"], [class*="tag"], [class*="status"], [class*="episodes"], .van-tag');
                        if (badgeEl) {
                            badge = (badgeEl.innerText || badgeEl.textContent || '').trim();
                        }

                        if (title && href && (cover || card.innerText)) {
                            visitedUrls[href] = true;
                            results.push({
                                id: 'drama_' + results.length,
                                title: title.replace(/\n+/g, ' ').substring(0, 50),
                                coverUrl: cover,
                                detailUrl: href,
                                badge: badge
                            });
                        }
                    }

                    if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onHomeDramasScraped) {
                        window.${INTERFACE_NAME}.onHomeDramasScraped(JSON.stringify(results));
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

                        // 判斷是否為集數（如「第1集」、「1」、「01」等）
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

                    // 排序集數
                    epList.sort(function(a, b) { return a.index - b.index; });

                    // 如果沒有抓到分集，預設提供第 1 集
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

                // 定期輪詢以防 DOM 延遲載入 video 串流
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
        fun onHomeDramasScraped(jsonStr: String) {
            mainHandler.post {
                try {
                    val list = mutableListOf<DramaItem>()
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            DramaItem(
                                id = obj.optString("id", "drama_$i"),
                                title = obj.optString("title", "短劇"),
                                coverUrl = obj.optString("coverUrl", ""),
                                detailUrl = obj.optString("detailUrl", ""),
                                badge = obj.optString("badge", "")
                            )
                        )
                    }
                    Log.i(TAG, "成功解析首頁短劇共 ${list.size} 部")
                    onHomeDramasScrapedListener?.invoke(list)
                } catch (e: Exception) {
                    Log.e(TAG, "解析首頁短劇 JSON 失敗: ${e.message}")
                    onHomeDramasScrapedListener?.invoke(emptyList())
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
