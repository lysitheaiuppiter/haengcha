package haengcha.judge

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {

    private val day = LocalDate.of(2026, 10, 26)

    private fun trip(date: LocalDate, vararg regions: Region, guardian: Int = 0) =
        TripRecord(0, 0, date, 10.0, 20.0, 100, 0, 0, 10, 0, regions = regions.toSet(), guardian = guardian)

    @Test
    fun `거리와 지역 판정`() {
        // 울산시청 ↔ 횡성군청 직선거리는 약 247km
        assertTrue(Region.ULSAN.center.distanceKm(Region.HOENGSEONG.center) in 240.0..255.0)
        assertEquals(Region.ULSAN, Region.at(GeoPoint(35.55, 129.30)))
        assertEquals(Region.HOENGSEONG, Region.at(GeoPoint(37.49, 127.99)))
        assertEquals(Region.SEOUL, Region.at(GeoPoint(37.5665, 126.9780)))
        assertNull(Region.at(GeoPoint(37.2636, 127.0286))) // 수원은 도감에 없다
    }

    @Test
    fun `단골 등급은 방문한 날 수로 오른다`() {
        val oneDay = regulars(listOf(trip(day, Region.ULSAN), trip(day, Region.ULSAN)))
        assertEquals(1, oneDay.single().visitDays)
        assertEquals("노말 고래", oneDay.single().gradeLabel)
        assertEquals(2, oneDay.single().daysToNext)

        val threeDays = regulars((0L..2L).map { trip(day.plusDays(it), Region.ULSAN) })
        assertEquals("동 고래", threeDays.single().gradeLabel)
        assertEquals(4, threeDays.single().daysToNext)

        val top = regulars((0L..39L).map { trip(day.plusDays(it), Region.HOENGSEONG) })
        assertEquals("투뿔(1++) 한우", top.single().gradeLabel)
        assertNull(top.single().daysToNext)
    }

    @Test
    fun `최근에 간 지역이 앞에 온다`() {
        val list = regulars(listOf(trip(day, Region.HOENGSEONG), trip(day.plusDays(1), Region.ULSAN)))
        assertEquals(listOf(Region.ULSAN, Region.HOENGSEONG), list.map { it.region })
    }

    @Test
    fun `지역과 아이 지킴이 열을 저장하고 예전 형식도 읽는다`() {
        val record = trip(day, Region.ULSAN, Region.HOENGSEONG, guardian = 2)
        assertEquals(record, TripRecord.parse(record.toCsv()))
        val none = trip(day)
        assertEquals(none, TripRecord.parse(none.toCsv()))
        val legacy = TripRecord.parse("1,2,2026-10-26,1.000,2.00,10,0,0,1,0,1,1")!!
        assertEquals(emptySet(), legacy.regions)
        assertEquals(0, legacy.guardian)
    }

    @Test
    fun `세 번째 방문한 날 등급이 오른다`() {
        val before = summarize((0L..1L).map { trip(day.plusDays(it), Region.ULSAN) }, day.plusDays(2))
        val after = summarize((0L..2L).map { trip(day.plusDays(it), Region.ULSAN) }, day.plusDays(2))
        assertEquals(0, before.regulars.single { it.region == Region.ULSAN }.grade)
        assertEquals(1, after.regulars.single { it.region == Region.ULSAN }.grade)
    }
}
