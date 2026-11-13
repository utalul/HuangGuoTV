package com.huangguo.tv

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.adapter.DramaAdapter
import com.huangguo.tv.bridge.WebScraperBridge
import com.huangguo.tv.config.ConfigManager
import com.huangguo.tv.model.DramaItem
import com.huangguo.tv.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 原生 Android TV 海報牆主畫面
 * 徹底告別 WebView 直接渲染與虛擬滑鼠，由無頭 WebScraperBridge 於背景提取資料
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Main"
        private const val EXIT_INTERVAL = 2000L
    }

    private lateinit var tvCurrentLine: TextView
    private lateinit var btnReload: Button
    private lateinit var btnSwitchLine: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var rvDramaGrid: RecyclerView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvLoadingMsg: TextView
    private lateinit var tvModeHint: TextView

    private lateinit var configManager: ConfigManager
    private lateinit var updateManager: UpdateManager
    private lateinit var webScraperBridge: WebScraperBridge
    private lateinit var dramaAdapter: DramaAdapter

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
        setupRecyclerView()
        loadHomeData()

        // 啟動時非同步檢查雲端防失聯配置
        syncCloudConfigOnly()
    }

    private fun initViews() {
        tvCurrentLine = findViewById(R.id.tvCurrentLine)
        btnReload = findViewById(R.id.btnReload)
        btnSwitchLine = findViewById(R.id.btnSwitchLine)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        rvDramaGrid = findViewById(R.id.rvDramaGrid)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingMsg = findViewById(R.id.tvLoadingMsg)
        tvModeHint = findViewById(R.id.tvModeHint)

        updateCurrentLineText()

        btnReload.setOnClickListener {
            loadHomeData()
        }

        btnSwitchLine.setOnClickListener {
            showSwitchLineDialog()
        }

        btnCheckUpdate.setOnClickListener {
            updateManager.checkForUpdates(isManual = true)
        }
    }

    private fun setupRecyclerView() {
        // 設定 5 欄網格佈局
        val gridLayoutManager = GridLayoutManager(this, 5)
        rvDramaGrid.layoutManager = gridLayoutManager

        dramaAdapter = DramaAdapter(emptyList()) { dramaItem ->
            openPlayer(dramaItem)
        }
        rvDramaGrid.adapter = dramaAdapter
    }

    private fun updateCurrentLineText() {
        val currentUrl = configManager.getCurrentTargetUrl()
        tvCurrentLine.text = "目前線路：$currentUrl"
    }

    /**
     * 呼叫無頭抓取引擎拉取短劇海報清單
     */
    private fun loadHomeData() {
        val targetUrl = configManager.getCurrentTargetUrl()
        updateCurrentLineText()

        layoutLoading.visibility = View.VISIBLE
        tvLoadingMsg.text = "正在載入最新短劇列表，請稍候..."

        Log.i(TAG, "開始透過無頭引擎抓取首頁資料: $targetUrl")
        webScraperBridge.scrapeHomeDramas(targetUrl) { list ->
            layoutLoading.visibility = View.GONE
            if (list.isNotEmpty()) {
                Log.i(TAG, "首頁資料抓取成功，共 ${list.size} 筆短劇")
                dramaAdapter.updateData(list)
                // 聚焦到網格第一項
                rvDramaGrid.post {
                    rvDramaGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            } else {
                Log.w(TAG, "首頁短劇列表抓取為空")
                tvLoadingMsg.text = "目前線路暫無資料或載入逾時，請按「切換線路」重試"
                layoutLoading.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 跳轉至原生 ExoPlayer 播放器
     */
    private fun openPlayer(drama: DramaItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DRAMA, drama)
        }
        startActivity(intent)
    }

    /**
     * 啟動時僅同步雲端配置熱更新，不在啟動時彈出更新視窗
     */
    private fun syncCloudConfigOnly() {
        lifecycleScope.launch {
            val updated = configManager.fetchRemoteConfig()
            if (updated) {
                updateCurrentLineText()
            }
        }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - exitToastTime < EXIT_INTERVAL) {
                finish()
            } else {
                exitToastTime = now
                Toast.makeText(this, getString(R.string.msg_press_again_exit), Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        webScraperBridge.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
