package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.NotificationPrefs
import io.github.iacoposb.adodash.data.NotifyKind
import io.github.iacoposb.adodash.data.QuietHours
import io.github.iacoposb.adodash.data.SoundTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NotificationRulesTest {
    // 2026-10-16 is a Friday
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val friday = 16
    private val saturday = 17
    private val sunday = 18
    private val monday = 19

    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val overnight = QuietHours(enabled = true, from = 19 * 60, to = 8 * 60, days = weekdays)
    private val daytime = QuietHours(enabled = true, from = 12 * 60 + 30, to = 14 * 60, days = weekdays)

    @Test fun `off means never quiet`() {
        assertFalse(NotificationRules.isQuiet(at(friday, 23), overnight.copy(enabled = false)))
    }

    @Test fun `overnight covers the evening of a day and the morning after`() {
        assertTrue("Friday evening", NotificationRules.isQuiet(at(friday, 22), overnight))
        assertTrue("Saturday morning, after Friday", NotificationRules.isQuiet(at(saturday, 7, 59), overnight))
        assertFalse("Saturday evening: Saturday is not a day", NotificationRules.isQuiet(at(saturday, 22), overnight))
        assertFalse("Sunday night: Sunday is not a day", NotificationRules.isQuiet(at(sunday, 23, 59), overnight))
        assertFalse("Monday morning: Sunday did not start one", NotificationRules.isQuiet(at(monday, 3), overnight))
        assertTrue("Monday evening", NotificationRules.isQuiet(at(monday, 19), overnight))
        assertFalse("Friday afternoon", NotificationRules.isQuiet(at(friday, 15), overnight))
    }

    @Test fun `the start is included, the end is not`() {
        assertTrue(NotificationRules.isQuiet(at(friday, 19, 0), overnight))
        assertFalse(NotificationRules.isQuiet(at(friday, 18, 59), overnight))
        assertTrue(NotificationRules.isQuiet(at(saturday, 7, 59), overnight))
        assertFalse(NotificationRules.isQuiet(at(saturday, 8, 0), overnight))
        assertTrue("midnight belongs to the night before", NotificationRules.isQuiet(at(saturday, 0, 0), overnight))
    }

    @Test fun `a period within a day only on its days`() {
        assertTrue(NotificationRules.isQuiet(at(friday, 12, 30), daytime))
        assertTrue(NotificationRules.isQuiet(at(friday, 13, 59), daytime))
        assertFalse(NotificationRules.isQuiet(at(friday, 14, 0), daytime))
        assertFalse(NotificationRules.isQuiet(at(friday, 12, 29), daytime))
        assertFalse(NotificationRules.isQuiet(at(saturday, 13), daytime))
    }

    @Test fun `equal ends make a whole day from that time`() {
        val allDay = QuietHours(enabled = true, from = 0, to = 0, days = setOf(6, 7))
        assertTrue(NotificationRules.isQuiet(at(saturday, 0), allDay))
        assertTrue(NotificationRules.isQuiet(at(sunday, 23, 59), allDay))
        assertFalse(NotificationRules.isQuiet(at(monday, 0), allDay))
        assertFalse(NotificationRules.isQuiet(at(friday, 23, 59), allDay))
    }

    @Test fun `no days, never quiet`() {
        assertFalse(NotificationRules.isQuiet(at(friday, 22), overnight.copy(days = emptySet())))
    }

    @Test fun `kinds turned off are not notified`() {
        val prefs = NotificationPrefs().switching(NotifyKind.PR_COMMENTS, false)
        assertNull(NotificationRules.route(NotifyKind.PR_COMMENTS, prefs, at(friday, 10)))
        assertEquals(Route.ACTIVITY, NotificationRules.route(NotifyKind.VOTES, prefs, at(friday, 10)))
        assertEquals(Route.MENTIONS, NotificationRules.route(NotifyKind.MENTIONS, prefs, at(friday, 10)))
        assertEquals(prefs, prefs.switching(NotifyKind.PR_COMMENTS, false))
        assertEquals(NotificationPrefs(), prefs.switching(NotifyKind.PR_COMMENTS, true))
    }

    @Test fun `every event kind has its setting`() {
        assertEquals(
            listOf(NotifyKind.ASSIGNMENTS, NotifyKind.MENTIONS, NotifyKind.REVIEWS, NotifyKind.PR_COMMENTS, NotifyKind.VOTES, NotifyKind.BUILDS),
            EventKind.entries.map(NotifyKind::of),
        )
    }

    @Test fun `quiet hours make everything silent, mentions too unless they bypass`() {
        val prefs = NotificationPrefs(quiet = overnight)
        val night = at(friday, 23)
        assertEquals(Route.SILENT, NotificationRules.route(NotifyKind.ASSIGNMENTS, prefs, night))
        assertEquals(Route.SILENT, NotificationRules.route(NotifyKind.MENTIONS, prefs, night))
        val bypass = prefs.copy(quiet = overnight.copy(mentionsSound = true))
        assertEquals(Route.MENTIONS, NotificationRules.route(NotifyKind.MENTIONS, bypass, night))
        assertEquals(Route.SILENT, NotificationRules.route(NotifyKind.BUILDS, bypass, night))
        assertNull("off stays off at night", NotificationRules.route(NotifyKind.MENTIONS, bypass.switching(NotifyKind.MENTIONS, false), night))
    }

    @Test fun `channels by route, versioned for the sounds`() {
        val prefs = NotificationPrefs()
        assertEquals("mentions_v1", NotificationChannels.of(Route.MENTIONS, prefs))
        assertEquals("activity_v1", NotificationChannels.of(Route.ACTIVITY, prefs))
        assertEquals("silent", NotificationChannels.of(Route.SILENT, prefs))
    }

    @Test fun `a new sound means a new channel version, the same sound does not`() {
        val prefs = NotificationPrefs()
        val bell = prefs.withSound(SoundTarget.MENTIONS, "content://media/internal/audio/media/12")
        assertEquals(2, bell.mentionsChannel)
        assertEquals(1, bell.othersChannel)
        assertEquals("mentions_v2", NotificationChannels.mentions(bell))
        assertEquals(bell, bell.withSound(SoundTarget.MENTIONS, "content://media/internal/audio/media/12"))
        val silent = bell.withSound(SoundTarget.OTHERS, "")
        assertEquals(2, silent.othersChannel)
        assertEquals("", silent.othersSound)
        val backToDefault = silent.withSound(SoundTarget.MENTIONS, null)
        assertEquals("never back to a used id", 3, backToDefault.mentionsChannel)
    }

    @Test fun `obsolete channels are older versions and those up to 0_0_16, not the current ones nor others`() {
        val prefs = NotificationPrefs(mentionsChannel = 3, othersChannel = 2)
        val existing = listOf(
            "mentions", "work_items", "pull_requests", "updates", "silent",
            "mentions_v1", "mentions_v2", "mentions_v3", "activity_v1", "activity_v2", "miscellaneous",
        )
        assertEquals(
            listOf("mentions", "work_items", "pull_requests", "mentions_v1", "mentions_v2", "activity_v1"),
            NotificationChannels.obsolete(existing, prefs),
        )
        assertEquals(setOf("mentions_v3", "activity_v2", "silent", "updates"), NotificationChannels.current(prefs))
    }

    @Test fun `settings survive encoding, and bad data falls back to the defaults`() {
        val prefs = NotificationPrefs(
            off = setOf(NotifyKind.PR_COMMENTS, NotifyKind.UPDATES), quiet = overnight.copy(mentionsSound = true),
            mentionsSound = "content://x/1", othersSound = "", mentionsChannel = 4,
        )
        assertEquals(prefs, NotificationPrefs.decode(prefs.encode()))
        assertEquals(NotificationPrefs(), NotificationPrefs.decode(null))
        assertEquals(NotificationPrefs(), NotificationPrefs.decode("{not json"))
    }
}
