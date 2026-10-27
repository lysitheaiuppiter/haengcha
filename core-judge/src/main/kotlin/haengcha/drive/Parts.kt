package haengcha.drive

import haengcha.judge.Announcements
import haengcha.judge.Buddy
import haengcha.judge.CompletedTrip
import haengcha.judge.Destination
import haengcha.judge.GeoPoint
import haengcha.judge.Landmark
import haengcha.judge.LiveTrip
import haengcha.judge.ProgressSummary
import haengcha.judge.Quest
import haengcha.judge.QuestChange
import haengcha.judge.RestRule
import haengcha.judge.RestTracker
import haengcha.judge.TripRecord
import haengcha.judge.Weather
import haengcha.judge.WeatherRule
import haengcha.judge.arrived
import haengcha.judge.journey
import haengcha.judge.journeyKm
import haengcha.judge.questChange
import haengcha.judge.savedKwh
import haengcha.judge.stationsReached
import java.time.LocalTime

/*
 * 주행 엔진의 기능별 부품. 각 부품은 자기 상태만 들고, 음성·축하 연출·저장 같은 바깥 일은 엔진이 한다.
 */

/**
 * 대장간 퀘스트(기획서 4.4). 경고등·엔진오일 신호로 퀘스트를 열고 닫는다. 닫히면 지금 운전자에게 개근 보호권을 준다.
 * 연 지 1분도 안 돼 꺼지면 신호가 흔들린 것으로 보고 조용히 닫는다([haengcha.judge.QuestRule]).
 */
internal class QuestDesk(private val settings: DriveSettings) {
    private var telltales = emptySet<String>()
    private var oilLow = false

    val open: Set<Quest> get() = settings.openQuests.keys

    fun onTelltales(active: Set<String>, nowMs: Long): QuestChange? {
        telltales = active
        return refresh(nowMs)
    }

    fun onOilLow(low: Boolean, nowMs: Long): QuestChange? {
        oilLow = low
        return refresh(nowMs)
    }

    /** [quests] 가운데 아직 열려 있는 것(열림 안내 확인용). */
    fun stillOpen(quests: Set<Quest>): List<Quest> = quests.filter { it in settings.openQuests }

    private fun refresh(nowMs: Long): QuestChange? {
        val now = settings.openQuests
        val change = questChange(now, Quest.active(telltales, oilLow), nowMs)
        if (change.opened.isEmpty() && change.completed.isEmpty() && change.dropped.isEmpty()) return null
        settings.openQuests = now - change.completed - change.dropped + change.opened.associateWith { nowMs }
        change.completed.forEach { _ -> settings.addShield(settings.activeProfile) }
        return change
    }
}

/** 역참에서 쉬어가기. [RestTracker] 상태를 앱 설정에 남겨 재부팅을 넘어 이어 간다. */
internal class RestStop(private val settings: DriveSettings) {
    private val tracker = RestTracker().apply { decode(settings.restState) }

    var rule: RestRule
        get() = tracker.rule
        set(value) {
            tracker.rule = value
        }

    val due get() = tracker.due

    /** 주행 시작. 쉬어 가라고 알린 뒤 충분히 쉬었으면 true(휴식 보너스). */
    fun onTripStart(nowMs: Long): Boolean = tracker.onTripStart(nowMs).also { save() }

    fun onTripEnd(nowMs: Long) {
        tracker.onTripEnd(nowMs)
        save()
    }

    /** 쉬어 갈 때가 된 순간 한 번 true. */
    fun onDriving(nowMs: Long): Boolean = tracker.onDriving(nowMs).also { if (it) save() }

    /** 쉬는 중이면 보너스까지 남은 분(올림). */
    fun leftMin(nowMs: Long, inTrip: Boolean): Int? =
        tracker.restLeftMs(nowMs, inTrip)?.let { ((it + 59_999) / 60_000).toInt() }

    private fun save() {
        settings.restState = tracker.encode()
    }
}

/**
 * 명소 도착(기획서 4.9). 내비게이션이 도착을 알렸거나, 목적지 반경 밖에서 출발해 반경 안에서 주행을 끝내면 도착이다.
 * 좌표는 판정에만 쓰고 저장하지 않는다.
 */
internal class ArrivalWatch {
    var destination: Destination? = null
    private var last: GeoPoint? = null
    private var start: GeoPoint? = null
    /** 이번 주행에서 내비게이션이 알린 도착(일지에 남겨 이어 간다). */
    var announced: String? = null
        private set

    fun onLocation(p: GeoPoint) {
        last = p
    }

    fun beginTrip() {
        start = last
        announced = null
    }

    /** 일지에서 이어 간 주행. 출발 위치는 일지에 없어 모른다. */
    fun resume(announcedName: String?) {
        start = null
        announced = announcedName
    }

    fun onArrived(name: String?): String? {
        announced = name ?: destination?.name
        return announced
    }

    /** 주행을 끝낼 때. 도착한 명소(명소가 아니거나 도착하지 않았으면 null). */
    fun finish(): Landmark? {
        val name = announced ?: destination?.takeIf { arrived(it, start, last) }?.name
        start = null
        announced = null
        return Landmark.match(name)
    }
}

/** 주행 중 화면 계기판과 알림 띠(구간 경험치, 무사고 행진, 옛길 지점). */
internal class LiveBoard {
    private var xp = -1
    private var run = 0
    private var journeyKm: Double? = null
    private var banner: String? = null
    private var bannerUntil = 0L
    private var bannerRank = 0

    fun begin() {
        xp = -1
        run = 0
        journeyKm = null
        banner = null
    }

    /** [baseKm]는 지난 주행까지 쌓은 원정 거리, [odoKm]는 이번 주행의 적산거리 차이. */
    fun update(trip: LiveTrip, baseKm: Double, odoKm: Double?, nowMs: Long): LiveView {
        val j = journey(baseKm + maxOf(odoKm ?: 0.0, trip.distanceKm))
        if (xp >= 0) {
            val station = stationsReached(journeyKm ?: baseKm, j.totalKm).lastOrNull()
            // 뒤 알림이 앞 알림보다 낮으면 앞 알림이 다 보일 때까지 기다리지 않고 버린다(옛길 > 무사고 > 구간 경험치)
            val (text, rank) = when {
                station != null && station.next == null -> "🏆 ${station.road.label} 완주 · ${station.label}에 닿았습니다" to 3
                station != null -> "📜 옛길 원정 · ${station.label} ${station.kind}에 이르렀습니다" to 3
                trip.safeRun > run && trip.safeRun % RUN_MILESTONE == 0 -> "무사고 ${trip.safeRun}분 행진!" to 2
                trip.xp > xp -> "+${trip.xp - xp}XP · 1분 안전 구간" to 1
                else -> null to 0
            }
            if (text != null && (nowMs >= bannerUntil || rank >= bannerRank)) {
                banner = text
                bannerRank = rank
                bannerUntil = nowMs + BANNER_MS
            }
        }
        xp = trip.xp
        run = trip.safeRun
        journeyKm = j.totalKm
        return LiveView(trip, j, banner.takeIf { nowMs < bannerUntil })
    }

    companion object {
        const val BANNER_MS = 6_000L
        /** 무사고 행진 알림을 띄우는 안전 구간 연속 수(5분마다). */
        const val RUN_MILESTONE = 5
    }
}

/** 장면 신호(야행·날씨): 전조등, 와이퍼, 바깥 기온. 시연용 고정 기온이 있으면 차량 값보다 앞선다. */
internal class SceneSignals {
    var headlights = false
    var wiper = false
    var outsideC: Float? = null
        private set
    var override: Float? = null

    /** 차량이 보낸 기온을 받아 실제로 쓸 기온을 낸다. */
    fun temperature(vehicleC: Float): Float = (override ?: vehicleC).also { outsideC = it }

    fun weather(rule: WeatherRule = WeatherRule()): Weather? =
        if (!wiper) null else if ((outsideC ?: Float.MAX_VALUE) <= rule.snowMaxC) Weather.SNOW else Weather.RAIN

    fun night(time: LocalTime): Boolean = headlights || time.hour >= 19 || time.hour < 6
}

/** 주행을 저장한 뒤 차례로 보여 줄 축하 연출. */
internal fun celebrationsFor(
    trip: CompletedTrip,
    record: TripRecord,
    before: ProgressSummary,
    after: ProgressSummary,
    guardian: Int,
): List<Celebration> = buildList {
    if (trip.result.mapae) add(Celebration.Mapae)
    if (guardian > 0) add(Celebration.Guardian)
    record.kmPerKwh?.takeIf { record.eco }?.let { add(Celebration.Eco(it, savedKwh(record.journeyKm, it))) }
    record.weather?.takeIf { record.weatherCalm }?.let { add(Celebration.WeatherCalm(it)) }
    if (record.rested) add(Celebration.Rested)
    record.stamp?.let { add(Celebration.Stamp(it)) }
    stationsReached(before.journey.totalKm, after.journey.totalKm).forEach { add(Celebration.StationReached(it)) }
    if (after.shieldsUsed > before.shieldsUsed) add(Celebration.ShieldUsed)
    val themes = after.themes - before.themes.toSet()
    if (after.level > before.level && themes.isEmpty()) add(Celebration.LevelUp(after.level))
    themes.forEach { add(Celebration.ThemeUnlocked(it)) }
    after.regulars.forEach { now ->
        val was = before.regulars.firstOrNull { it.region == now.region }
        if (was == null) add(Celebration.RegionJoined(now)) else if (now.grade > was.grade) add(Celebration.GradeUp(now))
    }
    addAll(newBuddiesAndTitles(before, after))
}

/** 새로 합류한 동반자와 새로 얻은 칭호(한 번에 [MAX_TITLE_CELEBRATIONS]개까지, 나머지는 도감에서 본다). */
internal fun newBuddiesAndTitles(before: ProgressSummary, after: ProgressSummary): List<Celebration> =
    Buddy.entries.filter { it in after.buddies && it !in before.buddies }.map(Celebration::BuddyJoined) +
        (after.titles - before.titles.toSet()).take(MAX_TITLE_CELEBRATIONS).map(Celebration::TitleEarned)

private const val MAX_TITLE_CELEBRATIONS = 3

/** 축하 연출 한 장에서 말할 문장. 문장이 없는 장(역참 휴식)은 보여 주기만 한다. */
internal fun Celebration.lines(): List<String> = when (this) {
    is Celebration.LevelUp -> listOf(Announcements.levelUp(level))
    is Celebration.ThemeUnlocked -> listOf(Announcements.levelUp(theme.unlockLevel), Announcements.themeUnlocked(theme))
    is Celebration.RegionJoined -> listOf(Announcements.regionCollected(status.region))
    is Celebration.GradeUp -> listOf(Announcements.gradeUp(status.region, status.grade))
    Celebration.Mapae -> listOf(Announcements.MAPAE)
    Celebration.Guardian -> listOf(Announcements.GUARDIAN)
    is Celebration.BuddyJoined -> listOf(Announcements.buddyJoined(buddy))
    is Celebration.TitleEarned -> listOf(Announcements.titleEarned(title))
    is Celebration.Eco -> listOf(Announcements.ECO)
    is Celebration.WeatherCalm -> listOf(if (weather == Weather.SNOW) Announcements.SNOW_CALM else Announcements.RAIN_CALM)
    Celebration.Rested -> emptyList()
    is Celebration.StationReached ->
        listOf(Announcements.stationReached(station)) + listOfNotNull(Announcements.roadDone(station.road).takeIf { station.next == null })
    is Celebration.Stamp -> listOf(Announcements.stamped(landmark))
    is Celebration.QuestDone -> listOf(Announcements.QUEST_DONE)
    Celebration.ShieldUsed -> listOf(Announcements.SHIELD_USED)
}
