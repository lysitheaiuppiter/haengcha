package haengcha.drive

import haengcha.judge.Announcements
import haengcha.judge.Buddy
import haengcha.judge.Destination
import haengcha.judge.GeoPoint
import haengcha.judge.Landmark
import haengcha.judge.Quest
import haengcha.judge.RestRule
import haengcha.judge.Station
import haengcha.judge.Title
import haengcha.judge.Weather
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 주행 엔진 통합 시험. 가짜 차량 신호로 주행을 재생해 저장·안내·축하 연출까지 확인한다.
 * 2026-10-27 에뮬레이터 시연에서 잡은 문제(퀘스트 흔들림, 적산거리 0, 제자리 도착, 기온 덮어쓰기)를 시나리오로 남긴다.
 */
class DriveEngineTest {

    private val daewangam = Destination("대왕암공원", GeoPoint(35.4925, 129.4395))
    private val nearDaewangam = GeoPoint(35.4930, 129.4393)
    private val farFromDaewangam = GeoPoint(35.5150, 129.4250)

    @Test
    fun `차분한 주행을 저장하고 탑승과 마감을 안내한다`() {
        val rig = Rig()
        rig.calmTrip()
        val r = rig.records.single()
        assertEquals(20, r.xp)
        assertEquals(0, r.harshAccel + r.harshDecel)
        assertEquals(Announcements.BOARDING, rig.lines.first())
        assertEquals(Announcements.CLOSING, rig.lines.last())
        assertFalse(rig.state.inTrip)
        assertNull(rig.state.live)
        assertNull(rig.journal.lines, "주행이 끝나면 일지를 지운다")
    }

    @Test
    fun `경고등이 잠깐 켜졌다 꺼지면 퀘스트도 보호권도 없다`() {
        val rig = Rig()
        rig.engine.onTelltales(setOf("TPMS"))
        rig.wait(0.5)
        rig.engine.onTelltales(emptySet())
        rig.wait(5.0)
        assertTrue(rig.lines.none { it.startsWith("대장간") || it == Announcements.QUEST_DONE })
        assertEquals(0, rig.settings.shields(Profile.DEFAULT.id))
        assertTrue(rig.state.quests.isEmpty())
    }

    @Test
    fun `경고등이 1분 넘게 켜졌다 꺼지면 보호권과 완료 연출을 준다`() {
        val rig = Rig()
        rig.engine.onTelltales(setOf("TPMS", "SEAT_BELT"))
        rig.wait(3.5)
        assertEquals(listOf(Announcements.questOpened(Quest.TIRE)), rig.said.last())
        assertEquals(setOf(Quest.TIRE), rig.state.quests)
        rig.wait(60.0)
        rig.engine.onTelltales(emptySet())
        assertEquals(listOf(Announcements.QUEST_DONE), rig.said.last())
        assertEquals(1, rig.settings.shields(Profile.DEFAULT.id))
        assertEquals(Celebration.QuestDone(Quest.TIRE), rig.state.celebrations.single())
        assertEquals(1, rig.state.progress.shields)
    }

    @Test
    fun `주행 중 켜진 경고는 주차한 뒤에 알린다`() {
        val rig = Rig()
        rig.calmTrip(events = listOf(30.0 to { rig.engine.onTelltales(setOf("ENGINE_OIL_PRESSURE")) }))
        val opened = Announcements.questOpened(Quest.OIL)
        val closing = rig.lines.indexOf(Announcements.CLOSING)
        assertTrue(rig.lines.indexOf(opened) in 0 until closing, "주차 후 마감 안내 앞에 나온다: ${rig.lines}")
    }

    @Test
    fun `검약 행차는 잔량과 적산거리로 전비를 내고 옛길 지점까지 센다`() {
        val rig = Rig()
        rig.engine.onBatterySoc(80)
        rig.engine.onOdometer(1000f)
        rig.calmTrip(events = listOf(90.0 to {
            rig.engine.onOdometer(1025f)
            rig.engine.onBatterySoc(77)
        }))
        val r = rig.records.single()
        assertEquals(25.0, r.odoKm!!, 1e-6)
        assertTrue(r.eco)
        assertEquals(25 / (0.03 * 77.4), r.kmPerKwh!!, 1e-6)
        assertEquals(20 + 15, r.xp)
        val cels = rig.state.celebrations
        assertTrue(cels.first() is Celebration.Eco)
        assertTrue(Celebration.StationReached(Station.PANGYO) in cels)
        assertTrue(Announcements.ECO in rig.lines)
        assertTrue(Announcements.stationReached(Station.PANGYO) in rig.lines)
    }

    @Test
    fun `적산거리가 늘지 않아도 차속으로 잰 거리로 원정이 쌓인다`() {
        val rig = Rig()
        rig.engine.onOdometer(1000f)
        rig.calmTrip()
        val r = rig.records.single()
        assertEquals(0.0, r.odoKm!!, 1e-9)
        assertTrue(r.distanceKm > 0.5)
        assertEquals(r.distanceKm, rig.state.progress.journey.totalKm, 1e-9)
    }

    @Test
    fun `목적지 근처에서 제자리 주행은 도착이 아니고 밖에서 들어오면 명소 스탬프다`() {
        val rig = Rig()
        rig.engine.onDestination(daewangam)
        rig.engine.onLocation(nearDaewangam)
        rig.calmTrip()
        assertNull(rig.records.last().stamp)

        rig.engine.onLocation(farFromDaewangam)
        rig.calmTrip(events = listOf(80.0 to { rig.engine.onLocation(nearDaewangam) }))
        val first = rig.records.last()
        assertEquals(Landmark.DAEWANGAM, first.stamp)
        assertTrue(Celebration.Stamp(Landmark.DAEWANGAM) in rig.state.celebrations)

        rig.engine.onLocation(farFromDaewangam)
        rig.calmTrip(events = listOf(80.0 to { rig.engine.onLocation(nearDaewangam) }))
        val second = rig.records.last()
        assertEquals(Landmark.DAEWANGAM, second.stamp)
        assertEquals(first.xp - 20, second.xp, "명소 보너스는 처음 도착할 때만")
    }

    @Test
    fun `내비게이션이 도착을 알리면 끝낸 곳과 상관없이 도착이다`() {
        val rig = Rig()
        rig.engine.onDestination(daewangam)
        rig.calmTrip(events = listOf(70.0 to { rig.engine.onArrived("울산 대왕암공원") }))
        assertEquals(Landmark.DAEWANGAM, rig.records.single().stamp)
    }

    @Test
    fun `안전띠를 매지 않고 출발하면 안내하고 보호권으로 개근을 잇는다`() {
        val rig = Rig()
        rig.engine.seedDemo(region = null, days = 2, monthTrips = 0)
        assertEquals(2, rig.state.progress.streak)
        rig.settings.addShield(Profile.DEFAULT.id)
        rig.engine.onSeatBelt(false)
        // 하루 5분 넘게 달려야 개근에 셈하는 날이 된다
        rig.shiftDrive()
        rig.speeds(Triple(15.0, 0.0, 40.0), Triple(330.0, 40.0, 40.0), Triple(15.0, 40.0, 0.0))
        rig.shiftPark()
        assertTrue(Announcements.BELT in rig.lines)
        assertEquals(false, rig.records.last().belt)
        assertTrue(Celebration.ShieldUsed in rig.state.celebrations)
        assertTrue(Announcements.SHIELD_USED in rig.lines)
        assertEquals(2, rig.state.progress.streak, "보호권으로 이어 가되 오늘은 세지 않는다")
        assertEquals(0, rig.state.progress.shields)
    }

    @Test
    fun `오래 달리면 쉬어 가라고 하고 휴게소 검색을 띄우며 쉬고 떠나면 보너스를 준다`() {
        val rig = Rig()
        rig.engine.overrideRest(RestRule(driveLimitMin = 1.0, restMin = 0.5))
        rig.calmTrip()
        assertTrue(Announcements.REST_DUE in rig.lines)
        assertTrue(Announcements.REST_SEARCH in rig.lines)
        assertEquals(1, rig.restSearches)
        assertTrue(rig.state.restDue)
        rig.wait(1.5)
        assertEquals(1, rig.state.restLeftMin, "주차 뒤 1초 갱신에서 남은 시간을 센다")

        rig.wait(31.0)
        rig.said.clear()
        rig.calmTrip()
        assertEquals(listOf(Announcements.BOARDING, Announcements.RESTED), rig.said.first())
        val r = rig.records.last()
        assertTrue(r.rested)
        assertEquals(20 + 20, r.xp)
        assertTrue(Celebration.Rested in rig.state.celebrations)
    }

    @Test
    fun `경로 위에 휴게소가 있으면 검색 대신 앞길 휴게소를 알린다`() {
        val rig = Rig()
        rig.engine.overrideRest(RestRule(driveLimitMin = 1.0, restMin = 0.5))
        rig.engine.onTurns(nextTurnM = 2_000.0, restAreaM = 12_000.0)
        rig.calmTrip()
        assertTrue(Announcements.REST_AREA_AHEAD in rig.lines)
        assertEquals(0, rig.restSearches)
    }

    @Test
    fun `회전 앞에서는 주행 중 안내를 미루고 주차 중 안내는 바로 낸다`() {
        val rig = Rig()
        rig.engine.onTurns(nextTurnM = 80.0, restAreaM = null)
        rig.engine.onChargePort(true)
        assertEquals(listOf(Announcements.CHARGING), rig.said.single(), "주차 중 안내는 미루지 않는다")
        rig.engine.onChargePort(false)

        rig.shiftDrive()
        rig.speeds(Triple(10.0, 0.0, 30.0))
        assertTrue(rig.state.voiceHeld)
        assertFalse(Announcements.BOARDING in rig.lines)
        rig.speeds(Triple(21.0, 30.0, 30.0))
        assertTrue(Announcements.BOARDING in rig.lines, "30초보다 오래 미루지 않는다")
        assertFalse(rig.state.voiceHeld)
    }

    @Test
    fun `어린이보호구역 안내는 회전 앞에서도 미루지 않는다`() {
        val rig = Rig()
        rig.engine.onTurns(nextTurnM = 80.0, restAreaM = null)
        rig.engine.onLocation(GeoPoint(35.5440, 129.3300))
        rig.shiftDrive()
        assertEquals(listOf(Announcements.SCHOOL_ZONE), rig.said.single())
    }

    @Test
    fun `계기판은 1분 구간을 마치면 경험치 알림 띠를 띄운다`() {
        val rig = Rig()
        var banner: String? = null
        var liveXp = -1
        rig.shiftDrive()
        rig.speeds(*Rig.CALM, events = listOf(62.0 to {
            banner = rig.state.live?.banner
            liveXp = rig.state.live?.trip?.xp ?: -1
        }))
        assertEquals(10, liveXp)
        assertEquals("+10XP · 1분 안전 구간", banner)
        assertNotNull(rig.state.live)
        rig.shiftPark()
        assertNull(rig.state.live)
    }

    @Test
    fun `시연용 기온 고정은 차량이 보내는 기온에 덮이지 않는다`() {
        val rig = Rig()
        rig.engine.onWiper(true)
        rig.engine.onOutsideTemperature(25f)
        assertEquals(Weather.RAIN, rig.state.weather)
        rig.engine.overrideOutsideTemperature(-3f)
        rig.engine.onOutsideTemperature(25f)
        assertEquals(Weather.SNOW, rig.state.weather)
        rig.engine.overrideOutsideTemperature(null)
        rig.engine.onOutsideTemperature(25f)
        assertEquals(Weather.RAIN, rig.state.weather)
    }

    @Test
    fun `같은 부팅에서 다시 뜨면 주행을 이어 가고 재부팅 뒤면 조용히 마감한다`() {
        val rig = Rig()
        rig.shiftDrive()
        rig.speeds(Triple(15.0, 0.0, 40.0), Triple(30.0, 40.0, 40.0))
        rig.restart("boot-1")
        assertTrue(rig.state.inTrip)
        rig.said.clear()
        rig.speeds(Triple(30.0, 40.0, 40.0), Triple(15.0, 40.0, 0.0))
        rig.shiftPark()
        assertFalse(Announcements.BOARDING in rig.lines, "이어 간 주행은 탑승 안내를 다시 하지 않는다")
        assertEquals(1, rig.records.size)
        assertTrue(rig.records.single().distanceKm > 0.6)

        rig.shiftDrive()
        rig.speeds(Triple(15.0, 0.0, 40.0), Triple(30.0, 40.0, 40.0))
        rig.said.clear()
        rig.restart("boot-2")
        assertFalse(rig.state.inTrip)
        assertEquals(2, rig.records.size)
        assertTrue(rig.said.isEmpty(), "재부팅 뒤 마감한 주행은 안내하지 않는다")
        assertNull(rig.journal.lines)
    }
    @Test
    fun `다섯 번째 야행에서 부엉이가 합류하고 바로 데리고 다닌다`() {
        val rig = Rig()
        rig.engine.seedTour() // 지난 야행 4번
        assertTrue(Title.FIRST_TRIP in rig.state.progress.titles)
        assertNull(rig.state.buddy)
        rig.calmTrip(events = listOf(20.0 to { rig.engine.onHeadlights(true) }))
        assertTrue(rig.records.last().night)
        assertEquals(Buddy.OWL, rig.state.buddy)
        assertEquals(Buddy.OWL, rig.settings.buddy(Profile.DEFAULT.id))
        assertTrue(Celebration.BuddyJoined(Buddy.OWL) in rig.state.celebrations)
        assertTrue(Celebration.TitleEarned(Title.NIGHT_5) in rig.state.celebrations)
        val closing = rig.lines.lastIndexOf(Announcements.CLOSING)
        assertTrue(rig.lines.indexOf(Buddy.OWL.joinLine) in 0 until closing)
        assertTrue(Announcements.titleEarned(Title.NIGHT_5) in rig.lines)
        // 아직 합류하지 않은 동반자는 고를 수 없다
        rig.engine.chooseBuddy(Buddy.TIGER)
        assertEquals(Buddy.OWL, rig.state.buddy)
    }

    @Test
    fun `가진 칭호 가운데 하나를 대표 칭호로 고른다`() {
        val rig = Rig()
        rig.engine.seedTour()
        val titles = rig.state.progress.titles
        assertEquals(titles.first(), rig.state.title) // 고르기 전에는 가장 어려운 칭호
        val easy = titles.last()
        rig.engine.chooseTitle(easy)
        assertEquals(easy, rig.state.title)
        assertEquals(easy, rig.settings.title(Profile.DEFAULT.id))
        // 아직 없는 칭호는 고를 수 없다
        rig.engine.chooseTitle(Title.STREAK_100)
        assertEquals(easy, rig.state.title)
        // 엔진을 다시 띄워도 고른 칭호가 남는다
        rig.restart("boot-1")
        assertEquals(easy, rig.state.title)
    }

    @Test
    fun `도감 지역에 들어가면 지역마다 다른 인사를 하고 장면은 바꾸지 않는다`() {
        val rig = Rig()
        rig.calmTrip(events = listOf(30.0 to { rig.engine.onLocation(GeoPoint(35.1631, 129.1636)) })) // 해운대
        assertEquals(haengcha.judge.Region.BUSAN, rig.state.region)
        assertTrue("부산에 오셨습니다. 파도 소리가 반깁니다." in rig.lines, "${rig.lines}")
        assertTrue("지역 도감에 부산 바다가 올랐습니다." in rig.lines)
    }
}
