package com.restaurant.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.restaurant.iptv.data.entity.ChannelEntity

class ChannelAdapter(
    private val onClick: (ChannelEntity) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<ChannelEntity>()

    fun submit(list: List<ChannelEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(com.restaurant.iptv.R.layout.item_channel, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.text.text = if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name
        holder.text.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount(): Int = items.size
}
