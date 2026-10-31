package dev.ferry.android

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2Test {
    @Test
    fun grantsDefaultOff() {
        assertEquals(emptySet<Grant>(), Grants.read { _, default -> default })
        assertEquals(setOf(Grant.RING), Grants.read { key, default -> if (key == "ring") true else default })
        assertEquals(Grant.entries.size, Grant.entries.map { it.key }.toSet().size) // unique pref keys
    }

    @Test
    fun webUrls() {
        assertTrue(Links.isWebUrl("https://example.com/a?b=c#d"))
        assertTrue(Links.isWebUrl("HTTP://example.com"))
        assertTrue(Links.isWebUrl("https://例え.jp/"))
        assertFalse(Links.isWebUrl("javascript:alert(1)"))
        assertFalse(Links.isWebUrl("file:///etc/passwd"))
        assertFalse(Links.isWebUrl("intent://x#Intent;scheme=http;end"))
        assertFalse(Links.isWebUrl("https://"))
        assertFalse(Links.isWebUrl("https:///path"))
        assertFalse(Links.isWebUrl("example.com"))
        assertFalse(Links.isWebUrl("https://a.com b"))
        assertFalse(Links.isWebUrl(""))
    }

    @Test
    fun sharedTextClassification() {
        assertEquals("https://example.com/x", Links.sharedUrl("  https://example.com/x\n"))
        assertNull(Links.sharedUrl("look at https://example.com/x"))
        assertNull(Links.sharedUrl("https://a.com https://b.com"))
        assertNull(Links.sharedUrl("just some text"))
    }

    @Test
    fun notificationFilter() {
        val me = "dev.ferry.android"
        assertTrue(FerryListener.shouldForward("com.chat", me, 0, "Ann", "hi"))
        assertTrue(FerryListener.shouldForward("com.chat", me, Notification.FLAG_AUTO_CANCEL, "", "hi"))
        assertFalse(FerryListener.shouldForward(me, me, 0, "Ann", "hi"))
        assertFalse(FerryListener.shouldForward("com.chat", me, Notification.FLAG_ONGOING_EVENT, "Ann", "hi"))
        assertFalse(FerryListener.shouldForward("com.chat", me, Notification.FLAG_FOREGROUND_SERVICE, "Ann", "hi"))
        assertFalse(FerryListener.shouldForward("com.chat", me, Notification.FLAG_GROUP_SUMMARY, "Ann", "hi"))
        assertFalse(FerryListener.shouldForward("com.chat", me, Notification.FLAG_LOCAL_ONLY, "Ann", "hi"))
        assertFalse(FerryListener.shouldForward("com.chat", me, 0, " ", ""))
    }

    @Test
    fun boundedQueueDropsOldestNotificationFirst() {
        val q = ArrayDeque<String>()
        listOf("n1", "b1", "n2").forEach { Outbox.pushBounded(q, it, 3) { e -> e.startsWith("n") } }
        Outbox.pushBounded(q, "b2", 3) { it.startsWith("n") }
        assertEquals(listOf("b1", "n2", "b2"), q.toList())
        Outbox.pushBounded(q, "b3", 3) { it.startsWith("n") }
        assertEquals(listOf("b1", "b2", "b3"), q.toList())
        Outbox.pushBounded(q, "b4", 3) { it.startsWith("n") } // nothing droppable: oldest goes
        assertEquals(listOf("b2", "b3", "b4"), q.toList())
    }
}
