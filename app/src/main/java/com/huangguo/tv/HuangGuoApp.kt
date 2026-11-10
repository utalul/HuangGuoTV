package com.huangguo.tv

import android.app.Application
import android.util.Log
import com.huangguo.tv.config.ConfigManager

class HuangGuoApp : Application() {

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

        // 初始化雲端配置管理器
        ConfigManager.getInstance(this)
    }
}
