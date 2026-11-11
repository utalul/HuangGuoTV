package com.huangguo.tv.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * 遠端與本地雲端配置資料模型
 */
data class RemoteConfig(
    val version: Int = 1,
    val primaryUrl: String = "https://huangguoai.com",
    val backupUrls: List<String> = listOf(
        "https://huangguoai.com",
        "https://api1.huangguoju.com",
        "https://bak.wtzxzltu.cc"
    ),
    val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 HuangGuoTV/1.0",
    val injectCss: String = "",
    val injectJs: String = "",
    val githubRepo: String = "mustresource/HuangGuoTV",
    val remoteConfigUrl: String = "https://raw.githubusercontent.com/mustresource/HuangGuoTV/main/remote_config.json"
) {
    companion object {
        fun fromJson(jsonStr: String): RemoteConfig {
            val obj = JSONObject(jsonStr)
            val backupList = mutableListOf<String>()
            if (obj.has("backup_urls")) {
                val arr = obj.getJSONArray("backup_urls")
                for (i in 0 until arr.length()) {
                    backupList.add(arr.getString(i))
                }
            }
            if (backupList.isEmpty() && obj.has("primary_url")) {
                backupList.add(obj.optString("primary_url", "https://huangguoai.com"))
            }

            var repo = obj.optString("github_repo", "mustresource/HuangGuoTV")
            if (repo.contains("HGTV", ignoreCase = true) || repo.isBlank()) {
                repo = "mustresource/HuangGuoTV"
            }
            var configUrl = obj.optString(
                "remote_config_url",
                "https://raw.githubusercontent.com/mustresource/HuangGuoTV/main/remote_config.json"
            )
            if (configUrl.contains("HGTV", ignoreCase = true)) {
                configUrl = "https://raw.githubusercontent.com/mustresource/HuangGuoTV/main/remote_config.json"
            }

            return RemoteConfig(
                version = obj.optInt("version", 1),
                primaryUrl = obj.optString("primary_url", "https://huangguoai.com"),
                backupUrls = backupList,
                userAgent = obj.optString(
                    "user_agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 HuangGuoTV/1.0"
                ),
                injectCss = obj.optString("inject_css", ""),
                injectJs = obj.optString("inject_js", ""),
                githubRepo = repo,
                remoteConfigUrl = configUrl
            )
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("primary_url", primaryUrl)
        val arr = JSONArray()
        backupUrls.forEach { arr.put(it) }
        obj.put("backup_urls", arr)
        obj.put("user_agent", userAgent)
        obj.put("inject_css", injectCss)
        obj.put("inject_js", injectJs)
        obj.put("github_repo", githubRepo)
        obj.put("remote_config_url", remoteConfigUrl)
        return obj.toString()
    }
}
