package com.huangguo.tv.update

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.huangguo.tv.R
import com.huangguo.tv.config.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val activity: Activity) {

    companion object {
        private const val TAG = "UpdateManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var downloadJob: Job? = null

    data class ReleaseInfo(
        val tagName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val fileName: String
    )

    /**
     * 檢查更新
     * @param isManual 是否為使用者手動從選單點擊檢查（若為手動且無更新，則彈出 Toast 提示）
     */
    fun checkForUpdates(isManual: Boolean = false) {
        scope.launch {
            val apiUrl = "https://api.github.com/repos/mustresource/HuangGuoTV/releases/latest"

            val info = withContext(Dispatchers.IO) {
                fetchLatestRelease(apiUrl)
            }

            if (info == null) {
                if (isManual) {
                    Toast.makeText(activity, activity.getString(R.string.msg_update_failed), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val currentVersion = getCurrentVersionName(activity)
            val hasUpdate = isNewerVersion(info.tagName, currentVersion)

            if (hasUpdate) {
                showUpdateDialog(info)
            } else if (isManual) {
                Toast.makeText(activity, activity.getString(R.string.msg_latest_version), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchLatestRelease(apiUrl: String): ReleaseInfo? {
        try {
            val url = URL(apiUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "HuangGuoTV-Updater")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val releaseNotes = json.optString("body", "修復已知問題並優化播放體驗。")
                val assets = json.optJSONArray("assets")

                var apkDownloadUrl = ""
                var apkFileName = "update.apk"

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            apkFileName = name
                            break
                        }
                    }
                }

                if (apkDownloadUrl.isNotEmpty()) {
                    return ReleaseInfo(
                        tagName = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = apkDownloadUrl,
                        fileName = apkFileName
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "取得 GitHub Release 失敗: ${e.message}")
        }
        return null
    }

    private fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        // 清除常見版本前綴 'v' 或 'V'
        val remoteClean = remoteTag.removePrefix("v").removePrefix("V").trim()
        val currentClean = currentVersion.removePrefix("v").removePrefix("V").trim()

        if (remoteClean.isEmpty() || currentClean.isEmpty()) return false
        if (remoteClean == currentClean) return false

        val remoteParts = remoteClean.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until length) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun showUpdateDialog(info: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, R.style.Dialog_HuangGuoTV)
        dialog.setContentView(R.layout.dialog_update)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvMessage = dialog.findViewById<TextView>(R.id.tvUpdateMessage)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.updateProgressBar)
        val tvPercent = dialog.findViewById<TextView>(R.id.tvProgressPercent)
        val btnCancel = dialog.findViewById<Button>(R.id.btnUpdateCancel)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnUpdateConfirm)

        tvTitle.text = "${activity.getString(R.string.update_title)} (${info.tagName})"
        tvMessage.text = info.releaseNotes

        // 預設確認按鈕獲取焦點
        btnConfirm.requestFocus()

        btnCancel.setOnClickListener {
            downloadJob?.cancel()
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            btnConfirm.isEnabled = false
            btnConfirm.text = "下載中..."
            progressBar.visibility = View.VISIBLE
            tvPercent.visibility = View.VISIBLE

            downloadApkAndInstall(info.downloadUrl, info.fileName, progressBar, tvPercent, dialog)
        }

        dialog.show()
    }

    private fun downloadApkAndInstall(
        downloadUrl: String,
        fileName: String,
        progressBar: ProgressBar,
        tvPercent: TextView,
        dialog: Dialog
    ) {
        downloadJob = scope.launch {
            val destinationFile = File(activity.getExternalFilesDir(null) ?: activity.cacheDir, fileName)
            val success = withContext(Dispatchers.IO) {
                downloadFile(downloadUrl, destinationFile) { percent ->
                    scope.launch(Dispatchers.Main) {
                        progressBar.progress = percent
                        tvPercent.text = String.format(activity.getString(R.string.update_downloading), percent)
                    }
                }
            }

            if (success) {
                dialog.dismiss()
                installApk(destinationFile)
            } else {
                Toast.makeText(activity, activity.getString(R.string.msg_update_failed), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    private fun downloadFile(urlStr: String, destination: File, onProgress: (Int) -> Unit): Boolean {
        var input: InputStream? = null
        var output: FileOutputStream? = null
        var connection: HttpURLConnection? = null
        try {
            var url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 20000
            }

            // 處理重定向（GitHub releases 往往會 302 跳轉至 AWS S3）
            var redirectCount = 0
            while (connection!!.responseCode in 300..399 && redirectCount < 5) {
                val newUrl = connection.getHeaderField("Location")
                url = URL(newUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 20000
                }
                redirectCount++
            }

            val fileLength = connection.contentLength
            input = connection.inputStream
            output = FileOutputStream(destination)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            var lastReportedPercent = -1

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)
                if (fileLength > 0) {
                    val percent = ((total * 100) / fileLength).toInt()
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
            output.flush()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "下載檔案錯誤: ${e.message}")
            if (destination.exists()) {
                destination.delete()
            }
            return false
        } finally {
            try { output?.close() } catch (_: Exception) {}
            try { input?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * 呼叫系統安裝器覆蓋安裝 APK
     */
    private fun installApk(apkFile: File) {
        try {
            if (!apkFile.exists()) return

            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "啟動安裝器失敗: ${e.message}")
            Toast.makeText(activity, "無法開啟安裝程式: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
