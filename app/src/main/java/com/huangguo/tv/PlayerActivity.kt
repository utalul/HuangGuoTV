package com.huangguo.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.adapter.EpisodeAdapter
import com.huangguo.tv.bridge.WebScraperBridge
import com.huangguo.tv.config.ConfigManager
import com.huangguo.tv.model.DramaItem
import com.huangguo.tv.model.EpisodeItem
import kotlin.math.max
import kotlin.math.min

/**
 * 原生全螢幕 Android TV 影音播放器
 * 基於 AndroidX Media3 (ExoPlayer)，支援 HLS (.m3u8) / MP4，
 * 並配置解碼器自動降級 (Decoder Fallback)、重試次數保護、iframe 內嵌嗅探與 12 秒逾時直接網頁播放機制。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Player"
        const val EXTRA_DRAMA = "extra_drama"
        private const val CONTROLLER_TIMEOUT = 5000L
        private const val SEEK_STEP_MS = 10000L // 10 秒快進/快退
        private const val MAX_STREAM_RETRY = 2  // 防止播放失敗無限重試死循環
        private const val SNIFF_TIMEOUT_MS = 12000L // 12 秒嗅探保護
    }

    private lateinit var playerView: PlayerView
    private lateinit var layoutController: RelativeLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerEpisode: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var playerProgressBar: ProgressBar

    private lateinit var layoutPlayerLoading: LinearLayout
    private lateinit var tvPlayerLoadingStatus: TextView
    private lateinit var tvPlayerLoadingUrl: TextView
    private lateinit var btnDirectWebPlay: Button
    private lateinit var layoutWebViewContainer: FrameLayout

    private lateinit var layoutEpisodeSheet: RelativeLayout
    private lateinit var rvEpisodeGrid: RecyclerView
    private lateinit var tvPlayerHint: TextView

    private var exoPlayer: ExoPlayer? = null
    private lateinit var webScraperBridge: WebScraperBridge
    private lateinit var configManager: ConfigManager

    private var currentDrama: DramaItem? = null
    private var episodeList: List<EpisodeItem> = emptyList()
    private var currentEpisodeIndex = 0
    private var streamRetryCount = 0 // 重試計數器

    private val handler = Handler(Looper.getMainLooper())

    // 12 秒嗅探超時保護：提示並顯示直接開啟網頁播放按鈕
    private val sniffTimeoutRunnable = Runnable {
        if (exoPlayer?.isPlaying != true) {
            layoutPlayerLoading.visibility = View.VISIBLE
            tvPlayerLoadingStatus.text = "嗅探串流逾時 (12s)，可點擊下方直接開啟網頁播放"
            btnDirectWebPlay.visibility = View.VISIBLE
            btnDirectWebPlay.requestFocus()
        }
    }

    // 長按確認鍵呼出選集
    private var isCenterLongPressTriggered = false
    private val centerLongPressRunnable = Runnable {
        isCenterLongPressTriggered = true
        showEpisodeSheet(true)
    }

    // 控制列自動隱藏計時
    private val hideControllerRunnable = Runnable {
        hideController()
    }

    // 播放進度定期更新
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.isPlaying) {
                val current = player.currentPosition
                val duration = player.duration
                if (duration > 0) {
                    playerProgressBar.max = 1000
                    playerProgressBar.progress = ((current * 1000) / duration).toInt()
                    tvCurrentTime.text = formatTime(current)
                    tvTotalTime.text = formatTime(duration)
                } else {
                    tvCurrentTime.text = formatTime(current)
                    tvTotalTime.text = "--:--"
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // 保持全螢幕與常亮
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            setContentView(R.layout.activity_player)

            configManager = ConfigManager.getInstance(this)
            webScraperBridge = WebScraperBridge(this)

            @Suppress("DEPRECATION")
            currentDrama = intent.getSerializableExtra(EXTRA_DRAMA) as? DramaItem
            if (currentDrama == null) {
                Toast.makeText(this, "無法讀取短劇資料", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            initViews()
            initExoPlayer()
            setupEpisodeRecyclerView()
            startLoadingDrama()
        } catch (t: Throwable) {
            Log.e(TAG, "PlayerActivity 啟動異常: ${t.message}", t)
            Toast.makeText(this, "播放器啟動失敗: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        layoutController = findViewById(R.id.layoutController)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvPlayerEpisode = findViewById(R.id.tvPlayerEpisode)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        playerProgressBar = findViewById(R.id.playerProgressBar)

        layoutPlayerLoading = findViewById(R.id.layoutPlayerLoading)
        tvPlayerLoadingStatus = findViewById(R.id.tvPlayerLoadingStatus)
        tvPlayerLoadingUrl = findViewById(R.id.tvPlayerLoadingUrl)
        btnDirectWebPlay = findViewById(R.id.btnDirectWebPlay)
        layoutWebViewContainer = findViewById(R.id.layoutWebViewContainer)

        layoutEpisodeSheet = findViewById(R.id.layoutEpisodeSheet)
        rvEpisodeGrid = findViewById(R.id.rvEpisodeGrid)
        tvPlayerHint = findViewById(R.id.tvPlayerHint)

        tvPlayerTitle.text = currentDrama?.title ?: "短劇播放"
        tvPlayerEpisode.text = "正在載入第 1 集..."

        btnDirectWebPlay.setOnClickListener {
            showDirectWebPlay()
        }
    }

    /**
     * 直接在全螢幕掛載無頭 WebView 進行網頁播放 / 介面診斷
     */
    private fun showDirectWebPlay() {
        handler.removeCallbacks(sniffTimeoutRunnable)
        layoutPlayerLoading.visibility = View.GONE
        layoutWebViewContainer.visibility = View.VISIBLE

        val wv = webScraperBridge.getDetailWebView()
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            layoutWebViewContainer.removeAllViews()
            layoutWebViewContainer.addView(
                wv,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            wv.visibility = View.VISIBLE
            wv.requestFocus()
            showHint("已切換為網頁檢視模式，按 [返回鍵] 退出")
        } else {
            Toast.makeText(this, "網頁視圖未就緒", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 初始化 ExoPlayer：開啟解碼器自動降級 (Decoder Fallback) 並配置自訂防盜鏈 HTTP Header
     */
    private fun initExoPlayer() {
        val userAgent = configManager.getEffectiveUserAgent()
        val currentHost = configManager.getCurrentTargetUrl()

        // 注入與 WebView 完全一致的 User-Agent 與 Referer，穿透防盜鏈
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to currentHost))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        // 開啟解碼器自動降級 (Decoder Fallback)，提升 32 位元電視晶片特殊編碼相容性
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
        }

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                streamRetryCount = 0 // 播放就緒時歸零重試計數
                                handler.removeCallbacks(sniffTimeoutRunnable)
                                layoutPlayerLoading.visibility = View.GONE
                                btnDirectWebPlay.visibility = View.GONE
                                if (duration > 0) {
                                    tvTotalTime.text = formatTime(duration)
                                } else {
                                    tvTotalTime.text = "--:--"
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                layoutPlayerLoading.visibility = View.VISIBLE
                                tvPlayerLoadingStatus.text = "影片緩衝中..."
                            }
                            Player.STATE_ENDED -> {
                                showHint("即將為您自動播放下一集")
                                playNextEpisode()
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer 播放錯誤: ${error.message}, code=${error.errorCodeName}")
                        if (streamRetryCount < MAX_STREAM_RETRY) {
                            streamRetryCount++
                            layoutPlayerLoading.visibility = View.VISIBLE
                            tvPlayerLoadingStatus.text = "播放失敗，正在重新嗅探備援串流 ($streamRetryCount/$MAX_STREAM_RETRY)..."
                            retryCurrentEpisode()
                        } else {
                            layoutPlayerLoading.visibility = View.VISIBLE
                            tvPlayerLoadingStatus.text = "影片播放失敗: ${error.errorCodeName}，按返回鍵更換線路"
                            btnDirectWebPlay.visibility = View.VISIBLE
                            btnDirectWebPlay.requestFocus()
                        }
                    }
                })
            }

        playerView.player = exoPlayer
        handler.post(updateProgressRunnable)
    }

    private fun setupEpisodeRecyclerView() {
        rvEpisodeGrid.layoutManager = GridLayoutManager(this, 6)
        rvEpisodeGrid.adapter = EpisodeAdapter(emptyList(), 0) { episode, position ->
            showEpisodeSheet(false)
            playEpisode(position)
        }
    }

    /**
     * 啟動詳情頁載入與串流嗅探
     */
    private fun startLoadingDrama() {
        val drama = currentDrama ?: return
        Log.i(TAG, "點擊海報開始嗅探: title=${drama.title}, detailUrl=${drama.detailUrl}")
        layoutPlayerLoading.visibility = View.VISIBLE
        tvPlayerLoadingStatus.text = "正在嗅探真實影片串流，請稍候..."
        tvPlayerLoadingUrl.visibility = View.VISIBLE
        tvPlayerLoadingUrl.text = "目標: ${drama.detailUrl}"
        btnDirectWebPlay.visibility = View.GONE
        layoutWebViewContainer.visibility = View.GONE

        // 設定 12 秒嗅探超時保護計時器
        handler.removeCallbacks(sniffTimeoutRunnable)
        handler.postDelayed(sniffTimeoutRunnable, SNIFF_TIMEOUT_MS)

        webScraperBridge.loadDramaDetail(
            detailUrl = drama.detailUrl,
            onEpisodesLoaded = { episodes ->
                if (episodes.isNotEmpty()) {
                    episodeList = episodes
                    (rvEpisodeGrid.adapter as? EpisodeAdapter)?.updateData(episodes, currentEpisodeIndex)
                    tvPlayerEpisode.text = episodes[currentEpisodeIndex].title
                }
            },
            onStreamFound = { streamUrl ->
                handler.removeCallbacks(sniffTimeoutRunnable)
                if (streamUrl.isNotEmpty()) {
                    playStream(streamUrl)
                } else {
                    layoutPlayerLoading.visibility = View.VISIBLE
                    tvPlayerLoadingStatus.text = if (!webScraperBridge.isWebViewSupported) {
                        "系統缺少 Android System WebView 元件，無法嗅探影片"
                    } else {
                        "嗅探串流逾時，可點擊下方按鈕直接網頁播放"
                    }
                    btnDirectWebPlay.visibility = View.VISIBLE
                    btnDirectWebPlay.requestFocus()
                }
            }
        )
    }

    /**
     * 播放指定的串流網址
     */
    private fun playStream(streamUrl: String) {
        val player = exoPlayer ?: return
        Log.i(TAG, "原生播放器開始載入串流: $streamUrl")
        try {
            handler.removeCallbacks(sniffTimeoutRunnable)
            btnDirectWebPlay.visibility = View.GONE
            layoutWebViewContainer.visibility = View.GONE

            val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            layoutPlayerLoading.visibility = View.GONE
            showHint("開始播放：${tvPlayerEpisode.text}")
        } catch (e: Exception) {
            Log.e(TAG, "解析 MediaItem 失敗: ${e.message}")
        }
    }

    /**
     * 切換至指定集數
     */
    private fun playEpisode(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        currentEpisodeIndex = index
        streamRetryCount = 0 // 切換集數時重置重試計數
        val targetEpisode = episodeList[index]
        tvPlayerEpisode.text = targetEpisode.title
        (rvEpisodeGrid.adapter as? EpisodeAdapter)?.setCurrentPlayingIndex(index)

        exoPlayer?.stop()
        layoutPlayerLoading.visibility = View.VISIBLE
        tvPlayerLoadingStatus.text = "正在切換至【${targetEpisode.title}】..."
        tvPlayerLoadingUrl.visibility = View.VISIBLE
        tvPlayerLoadingUrl.text = "目標: ${targetEpisode.url}"
        btnDirectWebPlay.visibility = View.GONE
        layoutWebViewContainer.visibility = View.GONE

        handler.removeCallbacks(sniffTimeoutRunnable)
        handler.postDelayed(sniffTimeoutRunnable, SNIFF_TIMEOUT_MS)

        webScraperBridge.sniffEpisodeStream(targetEpisode.url) { streamUrl ->
            handler.removeCallbacks(sniffTimeoutRunnable)
            if (streamUrl.isNotEmpty()) {
                playStream(streamUrl)
            } else {
                layoutPlayerLoading.visibility = View.VISIBLE
                tvPlayerLoadingStatus.text = "嗅探此集串流逾時，可點擊下方直接網頁播放"
                btnDirectWebPlay.visibility = View.VISIBLE
                btnDirectWebPlay.requestFocus()
            }
        }
    }

    /**
     * 切換上一集
     */
    private fun playPreviousEpisode() {
        if (currentEpisodeIndex > 0) {
            playEpisode(currentEpisodeIndex - 1)
        } else {
            showHint("已經是第一集囉！")
        }
    }

    /**
     * 切換下一集
     */
    private fun playNextEpisode() {
        if (currentEpisodeIndex < episodeList.size - 1) {
            playEpisode(currentEpisodeIndex + 1)
        } else {
            showHint("已經是最後一集囉！")
        }
    }

    private fun retryCurrentEpisode() {
        if (episodeList.isNotEmpty() && currentEpisodeIndex < episodeList.size) {
            val targetEpisode = episodeList[currentEpisodeIndex]
            webScraperBridge.sniffEpisodeStream(targetEpisode.url) { streamUrl ->
                if (streamUrl.isNotEmpty()) {
                    playStream(streamUrl)
                } else {
                    layoutPlayerLoading.visibility = View.VISIBLE
                    tvPlayerLoadingStatus.text = "重試嗅探超時，請更換線路或直接網頁播放"
                    btnDirectWebPlay.visibility = View.VISIBLE
                    btnDirectWebPlay.requestFocus()
                }
            }
        } else {
            startLoadingDrama()
        }
    }

    /**
     * 顯示或隱藏選集半透明網格
     */
    private fun showEpisodeSheet(show: Boolean) {
        if (show) {
            layoutEpisodeSheet.visibility = View.VISIBLE
            rvEpisodeGrid.requestFocus()
            hideController()
        } else {
            layoutEpisodeSheet.visibility = View.GONE
            playerView.requestFocus()
        }
    }

    /**
     * 顯示控制列並啟動 5 秒自動隱藏
     */
    private fun showControllerTemporarily() {
        layoutController.visibility = View.VISIBLE
        handler.removeCallbacks(hideControllerRunnable)
        handler.postDelayed(hideControllerRunnable, CONTROLLER_TIMEOUT)
    }

    private fun hideController() {
        layoutController.visibility = View.GONE
        handler.removeCallbacks(hideControllerRunnable)
    }

    private fun showHint(message: String) {
        tvPlayerHint.text = message
        tvPlayerHint.visibility = View.VISIBLE
        tvPlayerHint.alpha = 1f
        tvPlayerHint.animate().alpha(0f).setDuration(500).setStartDelay(2000).withEndAction {
            tvPlayerHint.visibility = View.GONE
        }.start()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 1. 若當前處於全螢幕網頁檢視模式，優先攔截返回鍵
        if (layoutWebViewContainer.visibility == View.VISIBLE) {
            if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                val wv = webScraperBridge.getDetailWebView()
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                    return true
                } else {
                    layoutWebViewContainer.visibility = View.GONE
                    layoutWebViewContainer.removeAllViews()
                    showHint("已關閉網頁檢視")
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // 2. 選集介面已開啟時的按鍵處理
        if (layoutEpisodeSheet.visibility == View.VISIBLE) {
            if (action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                showEpisodeSheet(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 3. 處理確認鍵 (長按呼出選集，短按暫停/播放)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isCenterLongPressTriggered = false
                    handler.postDelayed(centerLongPressRunnable, 700L)
                }
                return true
            } else if (action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(centerLongPressRunnable)
                if (!isCenterLongPressTriggered) {
                    val player = exoPlayer
                    if (player != null) {
                        if (player.isPlaying) {
                            player.pause()
                            showControllerTemporarily()
                            showHint("暫停播放")
                        } else {
                            player.play()
                            hideController()
                            showHint("繼續播放")
                        }
                    }
                }
                return true
            }
        }

        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                // 快退 10 秒
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val player = exoPlayer ?: return true
                    val target = max(0L, player.currentPosition - SEEK_STEP_MS)
                    player.seekTo(target)
                    showHint("快退 -10 秒 (${formatTime(target)})")
                    showControllerTemporarily()
                    return true
                }

                // 快進 10 秒 (修正 C.TIME_UNSET / duration <= 0 負數快進異常)
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val player = exoPlayer ?: return true
                    val duration = player.duration
                    val target = if (duration > 0) {
                        min(duration, player.currentPosition + SEEK_STEP_MS)
                    } else {
                        player.currentPosition + SEEK_STEP_MS
                    }
                    player.seekTo(target)
                    showHint("快進 +10 秒 (${formatTime(target)})")
                    showControllerTemporarily()
                    return true
                }

                // 上一集
                KeyEvent.KEYCODE_DPAD_UP -> {
                    playPreviousEpisode()
                    return true
                }

                // 下一集
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playNextEpisode()
                    return true
                }

                // 遙控器選單鍵：呼出選集網格
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    showEpisodeSheet(true)
                    return true
                }

                // 返回鍵
                KeyEvent.KEYCODE_BACK -> {
                    if (layoutController.visibility == View.VISIBLE) {
                        hideController()
                        return true
                    }
                    finish()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(sniffTimeoutRunnable)
        layoutWebViewContainer.removeAllViews()
        exoPlayer?.release()
        exoPlayer = null
        webScraperBridge.destroy()
    }
}
