package com.huangguo.tv.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.R
import com.huangguo.tv.model.EpisodeItem

/**
 * 原生播放器選集網格適配器
 */
class EpisodeAdapter(
    private var items: List<EpisodeItem> = emptyList(),
    private var currentPlayingIndex: Int = 0,
    private val onEpisodeSelected: (EpisodeItem, Int) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    fun updateData(newItems: List<EpisodeItem>, currentIndex: Int) {
        this.items = newItems
        this.currentPlayingIndex = currentIndex
        notifyDataSetChanged()
    }

    fun setCurrentPlayingIndex(index: Int) {
        this.currentPlayingIndex = index
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_button, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position == currentPlayingIndex) {
            onEpisodeSelected(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvEpisodeName: TextView = itemView.findViewById(R.id.tvEpisodeName)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start()
                    v.elevation = 10f
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    v.elevation = 0f
                }
            }
        }

        fun bind(item: EpisodeItem, isCurrentPlaying: Boolean, onClick: () -> Unit) {
            tvEpisodeName.text = item.title
            if (isCurrentPlaying) {
                tvEpisodeName.setTextColor(Color.parseColor("#FFD600"))
            } else {
                tvEpisodeName.setTextColor(Color.WHITE)
            }

            itemView.setOnClickListener {
                onClick()
            }
        }
    }
}
