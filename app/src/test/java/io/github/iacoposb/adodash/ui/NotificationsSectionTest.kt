package io.github.iacoposb.adodash.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class NotificationsSectionTest {
    @Test fun `weekdays start where the locale starts the week`() {
        assertEquals(DayOfWeek.MONDAY, weekdays(Locale.ITALY).first())
        assertEquals(DayOfWeek.SUNDAY, weekdays(Locale.US).first())
        assertEquals(DayOfWeek.entries.toSet(), weekdays(Locale.US).toSet())
        assertEquals(7, weekdays(Locale.US).size)
    }

    @Test fun `day names are short and capitalized`() {
        assertEquals("Lun", dayLabel(DayOfWeek.MONDAY, Locale.ITALY))
        assertEquals("Mon", dayLabel(DayOfWeek.MONDAY, Locale.US))
    }

    @Test fun `times with 24 or 12 hours`() {
        assertEquals("19:00", timeLabel(19 * 60, is24Hour = true, Locale.ITALY))
        assertEquals("08:05", timeLabel(8 * 60 + 5, is24Hour = true, Locale.ITALY))
        assertEquals("7:00 PM", timeLabel(19 * 60, is24Hour = false, Locale.US))
    }
}
