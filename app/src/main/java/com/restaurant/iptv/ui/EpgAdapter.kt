package com.restaurant.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.restaurant.iptv.R
import com.restaurant.iptv.epg.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Program list for the focused channel (the guide panel). */
class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {

    private val items = ArrayList<Programme>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(list: List<Programme>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Index of the currently-airing programme, or 0. */
    fun currentIndex(): Int {
        val now = System.currentTimeMillis()
        val i = items.indexOfFirst { it.startMs <= now && it.endMs > now }
        return if (i >= 0) i else 0
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.epgTime)
        val title: TextView = view.findViewById(R.id.epgTitle)
        val desc: TextView = view.findViewById(R.id.epgDesc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val now = System.currentTimeMillis()
        val airing = p.startMs <= now && p.endMs > now
        if (airing) {
            holder.time.text = "NOW"
            holder.time.setTextColor(0xFF2E7DFF.toInt())
            holder.title.setTextColor(0xFFFFFFFF.toInt())
        } else {
            holder.time.text = timeFmt.format(Date(p.startMs))
            holder.time.setTextColor(0xFF9FB2C6.toInt())
            holder.title.setTextColor(0xFFF2F6FB.toInt())
        }
        holder.title.text = p.title
        holder.itemView.isSelected = airing
        holder.desc.text = p.desc ?: ""
        holder.desc.visibility = if (!p.desc.isNullOrBlank() && (airing || holder.itemView.isFocused)) View.VISIBLE else View.GONE
        holder.itemView.setOnFocusChangeListener { _, f ->
            holder.desc.visibility = if ((f || airing) && !p.desc.isNullOrBlank()) View.VISIBLE else View.GONE
        }
    }

    override fun getItemCount(): Int = items.size
}
