package com.restaurant.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.restaurant.iptv.R
import com.restaurant.iptv.data.entity.ChannelEntity

/** Horizontal "recently watched" strip of channel tiles (TiviMate-style). */
class HistoryTileAdapter(
    private val onPlay: (ChannelEntity) -> Unit
) : RecyclerView.Adapter<HistoryTileAdapter.VH>() {

    private val items = ArrayList<ChannelEntity>()

    fun submit(list: List<ChannelEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.histLogo)
        val name: TextView = view.findViewById(R.id.histName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.name.setTextColor(0xFFF2F5F8.toInt())
        if (!ch.logoUrl.isNullOrBlank()) holder.logo.load(ch.logoUrl) else holder.logo.setImageDrawable(null)
        holder.itemView.setOnClickListener { onPlay(ch) }
        holder.itemView.setOnFocusChangeListener { _, f ->
            holder.name.setTextColor(if (f) 0xFF111418.toInt() else 0xFFF2F5F8.toInt())
        }
    }

    override fun getItemCount(): Int = items.size
}
