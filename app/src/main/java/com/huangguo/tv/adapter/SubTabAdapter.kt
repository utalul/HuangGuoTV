package com.huangguo.tv.adapter

import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.R
import com.huangguo.tv.model.SubTabItem

/**
 * 首頁頂部橫向子分類 Tab 列表適配器
 * 支援遙控器左右切換焦點、平滑微縮放與選中狀態指示
 */
class SubTabAdapter(
    private var items: List<SubTabItem> = emptyList(),
    private var selectedIndex: Int = 0,
    var onKeyDown: (() -> Unit)? = null,
    var onKeyLeftEdge: (() -> Unit)? = null,
    private val onTabClick: (SubTabItem, Int) -> Unit
) : RecyclerView.Adapter<SubTabAdapter.SubTabViewHolder>() {

    fun updateData(newItems: List<SubTabItem>, defaultSelected: Int = 0) {
        this.items = newItems
        this.selectedIndex = if (defaultSelected in newItems.indices) defaultSelected else 0
        notifyDataSetChanged()
    }

    fun setSelectedIndex(position: Int) {
        if (position in items.indices && selectedIndex != position) {
            val oldIndex = selectedIndex
            selectedIndex = position
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
        }
    }

    fun getSelectedItem(): SubTabItem? {
        return items.getOrNull(selectedIndex)
    }

    fun getSelectedIndex(): Int = selectedIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubTabViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sub_tab, parent, false)
        return SubTabViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubTabViewHolder, position: Int) {
        val item = items[position]
        holder.bind(
            item = item,
            position = position,
            isSelected = (position == selectedIndex),
            onKeyDown = onKeyDown,
            onKeyLeftEdge = onKeyLeftEdge
        ) {
            setSelectedIndex(position)
            onTabClick(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    class SubTabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSubTabTitle)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start()
                    v.elevation = 8f
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    v.elevation = 0f
                }
            }
        }

        fun bind(
            item: SubTabItem,
            position: Int,
            isSelected: Boolean,
            onKeyDown: (() -> Unit)?,
            onKeyLeftEdge: (() -> Unit)?,
            onClick: () -> Unit
        ) {
            tvTitle.text = item.title
            itemView.isSelected = isSelected

            if (isSelected) {
                tvTitle.setTextColor(Color.parseColor("#121418"))
            } else {
                tvTitle.setTextColor(Color.WHITE)
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        onKeyDown?.invoke()
                        return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) {
                        onKeyLeftEdge?.invoke()
                        return@setOnKeyListener true
                    }
                }
                false
            }

            itemView.setOnClickListener {
                onClick()
            }
        }
    }
}
