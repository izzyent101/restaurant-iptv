package com.restaurant.iptv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Handle vertical remote navigation before Android can search another column. */
class TvChannelList @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    private var pendingPosition: Int? = null
    private var navigationGeneration = 0

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> return super.dispatchKeyEvent(event)
        }
        if (!hasFocus()) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val count = adapter?.itemCount ?: 0
        if (count == 0) return true
        val current = pendingPosition ?: focusedChild?.let(::getChildAdapterPosition)
            ?.takeIf { it != NO_POSITION } ?: return true
        val target = (current + direction).coerceIn(0, count - 1)
        pendingPosition = target
        focusTarget(target, ++navigationGeneration)
        return true
    }

    private fun focusTarget(position: Int, generation: Int, attempt: Int = 0) {
        if (generation != navigationGeneration) return
        if (!hasFocus() || position >= (adapter?.itemCount ?: 0)) {
            pendingPosition = null
            return
        }
        val row = findViewHolderForAdapterPosition(position)?.itemView
        if (!hasPendingAdapterUpdates() && row != null && row.requestFocus()) {
            pendingPosition = null
        } else if (attempt < 4) {
            // Minimal scrolling; do not recenter the list on every key repeat.
            (layoutManager as? LinearLayoutManager)?.scrollToPosition(position)
            postOnAnimation { focusTarget(position, generation, attempt + 1) }
        } else {
            pendingPosition = null
        }
    }
}
