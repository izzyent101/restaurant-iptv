package com.restaurant.iptv.data

import kotlinx.serialization.Serializable

/**
 * A TV managed from the central dashboard. `address` is host:port reachable
 * over the LAN / Tailscale, e.g. "100.101.102.103:8080".
 */
@Serializable
data class TvEndpoint(val name: String, val address: String)
