package com.restaurant.iptv.ui

import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.epg.Programme

/** A channel plus its live EPG + favorite state, for the rich list. */
data class ChannelUi(
    val channel: ChannelEntity,
    val now: Programme?,
    val next: Programme?,
    val favorite: Boolean
)
