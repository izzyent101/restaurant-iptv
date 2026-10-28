package com.marhaba.launcher
import org.junit.Assert.assertEquals
import org.junit.Test
class HomeOrderTest {
    @Test fun retainsOrderWhenFavoritesChange() {
        assertEquals(listOf("b", "a", "c"), HomeOrder.reconcile(listOf("b", "removed", "a"), setOf("a", "b", "c")))
    }
    @Test fun permitsRemovingEveryAppIncludingMmg() {
        assertEquals(emptyList<String>(), HomeOrder.reconcile(listOf("com.restaurant.iptv"), emptySet()))
    }
    @Test fun movesAcrossUnavailableAppsWithoutLosingThem() {
        assertEquals(listOf("b", "missing", "a"), HomeOrder.move(listOf("a", "missing", "b"), "a", 1, setOf("a", "b")))
    }
    @Test fun boundariesDoNotWrap() {
        assertEquals(listOf("a", "b"), HomeOrder.move(listOf("a", "b"), "a", -1, setOf("a", "b")))
    }
}
