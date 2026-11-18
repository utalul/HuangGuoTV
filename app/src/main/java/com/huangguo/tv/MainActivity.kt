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
 * 具備完整 32 位元電視盒啟動防崩潰保護與螢幕即時錯誤診斷面板
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Main"
        private const val EXIT_INTERVAL = 2000L
        private const val SPAN_COUNT = 4 // 右側海報牆 4 欄
    }

    // 左側邊欄
    private var tvSidebarLineStatus: TextView? = null
    private var rvCategories: RecyclerView? = null
    private var categoryAdapter: CategoryAdapter? = null

    // 右側主內容區
    private var tvCurrentCategory: TextView? = null
    private var tvCurrentLine: TextView? = null
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
        // 1. 左側邊欄分類列表 (垂直佈局)
        rvCategories?.layoutManager = LinearLayoutManager(this)
        categoryAdapter = CategoryAdapter(
            items = emptyList(),
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
            if (category.id == "cat_home") {
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

        // 2. 首頁上方橫向子分類 Tab 列 (水平佈局)
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

        Log.i(TAG, "開始抓取首頁資料: $targetUrl")
        webScraperBridge?.scrapeHomeDataAndCategories(targetUrl) { categories, subTabs, dramas ->
            layoutLoading?.visibility = View.GONE
            allHomeDramas = dramas

            if (categories.isNotEmpty()) {
                categoryAdapter?.updateData(categories, defaultSelected = 0)
                tvCurrentCategory?.text = categories[0].title
                rvCategories?.post {
                    rvCategories?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }

            if (subTabs.isNotEmpty()) {
                rvSubTabs?.visibility = View.VISIBLE
                subTabAdapter?.updateData(subTabs, defaultSelected = 0)
            }

            if (dramas.isNotEmpty()) {
                Log.i(TAG, "首頁短劇抓取成功，共 ${dramas.size} 部")
                dramaAdapter?.updateData(dramas)
                rvDramaGrid?.post {
                    rvDramaGrid?.scrollToPosition(0)
                }
            } else {
                Log.w(TAG, "首頁短劇列表為空")
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
                    } else {
                        dramaAdapter?.updateData(allHomeDramas)
                    }
                    rvDramaGrid?.scrollToPosition(0)
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
                    } else {
                        Toast.makeText(this, "正在呈現【${subTab.title}】短劇", Toast.LENGTH_SHORT).show()
                        dramaAdapter?.updateData(allHomeDramas)
                    }
                    rvDramaGrid?.scrollToPosition(0)
                }
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
                dramaAdapter?.updateData(dramaList)
                rvDramaGrid?.post {
                    rvDramaGrid?.scrollToPosition(0)
                }
            } else {
                tvLoadingMsg?.text = "【${category.title}】分類暫無資料，請切換其他分類"
                layoutLoading?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 開啟全螢幕播放器
     */
    private fun openPlayer(drama: DramaItem) {
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
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                recreate()
            }
        }

        val btnExit = Button(this).apply {
            text = "關閉應用"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(30, 0, 30, 0)
            setOnClickListener {
                finish()
            }
        }

        btnLayout.addView(btnRetry)
        btnLayout.addView(btnExit)

        rootLayout.addView(tvTitle)
        rootLayout.addView(tvSub)
        rootLayout.addView(scrollView)
        rootLayout.addView(btnLayout)

        setContentView(rootLayout)

        btnRetry.requestFocus()
    }

    /**
     * TV 遙控器焦點聯動 (規格一)
     * - 左側邊欄按「右鍵」無縫移入右側海報牆第一張
     * - 右側海報牆最左欄按「左鍵」返回左側邊欄選中項
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (action == KeyEvent.ACTION_DOWN) {
            val currentFocus = currentFocus

            // 1. 若目前焦點在左側邊欄，按下「右鍵」：焦點轉移至右側海報牆第一張
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (currentFocus != null && isViewInSidebar(currentFocus)) {
                    val firstHolder = rvDramaGrid?.findViewHolderForAdapterPosition(0)
                    if (firstHolder != null) {
                        firstHolder.itemView.requestFocus()
                        return true
                    }
                }
            }

            // 2. 若目前焦點在右側海報牆最左欄 (position % SPAN_COUNT == 0)，按下「左鍵」：焦點返回左側邊欄選中項
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (currentFocus != null && isViewInDramaGrid(currentFocus)) {
                    val pos = rvDramaGrid?.getChildAdapterPosition(currentFocus) ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION && (pos % SPAN_COUNT == 0)) {
                        val selPos = categoryAdapter?.getSelectedIndex() ?: 0
                        val targetHolder = rvCategories?.findViewHolderForAdapterPosition(selPos)
                        if (targetHolder != null) {
                            targetHolder.itemView.requestFocus()
                            return true
                        } else {
                            rvCategories?.requestFocus()
                            return true
                        }
                    }
                }
            }

            // 3. 返回鍵處理
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 如果在右側海報牆，返回鍵優先將焦點帶回左側分類
                if (currentFocus != null && isViewInDramaGrid(currentFocus)) {
                    val selPos = categoryAdapter?.getSelectedIndex() ?: 0
                    rvCategories?.findViewHolderForAdapterPosition(selPos)?.itemView?.requestFocus()
                    return true
                }

                val now = System.currentTimeMillis()
                if (now - exitToastTime < EXIT_INTERVAL) {
                    finish()
                } else {
                    exitToastTime = now
                    Toast.makeText(this, getString(R.string.msg_press_again_exit), Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun isViewInSidebar(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === rvCategories) return true
            parent = parent.parent
        }
        return false
    }

    private fun isViewInDramaGrid(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === rvDramaGrid) return true
            parent = parent.parent
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        webScraperBridge?.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
