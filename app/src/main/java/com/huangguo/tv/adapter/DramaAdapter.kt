package com.huangguo.tv.adapter

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
 */
class DramaAdapter(
    private var items: List<DramaItem> = emptyList(),
    private val onItemClick: (DramaItem) -> Unit
) : RecyclerView.Adapter<DramaAdapter.DramaViewHolder>() {

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
        holder.bind(item, onItemClick)
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

        fun bind(item: DramaItem, onClick: (DramaItem) -> Unit) {
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
