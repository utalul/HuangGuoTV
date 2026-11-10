package com.huangguo.tv.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ConfigManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ConfigManager"
        private const val PREFS_NAME = "huangguo_tv_config"
        private const val KEY_CONFIG_JSON = "cached_config_json"
        private const val KEY_SELECTED_URL = "selected_url"
        private const val KEY_IS_DESKTOP_UA = "is_desktop_ua"

        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentConfig: RemoteConfig

    init {
        currentConfig = loadInitialConfig()
    }

    fun getConfig(): RemoteConfig = currentConfig

    /**
     * 取得當前應該載入的網址
     */
    fun getCurrentTargetUrl(): String {
        val saved = prefs.getString(KEY_SELECTED_URL, null)
        if (!saved.isNullOrEmpty()) {
            return saved
        }
        return currentConfig.primaryUrl
    }

    fun setCurrentTargetUrl(url: String) {
        prefs.edit().putString(KEY_SELECTED_URL, url).apply()
    }

    /**
     * 取得當前 User-Agent
     */
    fun isDesktopUa(): Boolean {
        return prefs.getBoolean(KEY_IS_DESKTOP_UA, false)
    }

    fun setDesktopUa(isDesktop: Boolean) {
        prefs.edit().putBoolean(KEY_IS_DESKTOP_UA, isDesktop).apply()
    }

    fun getEffectiveUserAgent(): String {
        return if (isDesktopUa()) {
            DESKTOP_UA
        } else {
            currentConfig.userAgent
        }
    }

    /**
     * 讀取本地初始配置（優先讀快取，快取無則讀 Assets）
     */
    private fun loadInitialConfig(): RemoteConfig {
        val cached = prefs.getString(KEY_CONFIG_JSON, null)
        if (!cached.isNullOrEmpty()) {
            try {
                return RemoteConfig.fromJson(cached)
            } catch (e: Exception) {
                Log.e(TAG, "解析快取配置失敗，改讀 Assets: ${e.message}")
            }
        }

        return try {
            val json = context.assets.open("remote_config.json").use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
            RemoteConfig.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "讀取 Assets remote_config.json 失敗，使用預設值: ${e.message}")
            RemoteConfig()
        }
    }

    /**
     * 從遠端 GitHub Raw 或備用位址拉取最新雲端熱更新配置
     */
    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        val targetUrl = currentConfig.remoteConfigUrl
        try {
            Log.d(TAG, "開始拉取遠端熱更新配置: $targetUrl")
            val url = URL(targetUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "HuangGuoTV-Client")
                setRequestProperty("Cache-Control", "no-cache")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                if (responseText.isNotEmpty()) {
                    val newConfig = RemoteConfig.fromJson(responseText)
                    currentConfig = newConfig
                    prefs.edit().putString(KEY_CONFIG_JSON, newConfig.toJson()).apply()
                    Log.i(TAG, "成功更新遠端配置！primary_url: ${newConfig.primaryUrl}")
                    return@withContext true
                }
            } else {
                Log.w(TAG, "拉取配置失敗，HTTP 狀態碼: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉取遠端配置異常: ${e.message}")
        }
        return@withContext false
    }

    /**
     * 清除本地配置快取，恢復成 Assets 預設值
     */
    fun resetConfig() {
        prefs.edit().remove(KEY_CONFIG_JSON).remove(KEY_SELECTED_URL).apply()
        currentConfig = loadInitialConfig()
    }
}
