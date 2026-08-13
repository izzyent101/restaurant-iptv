package com.restaurant.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.restaurant.iptv.R

/** Left sidebar of categories (All, Favorites, and provider groups). */
class CategoryAdapter(
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private val items = ArrayList<String>()
    var selectedIndex = 0
        private set

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        if (selectedIndex >= items.size) selectedIndex = 0
        notifyDataSetChanged()
    }

    fun current(): String? = items.getOrNull(selectedIndex)

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
        val choose = {
            if (selectedIndex != position) {
                val old = selectedIndex
                selectedIndex = position
                notifyItemChanged(old)
                notifyItemChanged(position)
                onSelect(name)
            }
        }
        holder.text.setOnClickListener { choose() }
        holder.text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) choose() }
    }

    override fun getItemCount(): Int = items.size
}
