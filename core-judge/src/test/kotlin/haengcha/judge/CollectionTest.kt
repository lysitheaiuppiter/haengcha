package haengcha.judge

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val friday = LocalDate.of(2026, 10, 23)

    private fun trip(
        date: LocalDate,
        hour: Int = 9,
        km: Double = 5.0,
        regions: Set<Region> = emptySet(),
        stamp: Landmark? = null,
        night: Boolean = false,
    ): TripRecord {
        val start = ZonedDateTime.of(date.atTime(hour, 0), zone).toInstant().toEpochMilli()
        return TripRecord(start, start + 600_000, date, km, 10.0, 50, 0, 0, 5, 0, regions = regions, stamp = stamp, night = night)
    }

    @Test
    fun `지역 범위는 서로 겹치지 않는다`() {
        val all = Region.entries
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]
            val b = all[j]
            assertTrue(a.center.distanceKm(b.center) > a.radiusKm + b.radiusKm, "$a ↔ $b")
        }
        // 등급마다 장면이 바뀌는 지역 테마는 울산·횡성 두 곳이다
        assertEquals(setOf(Region.ULSAN, Region.HOENGSEONG), all.filter { it.graded }.toSet())
    }

    @Test
    fun `명소 이름 열쇠말은 다른 명소와 겹치지 않는다`() {
        val keys = Landmark.entries.flatMap { l -> l.keywords.map { it to l } }
        keys.forEach { (k, l) ->
            assertEquals(l, Landmark.match(k), k)
            keys.filter { it.second != l }.forEach { (other, _) -> assertFalse(other.contains(k) || k.contains(other), "$k / $other") }
        }
        assertTrue(Region.entries.all { r -> Landmark.entries.count { it.region == r } >= 2 })
        assertEquals(Landmark.JEONJU_HANOK, Landmark.match("전주 한옥마을 공영주차장"))
    }

    @Test
    fun `칭호는 기록에서 계산하고 어려운 것부터 보인다`() {
        assertEquals(emptyList(), summarize(emptyList(), friday, zone = zone).titles)
        val records = List(5) { trip(friday.minusWeeks(it.toLong()), hour = 18) } + trip(friday, hour = 5, km = 20.0, night = true)
        val p = summarize(records, friday, zone = zone)
        assertTrue(Title.FIRST_TRIP in p.titles)
        assertTrue(Title.FRIDAY_5 in p.titles)
        assertTrue(Title.LI_100 in p.titles) // 45km
        assertFalse(Title.DAWN_3 in p.titles)
        assertEquals(p.titles.sortedByDescending { it.tier }, p.titles)
        assertTrue(Title.SMITH_3 in summarize(records, friday, shieldsEarned = 3, zone = zone).titles)
    }

    @Test
    fun `명소를 모두 돌면 명소 완주, 도감 7곳이면 팔도 유람객`() {
        val busan = Landmark.entries.filter { it.region == Region.BUSAN }
        val p = summarize(busan.map { trip(friday, stamp = it, regions = setOf(Region.BUSAN)) }, friday, zone = zone)
        assertTrue(Title.TOWN_ALL in p.titles)
        val regions = Region.entries.take(7).map { trip(friday, regions = setOf(it)) }
        assertTrue(Title.REGIONS_7 in summarize(regions, friday, zone = zone).titles)
        assertFalse(Title.REGIONS_7 in summarize(regions.drop(1), friday, zone = zone).titles)
    }

    @Test
    fun `다섯 번째 야행에 부엉이가 합류하고 칭호도 함께 얻는다`() {
        val before = summarize(List(4) { trip(friday, night = true) }, friday, zone = zone)
        val after = summarize(List(5) { trip(friday, night = true) }, friday, zone = zone)
        assertFalse(Buddy.OWL in before.buddies)
        assertTrue(Buddy.OWL in after.buddies)
        assertFalse(Title.NIGHT_5 in before.titles)
        assertTrue(Title.NIGHT_5 in after.titles)
    }

    @Test
    fun `처음 가 본 지역은 도감에 오르고 도감 지역 등급에는 지역 이름이 붙는다`() {
        val before = summarize(emptyList(), friday, zone = zone)
        val after = summarize(listOf(trip(friday, regions = setOf(Region.DAEJEON))), friday, zone = zone)
        assertTrue(before.regulars.none { it.region == Region.DAEJEON })
        assertTrue(after.regulars.any { it.region == Region.DAEJEON })
        assertEquals("지역 도감에 대전 한빛탑이 올랐습니다.", Announcements.regionCollected(Region.DAEJEON))
        assertEquals("지역 도감에 부산 바다가 올랐습니다.", Announcements.regionCollected(Region.BUSAN))
        assertEquals("단골 등급이 올랐습니다. 대전 길손입니다.", Announcements.gradeUp(Region.DAEJEON, 1))
        assertEquals("단골 등급이 올랐습니다. 동 고래입니다.", Announcements.gradeUp(Region.ULSAN, 1))
        assertEquals("강릉에 오셨습니다. 커피 향이 반깁니다.", Announcements.regionEntered(Region.GANGNEUNG))
    }

    @Test
    fun `받침에 따라 조사를 고른다`() {
        assertEquals("이", "한빛탑".josa("이", "가"))
        assertEquals("가", "바다".josa("이", "가"))
        assertEquals("가", "1++".josa("이", "가"))
    }
    @Test
    fun `숨은 칭호와 전설은 쉽게 열리지 않는다`() {
        val rainyNight = trip(friday, night = true).copy(weather = Weather.RAIN, weatherCalm = true)
        val eve = trip(LocalDate.of(2026, 12, 31), hour = 23)
        val p = summarize(listOf(rainyNight, eve), friday, zone = zone)
        assertTrue(Title.RAINY_NIGHT in p.titles)
        assertTrue(Title.NEW_YEAR in p.titles)
        assertTrue(Title.RAINY_NIGHT.hidden && Title.RAINY_NIGHT.rarity == Rarity.RARE)
        // 전설은 몇 달을 달려야 한다
        assertTrue(Title.entries.count { it.rarity == Rarity.LEGEND } >= 5)
        assertTrue(Buddy.entries.filter { it.rarity == Rarity.LEGEND }.none { it in p.buddies })
        val far = summarize(List(100) { trip(friday.minusDays(it.toLong()), km = 40.0) }, friday, zone = zone)
        assertEquals(100, far.bestStreak)
        assertTrue(Buddy.DRAGON in far.buddies && Buddy.GUMIHO in far.buddies)
        assertTrue(Title.STREAK_100 in far.titles && Title.LI_10000 in far.titles)
        assertEquals(Title.entries.filter { it in far.titles }.maxOf { it.tier }, far.titles.first().tier)
        assertFalse(Buddy.PHOENIX in far.buddies)
    }
}
