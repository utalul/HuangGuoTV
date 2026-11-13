package com.huangguo.tv.model

import java.io.Serializable

/**
 * 原生 Android TV 選集列表資料實體
 */
data class EpisodeItem(
    val index: Int,
    val title: String,
    val url: String
) : Serializable
