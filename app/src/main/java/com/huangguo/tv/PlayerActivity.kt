package com.huangguo.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
 * 並配備自動防盜鏈 Header 注入與完整電視遙控器快捷鍵。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HuangGuoTV_Player"
        const val EXTRA_DRAMA = "extra_drama"
        private const val CONTROLLER_TIMEOUT = 5000L
        private const val SEEK_STEP_MS = 10000L // 10 秒快進/快退
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

    private lateinit var layoutEpisodeSheet: RelativeLayout
    private lateinit var rvEpisodeGrid: RecyclerView
    private lateinit var tvPlayerHint: TextView

    private var exoPlayer: ExoPlayer? = null
    private lateinit var webScraperBridge: WebScraperBridge
    private lateinit var configManager: ConfigManager

    private var currentDrama: DramaItem? = null
    private var episodeList: List<EpisodeItem> = emptyList()
    private var currentEpisodeIndex = 0

    private val handler = Handler(Looper.getMainLooper())

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
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        layoutEpisodeSheet = findViewById(R.id.layoutEpisodeSheet)
        rvEpisodeGrid = findViewById(R.id.rvEpisodeGrid)
        tvPlayerHint = findViewById(R.id.tvPlayerHint)

        tvPlayerTitle.text = currentDrama?.title ?: "短劇播放"
        tvPlayerEpisode.text = "正在載入第 1 集..."
    }

    /**
     * 初始化 ExoPlayer 並配置自訂防盜鏈 HTTP Header
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

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                layoutPlayerLoading.visibility = View.GONE
                                tvTotalTime.text = formatTime(duration)
                            }
                            Player.STATE_BUFFERING -> {
                                layoutPlayerLoading.visibility = View.VISIBLE
                                tvPlayerLoadingStatus.text = "影片緩衝中..."
                            }
                            Player.STATE_ENDED -> {
                                // 當前集數播放完畢，自動播放下一集
                                showHint("即將為您自動播放下一集")
                                playNextEpisode()
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer 播放錯誤: ${error.message}, code=${error.errorCodeName}")
                        layoutPlayerLoading.visibility = View.VISIBLE
                        tvPlayerLoadingStatus.text = "播放失敗，正在重新嗅探備援串流..."
                        retryCurrentEpisode()
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
        layoutPlayerLoading.visibility = View.VISIBLE
        tvPlayerLoadingStatus.text = "正在嗅探真實影片串流，請稍候..."

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
                playStream(streamUrl)
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
        val targetEpisode = episodeList[index]
        tvPlayerEpisode.text = targetEpisode.title
        (rvEpisodeGrid.adapter as? EpisodeAdapter)?.setCurrentPlayingIndex(index)

        exoPlayer?.stop()
        layoutPlayerLoading.visibility = View.VISIBLE
        tvPlayerLoadingStatus.text = "正在切換至【${targetEpisode.title}】..."

        webScraperBridge.sniffEpisodeStream(targetEpisode.url) { streamUrl ->
            playStream(streamUrl)
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
            playEpisode(currentEpisodeIndex)
        } else {
            startLoadingDrama()
        }
    }

    /**
     * 顯示或隱藏選集半透明網格
     */
    private fun showEpisodeSheet(show: Boolean) {
        if (show) {
            hideController()
            layoutEpisodeSheet.visibility = View.VISIBLE
            rvEpisodeGrid.post {
                rvEpisodeGrid.findViewHolderForAdapterPosition(currentEpisodeIndex)?.itemView?.requestFocus()
            }
        } else {
            layoutEpisodeSheet.visibility = View.GONE
        }
    }

    private fun isEpisodeSheetVisible(): Boolean = layoutEpisodeSheet.visibility == View.VISIBLE

    /**
     * 暫時顯示原生電視控制 HUD，幾秒後自動隱藏
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
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 2000L)
    }

    private val hideHintRunnable = Runnable {
        tvPlayerHint.animate().alpha(0f).setDuration(300).withEndAction {
            tvPlayerHint.visibility = View.GONE
        }.start()
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * 電視遙控器專用快捷鍵處理
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 1. 選集網格開啟時的事件處理
        if (isEpisodeSheetVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                showEpisodeSheet(false)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MENU && action == KeyEvent.ACTION_DOWN) {
                showEpisodeSheet(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 2. 處理長按「確認鍵」呼出選集
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isCenterLongPressTriggered = false
                    handler.postDelayed(centerLongPressRunnable, 1000L)
                }
            } else if (action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(centerLongPressRunnable)
                if (isCenterLongPressTriggered) {
                    isCenterLongPressTriggered = false
                    return true
                }
                // 單擊確認鍵：切換暫停/播放並顯示控制列
                val player = exoPlayer
                if (player != null) {
                    if (player.isPlaying) {
                        player.pause()
                        showHint("已暫停")
                    } else {
                        player.play()
                        showHint("繼續播放")
                    }
                    showControllerTemporarily()
                }
                return true
            }
            return true
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

                // 快進 10 秒
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val player = exoPlayer ?: return true
                    val target = min(player.duration, player.currentPosition + SEEK_STEP_MS)
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
        exoPlayer?.release()
        exoPlayer = null
        webScraperBridge.destroy()
    }
}
