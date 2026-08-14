package com.restaurant.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.restaurant.iptv.R

/**
 * Left sidebar of categories (All, Favorites, and provider groups).
 *
 * Focus highlight is handled entirely by the row background selector
 * (state_focused / state_selected) so moving the D-pad does NOT trigger any
 * adapter rebinds — that churn was the source of the scroll lag/repeat.
 * Focusing a row just reports the selection; MainActivity debounces the
 * (heavier) channel-list rebuild so fast scrolling stays smooth.
 */
class CategoryAdapter(
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private val items = ArrayList<String>()
    var selectedIndex = 0
        private set

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = items[position].hashCode().toLong()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        if (selectedIndex >= items.size) selectedIndex = 0
        notifyDataSetChanged()
    }

    fun current(): String? = items.getOrNull(selectedIndex)

    fun indexOf(name: String): Int = items.indexOf(name)

    /** Highlight a category without firing onSelect (used to jump to the current channel's group). */
    fun setSelected(index: Int) {
        if (index in items.indices && index != selectedIndex) {
            val old = selectedIndex
            selectedIndex = index
            notifyItemChanged(old)
            notifyItemChanged(index)
        }
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = items[position]
        holder.text.text = name
        holder.text.isSelected = position == selectedIndex

        holder.text.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                selectedIndex = pos
                onSelect(items[pos])
            }
        }
        holder.text.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos != selectedIndex) {
                    selectedIndex = pos
                    onSelect(items[pos])
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
