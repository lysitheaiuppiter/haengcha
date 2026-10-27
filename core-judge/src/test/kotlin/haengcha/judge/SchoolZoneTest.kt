package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchoolZoneTest {

    private fun sec(s: Double) = (s * 1e9).toLong()

    private fun pass(kph: List<Double>): SchoolZonePass =
        SchoolZonePass(enterNanos = 0).apply { kph.forEachIndexed { i, v -> onSpeed(SpeedSample(sec(i * 0.1), v / 3.6)) } }

    @Test
    fun `시연 구역 안팎 판정`() {
        val zone = SchoolZone.DEMO.single()
        assertEquals(zone, SchoolZone.at(zone.center))
        assertEquals(zone, SchoolZone.at(GeoPoint(35.5460, 129.3300))) // 북쪽 약 220m
        assertNull(SchoolZone.at(GeoPoint(35.5480, 129.3300))) // 북쪽 약 440m
    }

    @Test
    fun `30km·h 이하로 10초 넘게 지나면 아이 지킴이`() {
        assertTrue(pass(List(150) { 28.0 }).finish(sec(15.0)))
    }

    @Test
    fun `한 번이라도 30km·h를 넘거나 너무 짧으면 보너스 없음`() {
        assertFalse(pass(List(150) { if (it == 70) 34.0 else 25.0 }).finish(sec(15.0)))
        assertFalse(pass(List(50) { 20.0 }).finish(sec(5.0)))
    }
}
