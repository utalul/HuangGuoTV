package com.huangguo.tv.model

import java.io.Serializable

/**
 * 首頁上方橫向子分類 Tab 實體
 * 支援「主板塊」（全部精選、精選推薦、最近上新、熱播短劇）、
 * 「專題推薦」（81部精品高分等）與「熱門標籤」（都市、古風、奇幻等）
 */
data class SubTabItem(
    val id: String,
    val title: String,
    val url: String = "",
    val type: Type = Type.SECTION
) : Serializable {

    enum class Type {
        SECTION, // 主板塊 (如：全部精選、精選推薦、最近上新、熱播短劇)
        TOPIC,   // 專題推薦 (如：81部精品高分、68部家庭倫理、100部天菜男同)
        TAG      // 熱門標籤 (如：都市、古風、奇幻、逆襲、系統等)
    }
}
