package com.familydocs.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ExpiryAndNotesTest {
    private val today = LocalDate.of(2026, 11, 2)

    @Test fun parsesTheUsualFormats() {
        assertEquals(LocalDate.of(2027, 5, 12), Expiry.parse("12/05/2027"))
        assertEquals(LocalDate.of(2027, 5, 12), Expiry.parse(" 12.05.2027 "))
        assertEquals(LocalDate.of(2027, 5, 12), Expiry.parse("2027-05-12"))
        assertNull(Expiry.parse("31/02/2027"))
        assertNull(Expiry.parse("soon"))
    }

    @Test fun levelsAndLabels() {
        assertNull(Expiry.level(today.plusDays(200), today))
        assertEquals(Expiry.Level.SIX_MONTHS, Expiry.level(today.plusDays(150), today))
        assertEquals(Expiry.Level.MONTH, Expiry.level(today.plusDays(20), today))
        assertEquals(Expiry.Level.EXPIRED, Expiry.level(today.minusDays(1), today))
        assertEquals("Expires in 20 days", Expiry.label(today.plusDays(20), today))
        assertEquals("Expires in 5 months", Expiry.label(today.plusDays(150), today))
        assertEquals("Expired 1 Nov 2026", Expiry.label(today.minusDays(1), today))
    }

    @Test fun passportAndTrips() {
        val end = LocalDate.of(2027, 1, 10)
        assertEquals("expires less than 6 months after the trip", Expiry.tripProblem(LocalDate.of(2027, 5, 1), end))
        assertEquals("expires before the trip ends", Expiry.tripProblem(LocalDate.of(2027, 1, 5), end))
        assertNull(Expiry.tripProblem(LocalDate.of(2028, 1, 1), end))
    }

    @Test fun notesKeepBothSides() {
        val base = "Hotel Rambagh\nTrain 12956"
        val mine = "Hotel Rambagh\nCheck-in 2pm\nTrain 12956"
        val theirs = "Hotel Rambagh\nTrain 12956\nCarry sunscreen"
        assertEquals("Hotel Rambagh\nCheck-in 2pm\nTrain 12956\nCarry sunscreen", NotesMerge.merge(base, mine, theirs))
    }

    @Test fun notesDeletionsAndEdits() {
        val base = "Hotel Rambagh\nTrain 12956"
        // you changed the train line, they added one
        assertEquals("Hotel Rambagh\nTrain 12958\nTaxi at 6", NotesMerge.merge(base, "Hotel Rambagh\nTrain 12958", "Hotel Rambagh\nTrain 12956\nTaxi at 6"))
        // they deleted the hotel, you didn't touch it
        assertEquals("Train 12956\nTaxi", NotesMerge.merge(base, "Hotel Rambagh\nTrain 12956\nTaxi", "Train 12956"))
        // only one side changed
        assertEquals("x", NotesMerge.merge(base, base, "x"))
        assertEquals("y", NotesMerge.merge(base, "y", base))
    }
}
