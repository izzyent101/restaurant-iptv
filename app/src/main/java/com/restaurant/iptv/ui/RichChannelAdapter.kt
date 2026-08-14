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

/** TiviMate-style channel row: logo, number+name, now-playing subtitle, favorite star. */
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

    /** Flip one channel's star in place — no full rebuild, so focus stays put. */
    fun setFavorite(streamKey: String, fav: Boolean) {
        for (i in items.indices) {
            val it = items[i]
            if (it.channel.streamKey == streamKey && it.favorite != fav) {
                items[i] = it.copy(favorite = fav)
                notifyItemChanged(i)
            }
        }
    }

    fun positionOf(channelId: Long): Int {
        for (i in items.indices) if (items[i].channel.id == channelId) return i
        return -1
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.chLogo)
        val name: TextView = view.findViewById(R.id.chName)
        val now: TextView = view.findViewById(R.id.chNow)
        val star: TextView = view.findViewById(R.id.chStar)
        val arrow: TextView = view.findViewById(R.id.chArrow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rich_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ui = items[position]
        val ch = ui.channel
        holder.name.text = if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name

        val nowTitle = ui.now?.title
        if (!nowTitle.isNullOrBlank()) {
            holder.now.text = nowTitle
            holder.now.visibility = View.VISIBLE
        } else {
            holder.now.visibility = View.GONE
        }

        if (!ch.logoUrl.isNullOrBlank()) holder.logo.load(ch.logoUrl) else holder.logo.setImageDrawable(null)
        holder.star.visibility = if (ui.favorite) View.VISIBLE else View.GONE

        applyColors(holder, holder.itemView.isFocused)

        holder.itemView.setOnClickListener { onPlay(ch) }
        holder.itemView.setOnLongClickListener { onToggleFav(ch); true }
        holder.itemView.setOnFocusChangeListener { _, f ->
            applyColors(holder, f)
            if (f) onFocusCh(ui)
        }
    }

    // Focused row = light pill → dark text; otherwise light text on the dark panel.
    private fun applyColors(holder: VH, focused: Boolean) {
        holder.name.setTextColor(if (focused) TITLE_ON else TITLE_OFF)
        holder.now.setTextColor(if (focused) SUB_ON else SUB_OFF)
        holder.arrow.setTextColor(if (focused) ARROW_ON else 0x00000000)
    }

    private companion object {
        const val TITLE_ON = 0xFF111418.toInt(); const val TITLE_OFF = 0xFFF2F5F8.toInt()
        const val SUB_ON = 0xFF495663.toInt();   const val SUB_OFF = 0xFF93A2B2.toInt()
        const val ARROW_ON = 0xFF2E7DFF.toInt()
    }

    override fun getItemCount(): Int = items.size
}
