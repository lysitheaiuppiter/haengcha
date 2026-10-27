package haengcha.judge

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripTrackerTest {

    private val sec = 1_000_000_000L

    @Test
    fun `P단을 벗어나면 시작하고 P단으로 돌아오면 끝난다`() {
        val tracker = TripTracker()
        assertNull(tracker.onGear(Gear.PARK, 0))
        assertNull(tracker.onGear(Gear.DRIVE, 1 * sec))
        assertTrue(tracker.inTrip)

        val samples = Profile(0.0).ramp(10.0, 50.0).hold(120.0).ramp(10.0, 0.0).build()
            .map { it.copy(tNanos = it.tNanos + 2 * sec) }
        samples.forEach { assertNull(tracker.onSpeed(it)) }

        val trip = assertNotNull(tracker.onGear(Gear.PARK, samples.last().tNanos + sec))
        assertFalse(tracker.inTrip)
        assertEquals(1 * sec, trip.startNanos)
        assertEquals(20, trip.result.xp)
    }

    @Test
    fun `P단에서 들어온 차속은 버린다`() {
        val tracker = TripTracker()
        tracker.onGear(Gear.PARK, 0)
        Profile(30.0).hold(10.0).build().forEach { assertNull(tracker.onSpeed(it)) }
        assertFalse(tracker.inTrip)
    }

    @Test
    fun `기어를 못 읽으면 움직일 때 시작하고 3분 서 있으면 끝난다`() {
        val tracker = TripTracker()
        val samples = Profile(0.0).hold(5.0).ramp(10.0, 40.0).hold(60.0).ramp(10.0, 0.0).hold(200.0).build()
        val trips = samples.mapNotNull { tracker.onSpeed(it) }
        assertEquals(1, trips.size)
        assertFalse(tracker.inTrip)
        // 정차 시작(약 85초)에서 180초 뒤에 끝난다
        assertEquals(265.0, trips[0].endNanos / 1e9, 1.0)
        assertTrue(trips[0].result.distanceKm > 0.7)
    }

    @Test
    fun `기록은 CSV 한 줄로 저장하고 다시 읽는다`() {
        val result = TripResult(1.234, 2.5, 2, 1, 25, emptyList())
        val record = TripRecord.of(CompletedTrip(0, 1, result), 1_792_990_000_000, 1_792_990_150_000, ZoneOffset.ofHours(9))
        assertEquals(LocalDate.of(2026, 10, 26), record.date)
        assertEquals(record, TripRecord.parse(record.toCsv())?.copy(distanceKm = record.distanceKm))
        assertNull(TripRecord.parse(TripRecord.CSV_HEADER))
        assertNull(TripRecord.parse("1,2,3"))
    }

    @Test
    fun `기록을 모아 레벨·오늘 경험치·개근을 낸다`() {
        val today = LocalDate.of(2026, 10, 28)
        fun trip(date: LocalDate, xp: Int, harsh: Int = 0) =
            TripRecord(0, 0, date, 15.0, 30.0, xp, harsh, 0, 0, 0)
        val summary = summarize(
            listOf(trip(today.minusDays(2), 300), trip(today.minusDays(1), 250), trip(today, 100), trip(today, 30)),
            today,
        )
        assertEquals(680, summary.totalXp)
        assertEquals(3, summary.level)
        assertEquals(1000 - 680, summary.xpToNextLevel)
        assertEquals(130, summary.todayXp)
        assertEquals(3, summary.streak)
        assertEquals(listOf(JoseonTheme.OX_CART, JoseonTheme.RICKSHAW), summary.themes)
    }
}
