package com.huangguo.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.adapter.CategoryAdapter
import com.huangguo.tv.adapter.DramaAdapter
import com.huangguo.tv.adapter.SubTabAdapter
import com.huangguo.tv.bridge.WebScraperBridge
import com.huangguo.tv.config.ConfigManager
import com.huangguo.tv.model.CategoryItem
import com.huangguo.tv.model.DramaItem
import com.huangguo.tv.model.SubTabItem
import com.huangguo.tv.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 原生 Android TV 雙欄主畫面 (左側分類邊欄 + 右側上方子分類 Tab 列 + 海報牆)
 * 具備完整 32 位元電視盒啟動防崩潰保護與螢幕即時錯誤診斷面板。
 * 左側邊欄不依賴網頁抓取結果，啟動即填入預設大分類，永遠穩定顯示。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Main"
        private const val EXIT_INTERVAL = 2000L
        private const val SPAN_COUNT = 4 // 右側海報牆 4 欄

        // 預設左側大分類清單（啟動立即填入，確保邊欄永遠穩定存在）
        val DEFAULT_CATEGORIES = listOf(
            CategoryItem("cat_home", "首頁推薦", "/"),
            CategoryItem("cat_orig", "黃果原創", "/original"),
            CategoryItem("cat_short", "短劇", "/short"),
            CategoryItem("cat_comic", "漫劇", "/comic"),
            CategoryItem("cat_topic", "專題", "/topic"),
            CategoryItem("cat_rank", "排行榜", "/rank"),
            CategoryItem("cat_blog", "博客", "/blog"),
            CategoryItem("cat_gossip", "黑料吃瓜", "/gossip")
        )

        // 預設首頁頂部橫向子分類（四大板塊 + 三大專題推薦 + 二十個熱門標籤）
        val DEFAULT_SUB_TABS = listOf(
            SubTabItem("tab_all", "全部精選", "", SubTabItem.Type.SECTION),
            SubTabItem("tab_featured", "精選推薦", "", SubTabItem.Type.SECTION),
            SubTabItem("tab_latest", "最近上新", "", SubTabItem.Type.SECTION),
            SubTabItem("tab_hot", "熱播短劇", "", SubTabItem.Type.SECTION),
            SubTabItem("tab_topic_81", "81部精品高分", "", SubTabItem.Type.TOPIC),
            SubTabItem("tab_topic_68", "68部家庭倫理", "", SubTabItem.Type.TOPIC),
            SubTabItem("tab_topic_100", "100部天菜男同", "", SubTabItem.Type.TOPIC),
            SubTabItem("tab_tag_city", "都市", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_gufeng", "古風", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_fantasy", "奇幻", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_modern", "現代", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_work", "職場", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_counter", "逆襲", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_campus", "校園", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_green", "綠帽", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_mature", "熟女", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_mother", "母子", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_incest", "亂倫", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_system", "系統", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_xianxia", "仙俠", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_harem", "后宮", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_hero", "大男主", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_gender", "性轉", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_hole", "腦洞", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_suspense", "懸疑", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_ntr", "NTR", "", SubTabItem.Type.TAG),
            SubTabItem("tab_tag_wuxia", "武俠", "", SubTabItem.Type.TAG)
        )
    }

    // 左側邊欄
    private var tvSidebarLineStatus: TextView? = null
    private var rvCategories: RecyclerView? = null
    private var categoryAdapter: CategoryAdapter? = null

    // 右側主內容區
    private var tvCurrentCategory: TextView? = null
    private var tvCurrentLine: TextView? = null
    private var tvDiagnosticInfo: TextView? = null
    private var btnReload: Button? = null
    private var btnSwitchLine: Button? = null
    private var btnCheckUpdate: Button? = null
    private var rvSubTabs: RecyclerView? = null
    private var subTabAdapter: SubTabAdapter? = null
    private var rvDramaGrid: RecyclerView? = null
    private var layoutLoading: LinearLayout? = null
    private var tvLoadingMsg: TextView? = null
    private var tvModeHint: TextView? = null
    private var dramaAdapter: DramaAdapter? = null

    // 首頁抓取到的完整短劇快取 (用於頂部 Tab 即時過濾)
    private var allHomeDramas: List<DramaItem> = emptyList()

    private lateinit var configManager: ConfigManager
    private var updateManager: UpdateManager? = null
    private var webScraperBridge: WebScraperBridge? = null

    private val handler = Handler(Looper.getMainLooper())
    private var exitToastTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 啟動最外層防崩潰保護，若有任何未預期例外直接在電視畫面上顯示錯誤堆疊
        try {
            // 保持全螢幕與螢幕常亮
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            setContentView(R.layout.activity_main)

            configManager = ConfigManager.getInstance(this)
            updateManager = UpdateManager(this)
            webScraperBridge = WebScraperBridge(this).apply {
                setOnTargetUrlResolvedListener { resolvedUrl ->
                    updateLineStatusText()
                    showModeHint("已由發布頁自動跳轉至官方地址：$resolvedUrl")
                }
            }

            initViews()
            setupRecyclerViews()
            updateLineStatusText() // 確保在 RecyclerViews 與 Adapter 初始化完成後才調用
            loadHomeData()

            // 啟動時非同步檢查雲端防失聯配置
            syncCloudConfigOnly()
        } catch (t: Throwable) {
            Log.e(TAG, "MainActivity 啟動嚴重異常: ${t.message}", t)
            showCrashDiagnosticScreen(t)
        }
    }

    private fun initViews() {
        // 左側邊欄
        tvSidebarLineStatus = findViewById(R.id.tvSidebarLineStatus)
        rvCategories = findViewById(R.id.rvCategories)

        // 右側主內容區
        tvCurrentCategory = findViewById(R.id.tvCurrentCategory)
        tvCurrentLine = findViewById(R.id.tvCurrentLine)
        tvDiagnosticInfo = findViewById(R.id.tvDiagnosticInfo)
        btnReload = findViewById(R.id.btnReload)
        btnSwitchLine = findViewById(R.id.btnSwitchLine)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        rvSubTabs = findViewById(R.id.rvSubTabs)
        rvDramaGrid = findViewById(R.id.rvDramaGrid)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingMsg = findViewById(R.id.tvLoadingMsg)
        tvModeHint = findViewById(R.id.tvModeHint)

        btnReload?.setOnClickListener {
            val currentCat = categoryAdapter?.getSelectedItem()
            if (currentCat != null && currentCat.id != "cat_home") {
                loadCategoryData(currentCat)
            } else {
                loadHomeData()
            }
        }

        btnSwitchLine?.setOnClickListener {
            showSwitchLineDialog()
        }

        btnCheckUpdate?.setOnClickListener {
            updateManager?.checkForUpdates(isManual = true)
        }
    }

    private fun setupRecyclerViews() {
        // 1. 左側邊欄分類列表 (垂直佈局，立即使用 DEFAULT_CATEGORIES 填入，不依賴網頁抓取結果)
        rvCategories?.layoutManager = LinearLayoutManager(this)
        categoryAdapter = CategoryAdapter(
            items = DEFAULT_CATEGORIES,
            selectedIndex = 0,
            onKeyRight = {
                // 左側邊欄按「右鍵」優先進入上方子分類 Tab 列；若無子分類則進入海報牆
                if (rvSubTabs?.visibility == View.VISIBLE && (subTabAdapter?.itemCount ?: 0) > 0) {
                    val pos = subTabAdapter?.getSelectedIndex() ?: 0
                    rvSubTabs?.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                        ?: rvSubTabs?.requestFocus()
                } else {
                    rvDramaGrid?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        ?: rvDramaGrid?.requestFocus()
                }
            }
        ) { category, position ->
            tvCurrentCategory?.text = category.title
            if (position == 0) {
                rvSubTabs?.visibility = View.VISIBLE
                if (allHomeDramas.isNotEmpty()) {
                    dramaAdapter?.updateData(allHomeDramas)
                    rvDramaGrid?.scrollToPosition(0)
                } else {
                    loadHomeData()
                }
            } else {
                rvSubTabs?.visibility = View.GONE
                loadCategoryData(category)
            }
        }
        rvCategories?.adapter = categoryAdapter
        tvCurrentCategory?.text = DEFAULT_CATEGORIES[0].title

        // 2. 首頁上方橫向子分類 Tab 列 (水平佈局，立即填入預設子分類)
        rvSubTabs?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        subTabAdapter = SubTabAdapter(
            items = emptyList(),
            onKeyDown = {
                // 上方 Tab 列按「下鍵」進入海報牆第一張
                rvDramaGrid?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rvDramaGrid?.requestFocus()
            },
            onKeyLeftEdge = {
                // 上方 Tab 第 0 項按「左鍵」回到左側邊欄當前選中項
                val catIndex = categoryAdapter?.getSelectedIndex() ?: 0
                rvCategories?.findViewHolderForAdapterPosition(catIndex)?.itemView?.requestFocus()
                    ?: rvCategories?.requestFocus()
            }
        ) { subTab, position ->
            onSubTabClicked(subTab)
        }
        rvSubTabs?.adapter = subTabAdapter
        subTabAdapter?.updateData(DEFAULT_SUB_TABS, defaultSelected = 0)
        rvSubTabs?.visibility = View.VISIBLE

        // 3. 右側短劇海報網格 (4 欄佈局)
        val gridLayoutManager = GridLayoutManager(this, SPAN_COUNT)
        rvDramaGrid?.layoutManager = gridLayoutManager

        val currentHost = configManager.getCurrentTargetUrl()
        val userAgent = configManager.getEffectiveUserAgent()
        dramaAdapter = DramaAdapter(
            items = emptyList(),
            currentBaseUrl = currentHost,
            userAgent = userAgent,
            onKeyUpEdge = {
                // 海報網格第一排按「上鍵」回到上方子分類 Tab 列
                if (rvSubTabs?.visibility == View.VISIBLE && (subTabAdapter?.itemCount ?: 0) > 0) {
                    val tabPos = subTabAdapter?.getSelectedIndex() ?: 0
                    rvSubTabs?.findViewHolderForAdapterPosition(tabPos)?.itemView?.requestFocus()
                        ?: rvSubTabs?.requestFocus()
                }
            },
            onKeyLeftEdge = {
                // 最左欄按「左鍵」返回左側邊欄
                val catIndex = categoryAdapter?.getSelectedIndex() ?: 0
                rvCategories?.findViewHolderForAdapterPosition(catIndex)?.itemView?.requestFocus()
                    ?: rvCategories?.requestFocus()
            },
            onFirstImageError = { errorMsg ->
                runOnUiThread {
                    val currentText = tvDiagnosticInfo?.text?.toString() ?: ""
                    tvDiagnosticInfo?.text = "首圖載入失敗: $errorMsg | $currentText"
                }
            }
        ) { dramaItem ->
            openPlayer(dramaItem)
        }
        rvDramaGrid?.adapter = dramaAdapter
    }

    private fun updateLineStatusText() {
        val currentUrl = configManager.getCurrentTargetUrl()
        val shortHost = currentUrl.replace("https://", "").replace("http://", "")
        tvSidebarLineStatus?.text = shortHost
        tvCurrentLine?.text = "目前線路：$currentUrl"

        // 安全更新圖片請求防盜鏈 Header
        dramaAdapter?.let { adapter ->
            adapter.currentBaseUrl = currentUrl
            adapter.userAgent = configManager.getEffectiveUserAgent()
        }
    }

    /**
     * 載入首頁資料、左側分類清單與上方橫向子分類 Tab
     */
    private fun loadHomeData() {
        val targetUrl = configManager.getCurrentTargetUrl()
        updateLineStatusText()

        layoutLoading?.visibility = View.VISIBLE
        tvLoadingMsg?.text = "正在抓取最新分類與短劇清單..."
        tvDiagnosticInfo?.text = "正在連線抓取中..."

        Log.i(TAG, "開始抓取首頁資料: $targetUrl")
        webScraperBridge?.scrapeHomeDataAndCategories(targetUrl) { categories, subTabs, dramas ->
            layoutLoading?.visibility = View.GONE
            allHomeDramas = dramas

            // 若網頁成功解析出包含真實連結的分類，且非空，則進行增量更新；否則維持預設分類，保證邊欄永遠存在
            if (categories.isNotEmpty()) {
                val currentSelected = categoryAdapter?.getSelectedIndex() ?: 0
                categoryAdapter?.updateData(categories, defaultSelected = currentSelected)
                tvCurrentCategory?.text = categories.getOrNull(currentSelected)?.title ?: "首頁推薦"
                rvCategories?.post {
                    rvCategories?.findViewHolderForAdapterPosition(currentSelected)?.itemView?.requestFocus()
                }
            }

            // 合併動態抓取的 subTabs 與預設清單
            val mergedSubTabs = if (subTabs.isNotEmpty()) {
                val list = subTabs.toMutableList()
                val existingTitles = subTabs.map { it.title }.toSet()
                for (defaultTab in DEFAULT_SUB_TABS) {
                    if (!existingTitles.contains(defaultTab.title)) {
                        list.add(defaultTab)
                    }
                }
                list
            } else {
                DEFAULT_SUB_TABS
            }
            rvSubTabs?.visibility = View.VISIBLE
            subTabAdapter?.updateData(mergedSubTabs, defaultSelected = 0)

            if (dramas.isNotEmpty()) {
                Log.i(TAG, "首頁短劇抓取成功，共 ${dramas.size} 部")
                val first = dramas.first()
                val shortCover = formatCoverDiagnostic(first)
                val shortDetail = if (first.detailUrl.length > 35) first.detailUrl.take(35) + "..." else first.detailUrl
                tvDiagnosticInfo?.text = "已抓 ${dramas.size} 部 | 圖: $shortCover | 連: $shortDetail"

                dramaAdapter?.updateData(dramas)
                rvDramaGrid?.post {
                    rvDramaGrid?.scrollToPosition(0)
                }
            } else {
                Log.w(TAG, "首頁短劇列表為空")
                tvDiagnosticInfo?.text = "抓取 0 部 | 請檢查線路或點擊重新整理"
                if (webScraperBridge?.isWebViewSupported == false) {
                    tvLoadingMsg?.text = WebScraperBridge.MSG_MISSING_WEBVIEW
                } else {
                    tvLoadingMsg?.text = "該分類暫無劇集或載入逾時，請重新整理或切換線路"
                }
                layoutLoading?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 點選上方橫向子分類 Tab (板塊篩選、專題推薦或熱門標籤)
     */
    private fun onSubTabClicked(subTab: SubTabItem) {
        Log.i(TAG, "點擊上方子分類 Tab: ${subTab.title}, type=${subTab.type}, url=${subTab.url}")
        when (subTab.type) {
            SubTabItem.Type.SECTION -> {
                if (subTab.title == "全部精選") {
                    dramaAdapter?.updateData(allHomeDramas)
                } else {
                    val filtered = allHomeDramas.filter {
                        it.section.contains(subTab.title) || it.title.contains(subTab.title)
                    }
                    if (filtered.isNotEmpty()) {
                        dramaAdapter?.updateData(filtered)
                    } else {
                        dramaAdapter?.updateData(allHomeDramas)
                    }
                }
                rvDramaGrid?.scrollToPosition(0)
            }
            SubTabItem.Type.TOPIC -> {
                if (subTab.url.isNotEmpty()) {
                    loadCategoryData(CategoryItem(subTab.id, subTab.title, subTab.url))
                } else {
                    val cleanKey = subTab.title.replace(Regex("\\d+部"), "").trim()
                    val filtered = allHomeDramas.filter {
                        it.title.contains(cleanKey) || it.section.contains(cleanKey) || it.badge.contains(cleanKey)
                    }
                    if (filtered.isNotEmpty()) {
                        dramaAdapter?.updateData(filtered)
                        rvDramaGrid?.scrollToPosition(0)
                    } else {
                        loadTagOrKeywordOnline(subTab.title)
                    }
                }
            }
            SubTabItem.Type.TAG -> {
                if (subTab.url.isNotEmpty()) {
                    loadCategoryData(CategoryItem(subTab.id, subTab.title, subTab.url))
                } else {
                    val filtered = allHomeDramas.filter {
                        it.title.contains(subTab.title) || it.tag.contains(subTab.title) || it.badge.contains(subTab.title)
                    }
                    if (filtered.isNotEmpty()) {
                        dramaAdapter?.updateData(filtered)
                        rvDramaGrid?.scrollToPosition(0)
                    } else {
                        loadTagOrKeywordOnline(subTab.title)
                    }
                }
            }
        }
    }

    private fun formatCoverDiagnostic(first: DramaItem): String {
        return if (first.coverUrl.isNotEmpty()) {
            if (first.coverUrl.length > 45) first.coverUrl.take(45) + "..." else first.coverUrl
        } else {
            val debug = if (first.tag.isNotEmpty()) first.tag.take(50) else "無圖片標籤"
            "空(HTML:$debug)"
        }
    }

    /**
     * 本地快取無匹配時，透過無頭 WebView 點擊網頁上的分類/標籤或跳轉加載對應分類頁
     */
    private fun loadTagOrKeywordOnline(keyword: String) {
        layoutLoading?.visibility = View.VISIBLE
        tvLoadingMsg?.text = "正在載入【$keyword】相關短劇..."
        webScraperBridge?.clickNavOrTag(keyword) { dramas ->
            layoutLoading?.visibility = View.GONE
            if (dramas.isNotEmpty()) {
                val first = dramas.first()
                val shortCover = formatCoverDiagnostic(first)
                val shortDetail = if (first.detailUrl.length > 35) first.detailUrl.take(35) + "..." else first.detailUrl
                tvDiagnosticInfo?.text = "【$keyword】已抓 ${dramas.size} 部 | 圖: $shortCover | 連: $shortDetail"

                dramaAdapter?.updateData(dramas)
                rvDramaGrid?.scrollToPosition(0)
            } else {
                Toast.makeText(this, "未找到【$keyword】專屬劇集，顯示精選短劇", Toast.LENGTH_SHORT).show()
                dramaAdapter?.updateData(allHomeDramas)
                rvDramaGrid?.scrollToPosition(0)
            }
        }
    }

    /**
     * 載入特定分類短劇清單
     */
    private fun loadCategoryData(category: CategoryItem) {
        rvSubTabs?.visibility = View.GONE
        layoutLoading?.visibility = View.VISIBLE
        tvLoadingMsg?.text = "正在載入【${category.title}】的短劇清單..."

        Log.i(TAG, "載入分類: ${category.title}, url=${category.url}")
        webScraperBridge?.loadCategory(category.url) { dramaList ->
            layoutLoading?.visibility = View.GONE
            if (dramaList.isNotEmpty()) {
                Log.i(TAG, "分類【${category.title}】抓取成功，共 ${dramaList.size} 部")
                val first = dramaList.first()
                val shortCover = formatCoverDiagnostic(first)
                val shortDetail = if (first.detailUrl.length > 35) first.detailUrl.take(35) + "..." else first.detailUrl
                tvDiagnosticInfo?.text = "【${category.title}】已抓 ${dramaList.size} 部 | 圖: $shortCover | 連: $shortDetail"

                dramaAdapter?.updateData(dramaList)
                rvDramaGrid?.post {
                    rvDramaGrid?.scrollToPosition(0)
                }
            } else {
                tvDiagnosticInfo?.text = "【${category.title}】暫無資料"
                tvLoadingMsg?.text = "【${category.title}】分類暫無資料，請切換其他分類"
                layoutLoading?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 開啟全螢幕播放器
     */
    private fun openPlayer(drama: DramaItem) {
        Log.i(TAG, "點擊短劇卡片: [${drama.title}], detailUrl=${drama.detailUrl}")
        try {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_DRAMA, drama)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "啟動播放器失敗: ${t.message}", t)
            Toast.makeText(this, "啟動播放器失敗: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 啟動時非同步更新雲端防失聯線路
     */
    private fun syncCloudConfigOnly() {
        lifecycleScope.launch {
            try {
                val updated = configManager.fetchRemoteConfig()
                if (updated) {
                    updateLineStatusText()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "同步雲端線路異常: ${t.message}")
            }
        }
    }

    /**
     * 切換播放線路對話框
     */
    private fun showSwitchLineDialog() {
        val config = configManager.getConfig()
        val urls = config.backupUrls
        val current = configManager.getCurrentTargetUrl()

        val displayNames = urls.map { url ->
            when {
                url.contains("huangguoai.com") -> "$url (官方永久域名)"
                url.contains("gitiecgad.cc") -> "$url (最新直連地址)"
                url.contains("hgai12.com") -> "$url (最新中轉頁)"
                url.contains("huangguoai.pages.dev") -> "$url (CF 防封發布頁 1)"
                url.contains("huang-guo.pages.dev") -> "$url (CF 防封發布頁 2)"
                else -> url
            }
        }.toTypedArray()

        var selectedIndex = urls.indexOf(current).let { if (it >= 0) it else 0 }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_HuangGuoTV)
        builder.setTitle("選擇播放線路")
        builder.setSingleChoiceItems(displayNames, selectedIndex) { d, which ->
            selectedIndex = which
            val selectedUrl = urls[which]
            switchLineTo(selectedUrl)
            d.dismiss()
        }
        builder.setPositiveButton("確定切換") { d, _ ->
            val selectedUrl = urls.getOrNull(selectedIndex) ?: current
            switchLineTo(selectedUrl)
            d.dismiss()
        }
        builder.setNegativeButton("取消", null)
        builder.create().show()
    }

    private fun switchLineTo(selectedUrl: String) {
        Log.i(TAG, "使用者切換線路至: $selectedUrl")
        // 1. 立即儲存新線路
        configManager.setCurrentTargetUrl(selectedUrl)

        // 2. 立即更新左側邊欄與頂部狀態文字
        updateLineStatusText()

        // 3. 顯示正在切換至新線路提示，並清空目前清單
        showModeHint("正在切換至新線路: $selectedUrl")
        layoutLoading?.visibility = View.VISIBLE
        tvLoadingMsg?.text = "正在切換至【$selectedUrl】並抓取短劇..."
        tvDiagnosticInfo?.text = "正在切換線路至: $selectedUrl"

        allHomeDramas = emptyList()
        dramaAdapter?.updateData(emptyList())

        // 4. 立刻呼叫 webScraperBridge 重新載入該 selectedUrl 並刷新海報牆
        loadHomeData()
    }

    private fun showModeHint(message: String) {
        tvModeHint?.let { hintView ->
            hintView.text = message
            hintView.visibility = View.VISIBLE
            hintView.alpha = 1f
            handler.removeCallbacks(hideHintRunnable)
            handler.postDelayed(hideHintRunnable, 2500L)
        }
    }

    private val hideHintRunnable = Runnable {
        tvModeHint?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            tvModeHint?.visibility = View.GONE
        }?.start()
    }

    /**
     * 電視全螢幕即時錯誤診斷面板 (規格 3：防閃退與畫面錯誤顯示保護)
     * 若啟動發生任何崩潰，直接在電視螢幕上以紅黑面板列出完整堆疊資訊
     */
    private fun showCrashDiagnosticScreen(throwable: Throwable) {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A0000"))
            setPadding(40, 40, 40, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val tvTitle = TextView(this).apply {
            text = "⚠️ 黃果短劇 TV 啟動異常保護面板"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }

        val tvSub = TextView(this).apply {
            text = "應用啟動過程中發生未處理異常，已自動攔截防止黑屏閃退。請將下方錯誤訊息拍照回報："
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val scrollView = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(20, 20, 20, 20)
        }

        val hScrollView = HorizontalScrollView(this)
        val tvLog = TextView(this).apply {
            text = Log.getStackTraceString(throwable)
            setTextColor(Color.parseColor("#FFD600"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        hScrollView.addView(tvLog)
        scrollView.addView(hScrollView)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 20, 0, 0)
        }

        val btnRetry = Button(this).apply {
            text = "重新嘗試啟動"
            setBackgroundColor(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                recreate()
            }
        }
        btnLayout.addView(btnRetry)

        rootLayout.addView(tvTitle)
        rootLayout.addView(tvSub)
        rootLayout.addView(scrollView)
        rootLayout.addView(btnLayout)

        setContentView(rootLayout)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            if (now - exitToastTime > EXIT_INTERVAL) {
                exitToastTime = now
                Toast.makeText(this, "再按一次返回鍵退出 黃果短劇 TV", Toast.LENGTH_SHORT).show()
                return true
            } else {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webScraperBridge?.destroy()
    }
}
