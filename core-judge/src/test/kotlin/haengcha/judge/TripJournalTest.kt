package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripJournalTest {

    private val plan = InspectionPlan(20.0, InspectionRule(windowSec = 120.0, minMovingSec = 60.0, mapaeXp = 30))

    /** 10Hz로 0→40km/h 가속 후 4분 정속, 정지. */
    private val samples = buildList {
        var t = 0L
        repeat(150) { add(SpeedSample(t, it / 150.0 * 11.1)); t += 100_000_000 }
        repeat(2400) { add(SpeedSample(t, 11.1)); t += 100_000_000 }
        repeat(150) { add(SpeedSample(t, 11.1 - it / 150.0 * 11.1)); t += 100_000_000 }
    }

    @Test
    fun `일지를 읽고 쓴다`() {
        val h = JournalHeader(5, true, "12", 1_700_000_000_000, plan)
        val lines = listOf(TripJournal.header(h)) + samples.take(3).map(TripJournal::sample) +
            listOf(TripJournal.region(Region.ULSAN), TripJournal.GUARDIAN, "s,12") // 쓰다 끊긴 줄
        val j = TripJournal.parse(lines)!!
        assertEquals(h, j.header)
        assertEquals(samples.take(3).map { it.tNanos }, j.samples.map { it.tNanos })
        assertEquals(setOf(Region.ULSAN), j.regions)
        assertEquals(1, j.guardian)
        assertNull(TripJournal.parse(listOf("s,1,2.0")))
        assertNull(TripJournal.parse(emptyList()))
    }

    @Test
    fun `일지로 이어 간 주행은 끊기지 않은 주행과 결과가 같다`() {
        val whole = TripTracker(planInspection = { plan })
        whole.onGear(Gear.DRIVE, 0)
        samples.forEach { whole.onSpeed(it) }
        val expected = whole.onGear(Gear.PARK, samples.last().tNanos)!!

        // 절반쯤에서 프로세스가 죽었다가, 일지를 다시 넣어 이어 간다
        val first = TripTracker(planInspection = { plan })
        first.onGear(Gear.DRIVE, 0)
        val lines = mutableListOf(TripJournal.header(JournalHeader(first.tripStartNanos, first.readsGear, "1", 0, first.plan)))
        samples.take(1200).forEach { first.onSpeed(it); lines += TripJournal.sample(it) }

        val j = TripJournal.parse(lines)!!
        val resumed = TripTracker()
        resumed.resume(j.header.startNanos, j.header.gearKnown, j.header.inspection)
        j.samples.forEach { resumed.onSpeed(it) }
        samples.drop(1200).forEach { resumed.onSpeed(it) }
        val actual = resumed.onGear(Gear.PARK, samples.last().tNanos)!!
        assertEquals(expected, actual)
    }
}
