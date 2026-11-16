package com.huangguo.tv.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.huangguo.tv.R
import com.huangguo.tv.model.DramaItem

/**
 * 原生 Android TV 海報牆適配器
 * 支援 Coil 防盜鏈 Header 注入 (Referer, User-Agent) 與詳細除錯日誌
 */
class DramaAdapter(
    private var items: List<DramaItem> = emptyList(),
    var currentBaseUrl: String = "",
    var userAgent: String = "",
    private val onItemClick: (DramaItem) -> Unit
) : RecyclerView.Adapter<DramaAdapter.DramaViewHolder>() {

    companion object {
        private const val TAG = "DramaAdapter"
    }

    fun updateData(newItems: List<DramaItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DramaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_drama_card, parent, false)
        return DramaViewHolder(view)
    }

    override fun onBindViewHolder(holder: DramaViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, currentBaseUrl, userAgent, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class DramaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvBadge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)

        init {
            // TV 遙控器焦點平滑放大與邊框外顯
            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                    v.elevation = 20f
                    v.translationZ = 20f
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    v.elevation = 2f
                    v.translationZ = 0f
                }
            }
        }

        fun bind(
            item: DramaItem,
            baseUrl: String,
            ua: String,
            onClick: (DramaItem) -> Unit
        ) {
            tvTitle.text = item.title

            if (item.badge.isNotEmpty()) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = item.badge
            } else {
                tvBadge.visibility = View.GONE
            }

            if (item.coverUrl.isNotEmpty()) {
                ivCover.load(item.coverUrl) {
                    crossfade(true)
                    placeholder(R.drawable.dialog_bg)
                    error(R.drawable.dialog_bg)

                    // 穿透防盜鏈
                    if (baseUrl.isNotEmpty()) {
                        addHeader("Referer", baseUrl)
                    }
                    if (ua.isNotEmpty()) {
                        addHeader("User-Agent", ua)
                    }

                    listener(
                        onSuccess = { _, _ ->
                            Log.d(TAG, "封面載入成功: ${item.title}")
                        },
                        onError = { _, result ->
                            Log.e("CoilError", "圖片載入失敗: ${item.coverUrl}, 原因: ${result.throwable.message}")
                        }
                    )
                }
            } else {
                ivCover.setImageResource(R.drawable.dialog_bg)
            }

            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
