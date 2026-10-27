package com.restaurant.iptv.recovery

object RecoveryBudget {
    const val WINDOW_MS = 15 * 60_000L
    const val MAX_ATTEMPTS = 3
    fun recent(attempts: List<Long>, now: Long) = attempts.filter { it <= now && now - it < WINDOW_MS }
    fun mayRestart(attempts: List<Long>, now: Long) = recent(attempts, now).size < MAX_ATTEMPTS
}
