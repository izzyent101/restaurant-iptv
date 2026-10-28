package com.restaurant.iptv.ui

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvChannelListTest {
    @Test fun categoryRefreshKeepsSelectionByNameAndSkipsUnchangedData() {
        val adapter = CategoryAdapter {}
        adapter.submit(listOf("All", "News", "Sports"))
        adapter.setSelected(2)
        var resets = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { resets++ }
        })
        adapter.submit(listOf("All", "News", "Sports"))
        assertEquals(0, resets)
        adapter.submit(listOf("All", "Movies", "News", "Sports"))
        assertEquals("Sports", adapter.current())
        assertEquals(3, adapter.selectedIndex)
    }

    @Test fun heldRemoteTraversesRecycledRowsAndStopsAtBothEnds() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val list = TvChannelList(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 100
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                object : RecyclerView.ViewHolder(TextView(activity).apply {
                    isFocusable = true
                    layoutParams = RecyclerView.LayoutParams(400, 60)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = "Row $position"
            }
        }
        activity.setContentView(list)
        fun settle() {
            list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 400, 300)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        settle()
        list.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
        fun press(key: Int) {
            list.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            settle()
        }
        repeat(99) { expected ->
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(expected + 1, list.getChildAdapterPosition(list.focusedChild!!))
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(99, list.getChildAdapterPosition(list.focusedChild!!))
        repeat(99) { press(KeyEvent.KEYCODE_DPAD_UP) }
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, list.getChildAdapterPosition(list.focusedChild!!))
        // Repeats arriving before the next layout must keep the newest target.
        repeat(25) { list.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
        settle()
        assertEquals(25, list.getChildAdapterPosition(list.focusedChild!!))
        activity.finish()
    }
}
