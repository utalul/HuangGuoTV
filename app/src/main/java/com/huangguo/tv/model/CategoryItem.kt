package com.huangguo.tv.model

import java.io.Serializable

/**
 * Android TV 左側邊欄分類與專題合集實體
 */
data class CategoryItem(
    val id: String,
    val title: String,
    val url: String,
    val count: String = "" // 如 "81部" 或空字串
) : Serializable
