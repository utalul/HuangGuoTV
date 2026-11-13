package com.huangguo.tv.model

import java.io.Serializable

/**
 * 原生 Android TV 海報牆短劇資料實體
 */
data class DramaItem(
    val id: String = "",
    val title: String,
    val coverUrl: String,
    val detailUrl: String,
    val badge: String = ""
) : Serializable
