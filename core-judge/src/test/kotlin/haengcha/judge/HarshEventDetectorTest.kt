package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarshEventDetectorTest {

    private fun detect(samples: List<SpeedSample>, config: HarshConfig = HarshConfig()): List<HarshEvent> {
        val detector = HarshEventDetector(config)
        return samples.flatMap { detector.add(it) } + detector.flush()
    }

    @Test
    fun `정속 주행에서는 아무 건도 없다`() {
        assertTrue(detect(Profile(60.0).hold(30.0).build()).isEmpty())
    }

    @Test
    fun `완만한 가속과 감속은 급가속·급감속이 아니다`() {
        // 0→50km/h 10초(1.4m/s²), 50→0km/h 6초(-2.3m/s²)
        assertTrue(detect(Profile(0.0).ramp(10.0, 50.0).hold(3.0).ramp(6.0, 0.0).build()).isEmpty())
    }

    @Test
    fun `급가속 한 번은 한 건이고 최대 가속을 남긴다`() {
        // 50→80km/h 2초 = 4.17m/s²
        val events = detect(Profile(50.0).hold(3.0).ramp(2.0, 80.0).hold(5.0).build())
        assertEquals(1, events.size)
        assertEquals(HarshType.ACCEL, events[0].type)
        assertEquals(4.17, events[0].peak, 0.05)
    }

    @Test
    fun `급감속 한 번은 한 건이다`() {
        // 90→40km/h 2.5초 = -5.56m/s²
        val events = detect(Profile(90.0).hold(3.0).ramp(2.5, 40.0).hold(5.0).build())
        assertEquals(listOf(HarshType.DECEL), events.map { it.type })
        assertEquals(-5.56, events[0].peak, 0.05)
    }

    @Test
    fun `3초 안에 다시 넘으면 한 건으로 묶고 넘어서면 따로 센다`() {
        val merged = detect(Profile(50.0).hold(3.0).ramp(1.5, 70.0).hold(1.5).ramp(1.5, 90.0).hold(5.0).build())
        assertEquals(1, merged.size)
        val separate = detect(Profile(50.0).hold(3.0).ramp(1.5, 70.0).hold(6.0).ramp(1.5, 90.0).hold(5.0).build())
        assertEquals(2, separate.size)
    }

    @Test
    fun `수신이 끊긴 뒤 차속이 뛰어도 판정하지 않는다`() {
        val samples = Profile(30.0).hold(2.0).gap(3.0).ramp(0.1, 60.0).hold(5.0).build()
        assertTrue(detect(samples).isEmpty())
    }

    @Test
    fun `1km_h 단위 흔들림이 섞인 정속은 급가속이 아니다`() {
        val wobble = listOf(0, 1, -1, 0, 1, 0, -1, 1, -1, 0)
        val samples = (0 until 300).map { i -> SpeedSample(i * 100_000_000L, (60 + wobble[i % wobble.size]) / 3.6) }
        assertTrue(detect(samples).isEmpty())
    }

    @Test
    fun `시간이 거꾸로 가는 표본은 버린다`() {
        val detector = HarshEventDetector()
        detector.add(SpeedSample(1_000_000_000L, 10.0))
        assertTrue(detector.add(SpeedSample(1_000_000_000L, 30.0)).isEmpty())
        assertTrue(detector.add(SpeedSample(500_000_000L, 30.0)).isEmpty())
        assertTrue(detector.flush().isEmpty())
    }
}
