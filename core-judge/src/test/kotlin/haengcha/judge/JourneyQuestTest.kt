package haengcha.judge

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 옛길 원정, 대장간 퀘스트와 개근 보호권, 안전띠 개근, 명소 도착. */
class JourneyQuestTest {

    @Test
    fun `쌓은 거리로 지난 지점과 다음 지점을 낸다`() {
        val j = journey(30.0)
        assertEquals(0, j.lap)
        assertEquals(Station.PANGYO, j.reached)
        assertEquals(Station.YONGIN, j.next)
        assertEquals(10.0, j.kmToNext, 1e-9)
        val second = journey(380.0 + 25.0)
        assertEquals(1, second.lap)
        assertEquals(Station.PANGYO, second.reached)
        assertEquals(Station.DONGNAE, journey(379.9).next)
        assertEquals(Station.HANYANG, journey(380.0).reached, "동래에 닿으면 한양에서 다시 떠난다")
    }

    @Test
    fun `이번 주행으로 새로 닿은 지점을 바퀴를 넘어 센다`() {
        assertEquals(listOf(Station.PANGYO), stationsReached(20.0, 25.0))
        assertEquals(emptyList(), stationsReached(22.0, 30.0), "이미 지난 판교는 다시 세지 않는다")
        assertEquals(listOf(Station.YANGSAN, Station.DONGNAE, Station.PANGYO), stationsReached(360.0, 405.0))
        assertEquals(emptyList(), stationsReached(10.0, 10.0))
    }

    @Test
    fun `적산거리와 차속 거리 중 큰 값을 원정 거리로 쓴다`() {
        val day = LocalDate.of(2026, 10, 27)
        val records = listOf(
            TripRecord(0, 0, day, 2.0, 5.0, 10, 0, 0, 1, 0, odoKm = 20.0),
            TripRecord(0, 0, day, 3.0, 5.0, 10, 0, 0, 1, 0),
            // 적산거리가 늘지 않은 주행(에뮬레이터)
            TripRecord(0, 0, day, 1.5, 5.0, 10, 0, 0, 1, 0, odoKm = 0.0),
        )
        assertEquals(24.5, summarize(records, day).journey.totalKm, 1e-9)
    }

    @Test
    fun `경고등이 켜지면 퀘스트가 열리고 꺼지면 마친다`() {
        assertEquals(setOf(Quest.TIRE), Quest.active(setOf("TPMS", "SEAT_BELT")))
        assertEquals(setOf(Quest.OIL), Quest.active(emptySet(), oilLow = true))
        val opened = questChange(emptyMap(), setOf(Quest.TIRE, Quest.OIL), 0)
        assertEquals(setOf(Quest.TIRE, Quest.OIL), opened.opened)
        val done = questChange(mapOf(Quest.TIRE to 0L, Quest.OIL to 0L), setOf(Quest.OIL), 60_000)
        assertEquals(setOf(Quest.TIRE), done.completed)
        assertTrue(done.opened.isEmpty())
    }

    @Test
    fun `잠깐 켜졌다 꺼진 경고등은 정비로 보지 않는다`() {
        val flicker = questChange(mapOf(Quest.OIL to 10_000L), emptySet(), 20_000)
        assertTrue(flicker.completed.isEmpty())
        assertEquals(setOf(Quest.OIL), flicker.dropped)
    }

    private val monday = LocalDate.of(2026, 10, 19)
    private fun day(offset: Long, harsh: Int = 0, unbelted: Boolean = false) =
        DayDriving(monday.plusDays(offset), 20.0, 5.0, harsh, unbelted)

    @Test
    fun `보호권이 있으면 끊길 날을 넘기고 이어 간다`() {
        val days = listOf(day(0), day(1), day(2, harsh = 3), day(3))
        assertEquals(Streak(1, 0), streakOf(days))
        assertEquals(Streak(3, 1), streakOf(days, shields = 1))
        // 기록이 없을 때(0일) 끊긴 날에는 보호권을 쓰지 않는다
        assertEquals(Streak(1, 0), streakOf(listOf(day(0, harsh = 3), day(1)), shields = 1))
    }

    @Test
    fun `안전띠를 매지 않고 출발한 날은 개근으로 세지 않는다`() {
        assertEquals(1, currentStreak(listOf(day(0), day(1), day(2, unbelted = true), day(3))))
        val records = listOf(
            TripRecord(0, 0, monday, 5.0, 20.0, 50, 0, 0, 5, 0, belt = true),
            TripRecord(0, 0, monday.plusDays(1), 5.0, 20.0, 50, 0, 0, 5, 0, belt = false),
        )
        assertEquals(0, summarize(records, monday.plusDays(1)).streak)
        val shielded = summarize(records, monday.plusDays(1), shieldsEarned = 1)
        assertEquals(1, shielded.streak)
        assertEquals(0, shielded.shields)
        assertEquals(1, shielded.shieldsUsed)
    }

    @Test
    fun `명소는 목적지 이름으로 알아보고 도착은 반경으로 판정한다`() {
        assertEquals(Landmark.DAEWANGAM, Landmark.match("울산 대왕암 공원"))
        assertEquals(Landmark.TAEHWAGANG, Landmark.match("태화강 국가정원 주차장"))
        assertNull(Landmark.match("판교역"))
        assertNull(Landmark.match(""))
        val dest = Destination("대왕암공원", GeoPoint(35.4925, 129.4395))
        val far = GeoPoint(35.5150, 129.4250)
        val near = GeoPoint(35.4935, 129.4395)
        assertTrue(arrived(dest, far, near))
        assertTrue(!arrived(dest, far, GeoPoint(35.5000, 129.4395)), "반경 밖에서 끝냄")
        assertTrue(!arrived(null, far, near))
        assertTrue(!arrived(dest, far, null))
    }

    @Test
    fun `목적지 근처에서 출발해 근처에서 끝난 주행은 도착이 아니다`() {
        val dest = Destination("대왕암공원", GeoPoint(35.4925, 129.4395))
        val near = GeoPoint(35.4930, 129.4393)
        assertTrue(!arrived(dest, near, near), "제자리 주행")
        assertTrue(!arrived(dest, GeoPoint(35.4940, 129.4400), near), "주차장 안에서 옮김")
        assertTrue(arrived(dest, null, near), "출발 위치를 모르면 끝낸 곳만 본다")
    }

    @Test
    fun `곳간과 횟수를 모은다`() {
        val day = LocalDate.of(2026, 10, 27)
        val p = summarize(
            listOf(
                TripRecord(0, 0, day, 10.0, 20.0, 50, 0, 0, 5, 0, kmPerKwh = 8.0, eco = true, night = true, stamp = Landmark.GANJEOLGOT),
                TripRecord(0, 0, day, 10.0, 20.0, 50, 0, 0, 5, 0, weather = Weather.RAIN, weatherCalm = true, rested = true),
            ),
            day,
        )
        assertEquals(10 / 5.0 - 10 / 8.0, p.rice, 1e-9)
        assertEquals(1, p.ecoTrips)
        assertEquals(1, p.nightTrips)
        assertEquals(1, p.weatherCalmTrips)
        assertEquals(1, p.restCount)
        assertEquals(setOf(Landmark.GANJEOLGOT), p.stamps)
    }

    @Test
    fun `주차 후 안내에 새 문장을 넣는다`() {
        val day = LocalDate.of(2026, 10, 27)
        val before = summarize(listOf(TripRecord(0, 0, day, 20.0, 20.0, 10, 0, 0, 5, 0)), day)
        val record = TripRecord(0, 0, day, 5.0, 10.0, 10, 0, 0, 5, 0, eco = true, weather = Weather.SNOW, weatherCalm = true, stamp = Landmark.PUNGSUWON)
        val after = summarize(listOf(TripRecord(0, 0, day, 20.0, 20.0, 10, 0, 0, 5, 0), record), day)
        val lines = Announcements.tripEndLines(TripResult(5.0, 10.0, 5, 0, 50, emptyList()), before, after, record = record)
        assertEquals(
            listOf(Announcements.ECO, Announcements.SNOW_CALM, Announcements.stamped(Landmark.PUNGSUWON), Announcements.stationReached(Station.PANGYO), Announcements.CLOSING),
            lines,
        )
        assertTrue(Announcements.all().containsAll(lines))
    }
}
