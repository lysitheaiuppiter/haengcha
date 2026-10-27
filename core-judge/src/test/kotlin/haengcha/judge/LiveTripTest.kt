package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 주행 중 화면에 보여 줄 지금까지의 판정. */
class LiveTripTest {

    private fun feed(judge: TripJudge, fromSec: Int, toSec: Int, kph: (Int) -> Double) {
        for (t in fromSec..toSec) judge.add(SpeedSample(t * 1_000_000_000L, kph(t) / 3.6))
    }

    @Test
    fun `끝난 구간만 경험치에 넣고 진행 중 구간은 지난 시간을 낸다`() {
        val j = TripJudge()
        feed(j, 0, 150) { 40.0 }
        val live = j.live()
        assertEquals(listOf(SegmentMark.SAFE, SegmentMark.SAFE), live.marks)
        assertEquals(20, live.xp)
        assertEquals(2, live.safeRun)
        assertEquals(30.0, live.segmentSec, 1e-6)
        assertTrue(live.segmentClean)
        assertEquals(150 * 40 / 3.6 / 1000, live.distanceKm, 1e-6)
    }

    @Test
    fun `급가속이 있으면 그 구간에서 연속 기록이 끊긴다`() {
        val j = TripJudge()
        feed(j, 0, 90) { 40.0 }
        // 91초에 1초 만에 40→60km/h(5.6m/s²)
        feed(j, 91, 200) { 60.0 }
        val live = j.live()
        assertEquals(SegmentMark.HARSH, live.marks[1])
        assertEquals(1, live.safeRun, "세 번째 구간만 이어짐")
        assertEquals(20, live.xp)
    }

    @Test
    fun `서 있던 구간은 연속 기록을 끊지 않는다`() {
        val j = TripJudge()
        feed(j, 0, 59) { 40.0 }
        // 천천히 서서(3km/h/s) 1분 가까이 머물다 천천히 출발
        feed(j, 60, 119) { t -> maxOf(0.0, 40.0 - (t - 59) * 3.0) }
        feed(j, 120, 185) { t -> minOf(40.0, (t - 120) * 3.0) }
        val live = j.live()
        assertEquals(listOf(SegmentMark.SAFE, SegmentMark.IDLE, SegmentMark.SAFE), live.marks)
        assertEquals(2, live.safeRun)
        assertFalse(TripJudge().live().marks.isNotEmpty())
    }
}
