package haengcha.judge

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 검약 행차, 빗길·눈길, 야행, 안전띠, 기록 형식. */
class VehicleExtrasTest {

    private fun s(sec: Double, kph: Double) = SpeedSample((sec * 1e9).toLong(), kph / 3.6)

    @Test
    fun `SDK 전비가 있으면 그 값을 쓰고 없으면 잔량 변화로 계산한다`() {
        assertEquals(7.5, tripEfficiency(7.5, 10.0, 80, 79))
        // 77.4kWh의 2% = 1.548kWh로 12km
        assertEquals(12 / 1.548, tripEfficiency(null, 12.0, 80, 78)!!, 1e-9)
        assertNull(tripEfficiency(null, 2.0, 80, 70), "3km보다 짧으면 판정하지 않는다")
        assertNull(tripEfficiency(null, 12.0, 80, 80), "잔량이 줄지 않았으면 모른다")
        assertNull(tripEfficiency(null, 12.0, null, 78))
    }

    @Test
    fun `아낀 전력량은 기준 전비보다 나을 때만 쌓인다`() {
        assertEquals(10 / 5.0 - 10 / 8.0, savedKwh(10.0, 8.0), 1e-9)
        assertEquals(0.0, savedKwh(10.0, 4.0))
    }

    @Test
    fun `와이퍼를 켜고 움직인 시간과 기온으로 빗길·눈길을 가린다`() {
        val m = ExtrasMeter()
        m.belted = true
        m.begin()
        assertFalse(m.onSpeed(s(0.0, 0.0)))
        assertTrue(m.onSpeed(s(1.0, 10.0)), "처음 움직인 표본이 출발")
        m.wiper = true
        m.outsideC = 5.0
        for (t in 2..61) m.onSpeed(s(t.toDouble(), 30.0))
        assertEquals(60.0, m.wetSec, 1e-6)
        assertFalse(m.snow)
        m.outsideC = -2.0
        m.onSpeed(s(62.0, 30.0))
        assertTrue(m.snow)
        m.wiper = false
        m.onSpeed(s(63.0, 30.0))
        assertEquals(61.0, m.wetSec, 1e-6)
        val x = m.finish(1.0)
        assertEquals(true, x.belt)
        assertFalse(x.night)
    }

    @Test
    fun `서 있는 동안이나 수신이 끊긴 구간은 빗길 시간에 넣지 않는다`() {
        val m = ExtrasMeter()
        m.wiper = true
        m.begin()
        m.onSpeed(s(0.0, 0.0))
        m.onSpeed(s(10.0, 0.0))
        m.onSpeed(s(15.0, 30.0))
        assertEquals(0.0, m.wetSec)
    }

    @Test
    fun `출발 때 안전띠 상태만 남기고 전조등을 켜고 달리면 야행이다`() {
        val m = ExtrasMeter()
        m.belted = false
        m.begin()
        m.onSpeed(s(0.0, 20.0))
        m.belted = true
        m.headlights = true
        m.onSpeed(s(1.0, 20.0))
        val x = m.finish(1.0)
        assertEquals(false, x.belt)
        assertTrue(x.night)
    }

    @Test
    fun `적산거리와 잔량으로 이번 주행 전비를 낸다`() {
        val m = ExtrasMeter()
        m.soc = 80
        m.odometerKm = 1234.0
        m.begin()
        m.odometerKm = 1246.0
        m.soc = 78
        val x = m.finish(distanceKm = 2.0)
        assertEquals(12.0, x.odoKm!!, 1e-9)
        assertEquals(12 / 1.548, x.kmPerKwh!!, 1e-9)
    }

    @Test
    fun `중간값을 일지에 남겼다가 이어 간다`() {
        val m = ExtrasMeter()
        m.soc = 80
        m.odometerKm = 100.0
        m.belted = false
        m.wiper = true
        m.headlights = true
        m.begin()
        m.onSpeed(s(0.0, 20.0))
        m.onSpeed(s(1.0, 20.0))
        val back = ExtrasMeter()
        back.restore(m.snapshot())
        back.soc = 79
        back.odometerKm = 110.0
        val x = back.finish(0.0)
        assertEquals(false, x.belt)
        assertTrue(x.night)
        assertEquals(1.0, x.wetSec, 1e-6)
        assertEquals(10.0, x.odoKm!!, 1e-9)
    }

    private fun result(harsh: Int = 0) = TripResult(
        5.0, 10.0, 8, 0, 80,
        List(harsh) { HarshEvent(HarshType.ACCEL, 0, 1, 4.0) },
    )

    @Test
    fun `보너스를 판정해 경험치에 더한다`() {
        val extras = TripExtras(kmPerKwh = 7.0, odoKm = 12.0, wetSec = 150.0, night = true, belt = true, rested = true, stamp = Landmark.DAEWANGAM)
        val r = TripRecord.of(CompletedTrip(0, 1, result()), 0, 600_000, ZoneOffset.UTC, extras = extras)
        assertTrue(r.eco)
        assertEquals(Weather.RAIN, r.weather)
        assertTrue(r.weatherCalm)
        assertEquals(80 + 15 + 15 + 20 + 20, r.xp)
        val again = TripRecord.of(CompletedTrip(0, 1, result(harsh = 1)), 0, 600_000, ZoneOffset.UTC, extras = extras, firstStamp = false)
        assertFalse(again.weatherCalm, "급가속이 있으면 빗길 보너스가 없다")
        assertEquals(80 + 15 + 20, again.xp, "명소 두 번째 도착은 보너스가 없다")
    }

    @Test
    fun `새 열을 쓰고 읽고 예전 형식도 읽는다`() {
        val r = TripRecord(
            1, 2, LocalDate.of(2026, 10, 27), 3.0, 4.0, 50, 0, 0, 4, 1,
            kmPerKwh = 7.25, odoKm = 12.5, eco = true, weather = Weather.SNOW, weatherCalm = true,
            night = true, belt = false, rested = true, stamp = Landmark.PUNGSUWON,
        )
        assertEquals(r, TripRecord.parse(r.toCsv()))
        val plain = TripRecord(1, 2, LocalDate.of(2026, 10, 27), 3.0, 4.0, 50, 0, 0, 4, 1)
        assertEquals(plain, TripRecord.parse(plain.toCsv()))
        assertEquals(23, TripRecord.CSV_HEADER.split(',').size)
        val old = TripRecord.parse("1,2,2026-10-26,1.000,2.00,10,0,0,1,0,1,1,ULSAN,2")!!
        assertNull(old.belt)
        assertNull(old.stamp)
    }

    @Test
    fun `일지의 중간값은 같은 키면 마지막 값을 쓴다`() {
        val lines = listOf(
            TripJournal.header(JournalHeader(1, true, "7", 0, null)),
            TripJournal.extra("wet", "10.0"),
            TripJournal.sample(SpeedSample(2, 3.0)),
            TripJournal.extra("wet", "20.0"),
            TripJournal.extra("belt", "1"),
        )
        val j = TripJournal.parse(lines)!!
        assertEquals(mapOf("wet" to "20.0", "belt" to "1"), j.extras)
        assertEquals(1, j.samples.size)
    }
}
