package com.marhaba.launcher

object HomeOrder {
    fun reconcile(current: List<String>, selected: Set<String>): List<String> =
        current.filter { it in selected }.distinct() + selected.filterNot { it in current }.sorted()

    fun move(current: List<String>, app: String, delta: Int, installed: Set<String>): List<String> {
        val visible = current.filter { it in installed }.distinct()
        val from = visible.indexOf(app)
        val to = from + delta
        if (from < 0 || to !in visible.indices || delta !in listOf(-1, 1)) return current
        val result = current.toMutableList()
        val a = result.indexOf(app)
        val b = result.indexOf(visible[to])
        result[a] = result[b]; result[b] = app
        return result
    }
}
