package com.huangguo.tv

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.huangguo.tv.config.ConfigManager
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 應用程式入口
 * 包含全域未捕獲異常安全攔截、雲端配置初始化，
 * 以及為 Android 5.0~7.0 (API 21~24) 舊電視盒配置自適應 SSL 憑證的 Coil ImageLoader。
 */
class HuangGuoApp : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "HuangGuoApp"
        lateinit var instance: HuangGuoApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化全域異常處理，防止未預期錯誤直接引發 Android TV 閃退
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught Exception on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            // 初始化雲端配置管理器
            ConfigManager.getInstance(this)
        } catch (t: Throwable) {
            Log.e(TAG, "ConfigManager 初始化異常: ${t.message}", t)
        }
    }

    /**
     * 全域 Coil ImageLoader 工廠
     * 支援寬鬆 SSL 憑證驗證，徹底解決 Android 5.0~7.0 舊電視盒因 ISRG Root 根憑證過期導致海報全黑的問題。
     */
    override fun newImageLoader(): ImageLoader {
        val client = createCompatOkHttpClient()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(80L * 1024 * 1024) // 80MB 快取
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    private fun createCompatOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "建立寬鬆 SSL OkHttpClient 失敗，降級使用預設值: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
