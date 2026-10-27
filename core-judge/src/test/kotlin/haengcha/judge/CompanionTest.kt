package haengcha.judge

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionTest {

    private fun trip(date: LocalDate) = TripRecord(0, 0, date, 5.0, 10.0, 50, 0, 0, 5, 0)

    @Test
    fun `이달 주행 횟수가 목표에 닿으면 거북이가 합류한다`() {
        val today = LocalDate.of(2026, 10, 26)
        // 지난달 주행은 세지 않는다
        val records = List(4) { trip(LocalDate.of(2026, 9, 30)) } + List(9) { trip(today) }
        val before = summarize(records, today)
        assertEquals(9, before.companion.tripsThisMonth)
        assertFalse(before.companion.joined)
        val after = summarize(records + trip(today), today)
        assertTrue(after.companion.joined)
        val lines = Announcements.tripEndLines(TripResult(5.0, 10.0, 5, 0, 50, emptyList()), before, after)
        assertTrue(Announcements.COMPANION_JOINED in lines)
        assertEquals(10, after.tripsToday)
        assertEquals(50.0, after.kmToday, 1e-9)
    }
}
