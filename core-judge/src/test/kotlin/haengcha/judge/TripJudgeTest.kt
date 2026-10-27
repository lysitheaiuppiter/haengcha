package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals

class TripJudgeTest {

    private fun judge(samples: List<SpeedSample>): TripResult {
        val judge = TripJudge()
        samples.forEach { judge.add(it) }
        return judge.finish()
    }

    @Test
    fun `정속 5분은 안전 구간 5개`() {
        val result = judge(Profile(50.0).hold(300.0).build())
        assertEquals(5, result.safeSegments)
        assertEquals(0, result.calmSegments)
        assertEquals(50, result.xp)
        assertEquals(4.17, result.distanceKm, 0.01)
    }

    @Test
    fun `정체 구간을 차분히 달리면 평정심 보너스를 받는다`() {
        val result = judge(Profile(15.0).hold(180.0).build())
        assertEquals(3, result.calmSegments)
        assertEquals(45, result.xp)
    }

    @Test
    fun `급가속이 있던 구간만 경험치를 받지 못한다`() {
        // 71초쯤 50→80km/h 2초 급가속, 이어서 80→50km/h 3초(-2.8m/s²)는 급감속 아님
        val result = judge(Profile(50.0).hold(70.0).ramp(2.0, 80.0).ramp(3.0, 50.0).hold(225.0).build())
        assertEquals(1, result.harshAccel)
        assertEquals(0, result.harshDecel)
        assertEquals(4, result.safeSegments)
        assertEquals(40, result.xp)
    }

    @Test
    fun `오래 서 있던 구간과 짧게 달린 구간은 세지 않는다`() {
        val samples = Profile(50.0).hold(60.0).ramp(5.0, 0.0).hold(120.0).ramp(10.0, 50.0).hold(60.0).build()
        val result = judge(samples)
        assertEquals(2, result.safeSegments)
        assertEquals(20, result.xp)
    }

    @Test
    fun `표본이 없으면 빈 결과`() {
        assertEquals(0, TripJudge().finish().xp)
    }
}
