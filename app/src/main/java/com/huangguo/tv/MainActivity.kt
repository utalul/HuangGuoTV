package com.huangguo.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.adapter.CategoryAdapter
import com.huangguo.tv.adapter.DramaAdapter
import com.huangguo.tv.bridge.WebScraperBridge
import com.huangguo.tv.config.ConfigManager
import com.huangguo.tv.model.CategoryItem
import com.huangguo.tv.model.DramaItem
import com.huangguo.tv.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 原生 Android TV 雙欄主畫面 (左側分類邊欄 + 右側海報牆)
 * 徹底過濾廣告與無效頁尾，精準提取真實劇名與完整封面
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Main"
        private const val EXIT_INTERVAL = 2000L
        private const val SPAN_COUNT = 4 // 右側海報牆 4 欄
    }

    // 左側邊欄
    private lateinit var tvSidebarLineStatus: TextView
    private lateinit var rvCategories: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter

    // 右側主內容區
    private lateinit var tvCurrentCategory: TextView
    private lateinit var tvCurrentLine: TextView
    private lateinit var btnReload: Button
    private lateinit var btnSwitchLine: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var rvDramaGrid: RecyclerView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvLoadingMsg: TextView
    private lateinit var tvModeHint: TextView
    private lateinit var dramaAdapter: DramaAdapter

    private lateinit var configManager: ConfigManager
    private lateinit var updateManager: UpdateManager
    private lateinit var webScraperBridge: WebScraperBridge

    private val handler = Handler(Looper.getMainLooper())
    private var exitToastTime = 0L

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
        webScraperBridge = WebScraperBridge(this)

        initViews()
        setupRecyclerViews()
        loadHomeData()

        // 啟動時非同步檢查雲端防失聯配置
        syncCloudConfigOnly()
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
        rvDramaGrid = findViewById(R.id.rvDramaGrid)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingMsg = findViewById(R.id.tvLoadingMsg)
        tvModeHint = findViewById(R.id.tvModeHint)

        updateLineStatusText()

        btnReload.setOnClickListener {
            val currentCat = categoryAdapter.getSelectedItem()
            if (currentCat != null && currentCat.id != "cat_home") {
                loadCategoryData(currentCat)
            } else {
                loadHomeData()
            }
        }

        btnSwitchLine.setOnClickListener {
            showSwitchLineDialog()
        }

        btnCheckUpdate.setOnClickListener {
            updateManager.checkForUpdates(isManual = true)
        }
    }

    private fun setupRecyclerViews() {
        // 1. 左側邊欄分類列表 (垂直佈局)
        rvCategories.layoutManager = LinearLayoutManager(this)
        categoryAdapter = CategoryAdapter(emptyList()) { category, position ->
            tvCurrentCategory.text = category.title
            if (category.id == "cat_home") {
                loadHomeData()
            } else {
                loadCategoryData(category)
            }
        }
        rvCategories.adapter = categoryAdapter

        // 2. 右側短劇海報網格 (4 欄佈局)
        val gridLayoutManager = GridLayoutManager(this, SPAN_COUNT)
        rvDramaGrid.layoutManager = gridLayoutManager

        val currentHost = configManager.getCurrentTargetUrl()
        val userAgent = configManager.getEffectiveUserAgent()
        dramaAdapter = DramaAdapter(
            items = emptyList(),
            currentBaseUrl = currentHost,
            userAgent = userAgent
        ) { dramaItem ->
            openPlayer(dramaItem)
        }
        rvDramaGrid.adapter = dramaAdapter
    }

    private fun updateLineStatusText() {
        val currentUrl = configManager.getCurrentTargetUrl()
        tvSidebarLineStatus.text = currentUrl.replace("https://", "").replace("http://", "")
        tvCurrentLine.text = "目前線路：$currentUrl"

        // 更新圖片請求防盜鏈 Header
        dramaAdapter.currentBaseUrl = currentUrl
        dramaAdapter.userAgent = configManager.getEffectiveUserAgent()
    }

    /**
     * 載入首頁資料與左側分類清單
     */
    private fun loadHomeData() {
        val targetUrl = configManager.getCurrentTargetUrl()
        updateLineStatusText()

        layoutLoading.visibility = View.VISIBLE
        tvLoadingMsg.text = "正在抓取最新分類與短劇清單..."

        Log.i(TAG, "開始抓取首頁資料: $targetUrl")
        webScraperBridge.scrapeHomeDataAndCategories(targetUrl) { categories, dramas ->
            layoutLoading.visibility = View.GONE

            if (categories.isNotEmpty()) {
                categoryAdapter.updateData(categories, defaultSelected = 0)
                tvCurrentCategory.text = categories[0].title
            }

            if (dramas.isNotEmpty()) {
                Log.i(TAG, "首頁短劇抓取成功，共 ${dramas.size} 部")
                dramaAdapter.updateData(dramas)
            } else {
                Log.w(TAG, "首頁短劇列表為空")
                tvLoadingMsg.text = "該分類暫無劇集或載入逾時，請重新整理或切換線路"
                layoutLoading.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 載入特定分類短劇清單
     */
    private fun loadCategoryData(category: CategoryItem) {
        layoutLoading.visibility = View.VISIBLE
        tvLoadingMsg.text = "正在載入【${category.title}】的短劇清單..."

        Log.i(TAG, "載入分類: ${category.title}, url=${category.url}")
        webScraperBridge.loadCategory(category.url) { dramaList ->
            layoutLoading.visibility = View.GONE
            if (dramaList.isNotEmpty()) {
                Log.i(TAG, "分類【${category.title}】抓取成功，共 ${dramaList.size} 部")
                dramaAdapter.updateData(dramaList)
                rvDramaGrid.post {
                    rvDramaGrid.scrollToPosition(0)
                }
            } else {
                tvLoadingMsg.text = "【${category.title}】分類暫無資料，請切換其他分類"
                layoutLoading.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 開啟全螢幕播放器
     */
    private fun openPlayer(drama: DramaItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DRAMA, drama)
        }
        startActivity(intent)
    }

    /**
     * 啟動時非同步更新雲端防失聯線路
     */
    private fun syncCloudConfigOnly() {
        lifecycleScope.launch {
            val updated = configManager.fetchRemoteConfig()
            if (updated) {
                updateLineStatusText()
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

        val items = urls.toTypedArray()
        val checkedItem = urls.indexOf(current).let { if (it >= 0) it else 0 }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_HuangGuoTV)
        builder.setTitle("選擇播放線路")
        builder.setSingleChoiceItems(items, checkedItem) { d, which ->
            val selectedUrl = items[which]
            configManager.setCurrentTargetUrl(selectedUrl)
            d.dismiss()
            showModeHint("已切換至線路: $selectedUrl")
            loadHomeData()
        }
        builder.setNegativeButton("取消", null)
        builder.create().show()
    }

    private fun showModeHint(message: String) {
        tvModeHint.text = message
        tvModeHint.visibility = View.VISIBLE
        tvModeHint.alpha = 1f
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 2500L)
    }

    private val hideHintRunnable = Runnable {
        tvModeHint.animate().alpha(0f).setDuration(350).withEndAction {
            tvModeHint.visibility = View.GONE
        }.start()
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
                    val firstHolder = rvDramaGrid.findViewHolderForAdapterPosition(0)
                    if (firstHolder != null) {
                        firstHolder.itemView.requestFocus()
                        return true
                    }
                }
            }

            // 2. 若目前焦點在右側海報牆最左欄 (position % SPAN_COUNT == 0)，按下「左鍵」：焦點返回左側邊欄選中項
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (currentFocus != null && isViewInDramaGrid(currentFocus)) {
                    val pos = rvDramaGrid.getChildAdapterPosition(currentFocus)
                    if (pos != RecyclerView.NO_POSITION && (pos % SPAN_COUNT == 0)) {
                        val selPos = categoryAdapter.getSelectedIndex()
                        val targetHolder = rvCategories.findViewHolderForAdapterPosition(selPos)
                        if (targetHolder != null) {
                            targetHolder.itemView.requestFocus()
                            return true
                        } else {
                            rvCategories.requestFocus()
                            return true
                        }
                    }
                }
            }

            // 3. 返回鍵處理
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 如果在右側海報牆，返回鍵優先將焦點帶回左側分類
                if (currentFocus != null && isViewInDramaGrid(currentFocus)) {
                    rvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
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
        webScraperBridge.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
