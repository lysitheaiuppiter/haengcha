package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * speedprobe가 에뮬레이터에서 남긴 실제 수신 기록을 재생한다.
 * 시나리오(tools/vc_drive.py 기본값)에는 급가속(50→90km/h 3초)과 급감속(90→40km/h 2.5초)이 한 번씩 들어 있다.
 */
class ProbeReplayTest {

    private fun load(name: String): List<SpeedSample> =
        javaClass.getResourceAsStream(name)!!.bufferedReader().useLines { lines ->
            lines.drop(1)
                .map { it.split(',') }
                .filter { it[0] == "PROP" && it[3] == "PERF_VEHICLE_SPEED" && it[5] == "0" }
                .map { SpeedSample(it[2].toLong(), it[6].toDouble()) }
                .toList()
        }

    private val samples = load("/probe/vc_drive_2026-10-26.csv")

    @Test
    fun `급가속과 급감속을 한 번씩 찾는다`() {
        val detector = HarshEventDetector()
        val events = samples.flatMap { detector.add(it) } + detector.flush()

        assertEquals(listOf(HarshType.ACCEL, HarshType.DECEL), events.map { it.type })
        assertTrue(events[0].peak in 3.5..4.5, "급가속 최대 ${events[0].peak}")
        assertTrue(events[1].peak in -6.5..-5.0, "급감속 최대 ${events[1].peak}")
        // 시나리오상 급가속 시작과 급감속 시작은 10초 떨어져 있다
        val apart = (events[1].startNanos - events[0].startNanos) / 1e9
        assertTrue(apart in 8.0..12.0, "간격 $apart 초")
    }

    @Test
    fun `주행 거리와 경험치`() {
        val judge = TripJudge()
        samples.forEach { judge.add(it) }
        val result = judge.finish()

        // 시나리오 이론 거리 약 459m
        assertTrue(result.distanceKm in 0.42..0.50, "거리 ${result.distanceKm}")
        assertEquals(1, result.harshAccel)
        assertEquals(1, result.harshDecel)
        // 움직인 35초가 모두 첫 1분 구간에 있고 그 구간에 급가속·급감속이 있으므로 경험치 없음
        assertEquals(0, result.xp)
    }
}
