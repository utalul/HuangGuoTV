package com.huangguo.tv.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.huangguo.tv.R
import com.huangguo.tv.model.CategoryItem

/**
 * 原生 Android TV 左側邊欄分類導覽適配器
 */
class CategoryAdapter(
    private var items: List<CategoryItem> = emptyList(),
    private var selectedIndex: Int = 0,
    private val onCategoryClick: (CategoryItem, Int) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    fun updateData(newItems: List<CategoryItem>, defaultSelected: Int = 0) {
        this.items = newItems
        this.selectedIndex = defaultSelected
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

    fun getSelectedItem(): CategoryItem? {
        return items.getOrNull(selectedIndex)
    }

    fun getSelectedIndex(): Int = selectedIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position == selectedIndex) {
            setSelectedIndex(position)
            onCategoryClick(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvCategoryTitle)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCategoryCount)
        private val viewIndicator: View = itemView.findViewById(R.id.viewIndicator)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()
                    v.elevation = 6f
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    v.elevation = 0f
                }
            }
        }

        fun bind(item: CategoryItem, isSelected: Boolean, onClick: () -> Unit) {
            tvTitle.text = item.title
            itemView.isSelected = isSelected

            if (isSelected) {
                viewIndicator.visibility = View.VISIBLE
                tvTitle.setTextColor(Color.parseColor("#FFD600"))
            } else {
                viewIndicator.visibility = View.GONE
                tvTitle.setTextColor(Color.WHITE)
            }

            if (item.count.isNotEmpty()) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = item.count
            } else {
                tvCount.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onClick()
            }
        }
    }
}
