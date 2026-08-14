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

/** Clean channel row: number, logo, full name (up to 2 lines), favorite star. */
class RichChannelAdapter(
    private val onPlay: (ChannelEntity) -> Unit,
    private val onFocusCh: (ChannelUi) -> Unit,
    private val onToggleFav: (ChannelEntity) -> Unit
) : RecyclerView.Adapter<RichChannelAdapter.VH>() {

    private val items = ArrayList<ChannelUi>()

    fun submit(list: List<ChannelUi>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun itemAt(pos: Int): ChannelUi? = items.getOrNull(pos)
    fun size(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.chNumber)
        val logo: ImageView = view.findViewById(R.id.chLogo)
        val name: TextView = view.findViewById(R.id.chName)
        val star: TextView = view.findViewById(R.id.chStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rich_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ui = items[position]
        val ch = ui.channel
        holder.number.text = if (ch.number > 0) ch.number.toString() else ""
        holder.name.text = ch.name
        if (!ch.logoUrl.isNullOrBlank()) holder.logo.load(ch.logoUrl) else holder.logo.setImageDrawable(null)

        holder.star.text = if (ui.favorite) "★" else "☆"
        holder.star.setTextColor(if (ui.favorite) 0xFFE3B341.toInt() else 0xFF6B7683.toInt())

        holder.itemView.setOnClickListener { onPlay(ch) }
        holder.itemView.setOnLongClickListener { onToggleFav(ch); true }
        holder.itemView.setOnFocusChangeListener { _, f -> if (f) onFocusCh(ui) }
    }

    override fun getItemCount(): Int = items.size
}
