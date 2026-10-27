package haengcha.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import haengcha.judge.Announcements
import haengcha.judge.CompletedTrip
import haengcha.judge.Destination
import haengcha.judge.ExtrasMeter
import haengcha.judge.Gear
import haengcha.judge.GeoPoint
import haengcha.judge.InspectionPlanner
import haengcha.judge.InspectionRule
import haengcha.judge.JoseonTheme
import haengcha.judge.JournalHeader
import haengcha.judge.JourneyStatus
import haengcha.judge.LiveTrip
import haengcha.judge.Landmark
import haengcha.judge.ProgressSummary
import haengcha.judge.Quest
import haengcha.judge.Region
import haengcha.judge.RegularStatus
import haengcha.judge.RestRule
import haengcha.judge.RestTracker
import haengcha.judge.SchoolZone
import haengcha.judge.SchoolZonePass
import haengcha.judge.SpeedSample
import haengcha.judge.Station
import haengcha.judge.TripJournal
import haengcha.judge.TripRecord
import haengcha.judge.TripTracker
import haengcha.judge.VoiceGate
import haengcha.judge.Weather
import haengcha.judge.WeatherRule
import haengcha.judge.arrived
import haengcha.judge.journey
import haengcha.judge.journeyKm
import haengcha.judge.questChange
import haengcha.judge.savedKwh
import haengcha.judge.stationsReached
import haengcha.judge.summarize
import haengcha.vehicle.CarVehicleSignals
import haengcha.vehicle.NaviListener
import haengcha.vehicle.PleosNavi
import haengcha.vehicle.PleosVehicleSignals
import haengcha.vehicle.SignalStatus
import haengcha.vehicle.VehicleListener
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DriveUiState(
    val progress: ProgressSummary,
    /** 최근 주행부터. */
    val recentTrips: List<TripRecord>,
    val restricted: Boolean = false,
    /** 주행 규제에 영상 금지가 들어 있다. 실시간 장면을 마지막 프레임에서 멈춘다. */
    val noVideo: Boolean = false,
    val inTrip: Boolean = false,
    val collecting: Boolean = false,
    val missingSignals: List<String> = emptyList(),
    /** 위치를 받고 있는지(위치 권한). */
    val locating: Boolean = false,
    /** 지금 차가 있는 지역 테마. 제출 범위 밖이면 null. */
    val region: Region? = null,
    /** 지금 지나는 어린이보호구역. */
    val schoolZone: SchoolZone? = null,
    /** 충전 포트가 연결돼 있는지(쉬어가는 행차). */
    val charging: Boolean = false,
    /** 정차 후 차례로 보여 줄 축하 연출. */
    val celebrations: List<Celebration> = emptyList(),
    val profiles: List<Profile> = listOf(AppPrefs.DEFAULT),
    val activeProfile: String = AppPrefs.DEFAULT.id,
    /** 보유한 콜라보 동반자(시연용 목업). */
    val owned: Set<String> = emptySet(),
    /** 야행: 전조등이 켜져 있거나 저녁 7시~아침 6시. */
    val night: Boolean = false,
    /** 와이퍼가 돌고 있으면 비, 바깥 기온이 낮으면 눈. */
    val weather: Weather? = null,
    /** 열려 있는 대장간 퀘스트. */
    val quests: Set<Quest> = emptySet(),
    /** 쉬어 갈 때가 됐다(역참). */
    val restDue: Boolean = false,
    /** 쉬는 중이면 휴식 보너스까지 남은 분. */
    val restLeftMin: Int? = null,
    /** 다음 회전이 가까워 행차 음성을 미루고 있다. */
    val voiceHeld: Boolean = false,
    /** 이 차량에서 읽지 못하는 Pleos SDK 값. */
    val missingSdk: List<String> = emptyList(),
    /** 주행 중 화면에 보여 줄 이번 주행(1초마다 갱신). 주행 중이 아니면 null. */
    val live: LiveView? = null,
)

/** 주행 중 화면의 이번 주행: 지금까지 판정, 이번 주행을 더한 옛길 원정, 잠깐 띄우는 알림 띠. */
data class LiveView(val trip: LiveTrip, val journey: JourneyStatus, val banner: String? = null)

/** 정차 후 보여 줄 축하 연출. 해금 연출은 차를 세운 뒤에만 재생한다(기획서 4.2). */
sealed interface Celebration {
    data class ThemeUnlocked(val theme: JoseonTheme) : Celebration
    data class RegionJoined(val status: RegularStatus) : Celebration
    data class GradeUp(val status: RegularStatus) : Celebration
    data object Mapae : Celebration
    data object Guardian : Celebration
    data class CompanionJoined(val trips: Int) : Celebration
    data class Eco(val kmPerKwh: Double, val rice: Double) : Celebration
    data class WeatherCalm(val weather: Weather) : Celebration
    data object Rested : Celebration
    data class StationReached(val station: Station) : Celebration
    data class Stamp(val landmark: Landmark) : Celebration
    data class QuestDone(val quest: Quest) : Celebration
    data object ShieldUsed : Celebration
}

/**
 * 차량 신호·위치 → 주행 시작·종료 판단 → 판정(암행어사 감찰, 아이 지킴이 포함) → 저장 → 음성 안내를 잇는다.
 * Application 수명이라 다른 앱이 앞에 있거나 주행 중 화면이 가려져도 프로세스가 살아 있는 동안은 수집이 이어진다.
 * 주행 중 음성은 탑승, 지역 진입(그날 처음), 어린이보호구역 진입, 안전띠·야행·역참 안내뿐이고 나머지는 차를 세운 뒤에 낸다.
 * 감찰 시작·진행은 알리지 않는다(기획서 4.6). 어린이보호구역 안에서는 다른 안내를 내지 않는다(4.7).
 * 내비게이션의 다음 회전이 가까우면 행차 음성을 미룬다(9.3, 어린이보호구역 안내는 미루지 않는다).
 * 주행 중에는 일지([journal])를 남겨, 프로세스가 죽었다 다시 뜨면 같은 부팅([bootId])에서는 이어 가고 재부팅 뒤에는 마감한다.
 *
 * Pleos Vehicle SDK·NaviHelper로 하는 판정: 검약 행차(전비), 대장간 퀘스트(경고등)와 개근 보호권, 역참에서 쉬어가기,
 * 옛길 원정(적산거리), 야행(전조등), 빗길·눈길(와이퍼·바깥 기온), 안전띠 개근, 명소 도착 스탬프.
 */
class DriveController(
    private val prefs: AppPrefs,
    private val storeFor: (profileId: String) -> TripStore,
    private val journal: TripJournalFile,
    private val bootId: String,
    private val signals: CarVehicleSignals,
    private val pleos: PleosVehicleSignals,
    private val navi: PleosNavi,
    private val location: LocationSource,
    private val say: (List<String>) -> Unit,
) : VehicleListener, NaviListener {

    private val handler = Handler(Looper.getMainLooper())
    private val planner = InspectionPlanner()
    private val tracker = TripTracker(planInspection = { planner.plan(_state.value.progress.inspectionsToday) })
    private val meter = ExtrasMeter()
    private val rest = RestTracker().apply { decode(prefs.restState) }
    private val gate = VoiceGate()
    private var store = storeFor(prefs.activeProfile)
    private var records = store.load()
    private val _state = MutableStateFlow(
        DriveUiState(
            summary(records), recent(records),
            profiles = prefs.profiles, activeProfile = prefs.activeProfile, owned = prefs.owned,
            quests = prefs.openQuests.keys, night = nightByClock(),
        ),
    )
    val state: StateFlow<DriveUiState> = _state

    // 이번 주행에서 지난 지역과 아이 지킴이 횟수, 지나는 중인 어린이보호구역
    private val tripRegions = mutableSetOf<Region>()
    private var tripGuardian = 0
    private var zonePass: SchoolZonePass? = null
    /** 지역 진입 안내는 지역마다 하루 한 번. */
    private val announced = mutableSetOf<Pair<Region, LocalDate>>()
    /** 이번 주행의 단조 시계 0 시점 벽시계 시각(ms). 기록 시각 계산에 쓴다. */
    private var epochOffsetMs = currentEpochOffsetMs()

    // 이번 주행의 역참 휴식, 야행 안내, 내비게이션이 알린 도착
    private var tripRested = false
    private var nightAnnounced = false
    private var tripArrivedName: String? = null
    /** 주행 중에 열려 주차 후에 알릴 퀘스트. */
    private val questLines = mutableListOf<String>()
    private var lastJournalExtrasMs = 0L

    // 주행 중 화면 알림 띠(구간 경험치, 무사고 행진, 옛길 지점)
    private var liveXp = -1
    private var liveRun = 0
    private var liveJourneyKm: Double? = null
    private var banner: String? = null
    private var bannerUntil = 0L

    // 차량 상태(바뀔 때만 오는 신호의 마지막 값)
    private var telltales = emptySet<String>()
    private var oilLow = false
    private var headlights = false
    private var wiper = false
    private var outsideC: Float? = null
    private var tempOverride: Float? = null
    private var destination: Destination? = null
    private var restAreaM: Double? = null
    /** 마지막 위치와 이번 주행을 시작한 위치. 도착 판정에만 쓰고 저장하지 않는다. */
    private var lastPoint: GeoPoint? = null
    private var tripStartPoint: GeoPoint? = null
    private var ticking = false

    init {
        restore()
        if (rest.due) scheduleTick()
    }

    /** 일지가 남아 있으면 같은 부팅에서는 주행을 이어 가고, 재부팅 뒤라면 마지막 표본 시각에 마감해 저장한다. */
    private fun restore() {
        val j = journal.load() ?: return
        val h = j.header
        tracker.resume(h.startNanos, h.gearKnown, h.inspection)
        meter.restore(j.extras)
        tripRegions += j.regions
        tripGuardian = j.guardian
        tripRested = j.extras["rested"] == "1"
        nightAnnounced = j.extras["night"] == "1"
        tripArrivedName = j.extras["arr"]
        epochOffsetMs = h.epochOffsetMs
        var ended: CompletedTrip? = null
        for (s in j.samples) {
            ended = tracker.onSpeed(s)
            if (ended != null) break
        }
        if (ended == null && h.bootId != bootId) ended = tracker.endAt(j.samples.lastOrNull()?.tNanos ?: h.startNanos)
        if (ended != null) {
            Log.i(TAG, "일지의 주행 마감: 표본 ${j.samples.size}개")
            save(ended, announce = false)
        } else {
            journal.reopen()
            _state.update { it.copy(inTrip = true) }
            scheduleTick()
            Log.i(TAG, "일지의 주행 이어 감: 표본 ${j.samples.size}개, 지역 ${j.regions}, 아이 지킴이 ${j.guardian}, 판정 ${j.extras}")
        }
    }

    fun start() {
        if (_state.value.collecting) return
        signals.start(this)
        pleos.start(this)
        navi.start(this)
        _state.update { it.copy(collecting = true) }
    }

    fun startLocation() {
        if (_state.value.locating) return
        if (location.start(::onLocation)) _state.update { it.copy(locating = true) }
    }

    /** 운전자 프로필을 바꾼다. 주행 중에는 바꾸지 않는다. */
    fun switchProfile(id: String): Boolean {
        if (tracker.inTrip || id == _state.value.activeProfile) return false
        prefs.activeProfile = id
        store = storeFor(id)
        records = store.load()
        announced.clear()
        _state.update {
            it.copy(progress = summary(records), recentTrips = recent(records), activeProfile = id, celebrations = emptyList())
        }
        return true
    }

    fun addProfile() {
        prefs.addProfile()
        _state.update { it.copy(profiles = prefs.profiles) }
    }

    /** 콜라보 동반자 확정 구매(시연용: 결제 없이 보유만 표시). */
    fun buy(itemId: String) {
        prefs.owned = prefs.owned + itemId
        _state.update { it.copy(owned = prefs.owned) }
    }

    fun dismissCelebration() {
        _state.update { it.copy(celebrations = it.celebrations.drop(1)) }
    }

    /**
     * 디버그 빌드 시연용: 지난 며칠의 주행 기록을 지금 프로필에 넣는다(단골 등급·동반자·옛길 원정 진행 시연).
     * 경험치는 적게 넣어 다음 주행에서 레벨업·테마 해금까지 함께 보이게 한다. [journeyKm]은 원정 거리를 채울 지난 주행 한 건.
     */
    fun seedDemo(region: Region?, days: Int, monthTrips: Int, journeyKm: Double = 0.0) {
        val today = LocalDate.now()
        val base = System.currentTimeMillis()
        val seeded = (1..days).map { d ->
            TripRecord(base - d * DAY_MS, base - d * DAY_MS + 900_000, today.minusDays(d.toLong()), 6.0, 15.0, 20, 0, 0, 2, 0,
                regions = setOfNotNull(region))
        } + (1..monthTrips).map { i ->
            // 이달 1일 ~ 어제 사이에 고르게 흩는다
            val date = today.minusDays(((i - 1) % maxOf(1, today.dayOfMonth - 1) + 1).toLong())
            TripRecord(base - i * 3_600_000L, base - i * 3_600_000L + 600_000, date, 4.0, 10.0, 5, 0, 0, 1, 0)
        } + listOfNotNull(
            journeyKm.takeIf { it > 0 }?.let {
                TripRecord(base - 2 * DAY_MS, base - 2 * DAY_MS + 3_600_000, today.minusDays(2), 4.0, 60.0, 5, 0, 0, 1, 0, odoKm = it)
            },
        )
        seeded.forEach(store::append)
        records = store.load()
        _state.update { it.copy(progress = summary(records), recentTrips = recent(records)) }
        Log.i(TAG, "시연 기록 추가: ${seeded.size}건")
    }

    /** 디버그 빌드에서 감찰 규칙을 바꿔 짧은 주행으로 시험할 때 쓴다. */
    fun overrideInspection(rule: InspectionRule) {
        planner.rule = rule
    }

    /** 디버그 빌드에서 역참 기준 시간을 줄여 시험할 때 쓴다. */
    fun overrideRest(rule: RestRule) {
        rest.rule = rule
    }

    /** 디버그 빌드 시연용: 명소로 내비게이션 경로를 잡는다. */
    fun requestRoute(name: String, point: GeoPoint) = navi.requestRoute(name, point)

    fun onLocation(p: GeoPoint) {
        lastPoint = p
        val region = Region.at(p)
        val zone = SchoolZone.at(p)
        val was = _state.value
        _state.update { it.copy(region = region, schoolZone = zone) }
        if (zone != was.schoolZone) {
            leaveZone(SystemClock.elapsedRealtimeNanos())
            if (zone != null && tracker.inTrip) enterZone()
        }
        if (region != null && region != was.region && tracker.inTrip) enterRegion(region)
    }

    override fun onSpeed(sample: SpeedSample) {
        zonePass?.onSpeed(sample)
        val ended = tracker.onSpeed(sample)
        syncInTrip()
        if (tracker.inTrip) {
            journal.append(TripJournal.sample(sample))
            if (meter.onSpeed(sample)) onDeparted()
        }
        ended?.let(::save)
    }

    override fun onGear(gear: Gear, tNanos: Long) {
        tracker.onGear(gear, tNanos)?.let(::save)
        syncInTrip()
    }

    override fun onDrivingRestricted(restricted: Boolean, noVideo: Boolean) {
        _state.update { it.copy(restricted = restricted, noVideo = restricted && noVideo) }
    }

    override fun onChargePort(connected: Boolean) {
        val was = _state.value.charging
        _state.update { it.copy(charging = connected) }
        // 쉬어가는 행차: 충전을 시작하면 한 번 안내한다
        if (connected && !was && !tracker.inTrip) speak(listOf(Announcements.CHARGING))
    }

    override fun onStatusChanged(statuses: List<SignalStatus>) {
        _state.update { it.copy(missingSignals = statuses.filterNot { s -> s.available }.map { s -> s.name }) }
    }

    // ---- Pleos Vehicle SDK ----

    override fun onSdkStatus(statuses: List<SignalStatus>) {
        _state.update { it.copy(missingSdk = statuses.filterNot { s -> s.available }.map { s -> s.name }) }
    }

    override fun onBatterySoc(percent: Int) {
        meter.soc = percent
    }

    override fun onEvEfficiency(kmPerKwh: Float) {
        meter.efficiency = kmPerKwh.toDouble()
    }

    override fun onOdometer(km: Float) {
        meter.odometerKm = km.toDouble()
    }

    override fun onSeatBelt(buckled: Boolean) {
        meter.belted = buckled
    }

    override fun onHeadlights(on: Boolean) {
        headlights = on
        meter.headlights = on
        updateScene()
        // 야행: 주행 중 전조등을 켜면 한 번 알린다
        if (on && tracker.inTrip && meter.departed && !nightAnnounced) announceNight()
    }

    override fun onWiper(on: Boolean) {
        wiper = on
        meter.wiper = on
        updateScene()
    }

    override fun onOutsideTemperature(celsius: Float) {
        // 시연용 고정값이 있으면 차량 값(에뮬레이터는 25도를 1초마다 보냄)으로 덮지 않는다
        val c = tempOverride ?: celsius
        outsideC = c
        meter.outsideC = c.toDouble()
        updateScene()
    }

    /** 디버그 빌드 시연용: 바깥 기온을 [celsius]로 고정한다(null이면 차량 값으로 돌아감). */
    fun overrideOutsideTemperature(celsius: Float?) {
        tempOverride = celsius
        celsius?.let(::onOutsideTemperature)
    }

    override fun onTelltales(active: Set<String>) {
        telltales = active
        refreshQuests()
    }

    override fun onEngineOilLow(low: Boolean) {
        oilLow = low
        refreshQuests()
    }

    // ---- NaviHelper ----

    override fun onDestination(dest: Destination?) {
        destination = dest
    }

    override fun onTurns(nextTurnM: Double?, restAreaM: Double?) {
        this.restAreaM = restAreaM
        release(gate.onNextTurn(nextTurnM, System.currentTimeMillis()))
    }

    override fun onArrived(name: String?) {
        if (!tracker.inTrip) return
        tripArrivedName = name ?: destination?.name
        tripArrivedName?.let { journal.append(TripJournal.extra("arr", it.replace(",", " ")), flush = true) }
        Log.i(TAG, "내비게이션 도착: $tripArrivedName")
    }

    // ---- 내부 ----

    private fun updateScene() {
        val weather = if (!wiper) null else if ((outsideC ?: Float.MAX_VALUE) <= WeatherRule().snowMaxC) Weather.SNOW else Weather.RAIN
        _state.update { it.copy(night = headlights || nightByClock(), weather = weather) }
    }

    private fun nightByClock(): Boolean = LocalTime.now().let { it.hour >= 19 || it.hour < 6 }

    /**
     * 경고등·엔진오일 신호로 대장간 퀘스트를 열고 닫는다. 닫히면 지금 운전자에게 개근 보호권을 준다.
     * 연 지 1분도 안 돼 꺼지면 신호가 흔들린 것으로 보고 조용히 닫는다. 열림 안내도 3초 뒤 아직 열려 있을 때만 한다.
     */
    private fun refreshQuests() {
        val now = System.currentTimeMillis()
        val open = prefs.openQuests
        val change = questChange(open, Quest.active(telltales, oilLow), now)
        if (change.opened.isEmpty() && change.completed.isEmpty() && change.dropped.isEmpty()) return
        prefs.openQuests = open - change.completed - change.dropped + change.opened.associateWith { now }
        Log.i(TAG, "대장간 퀘스트 열림 ${change.opened}, 마침 ${change.completed}, 짧게 켜졌다 꺼짐 ${change.dropped}")
        change.completed.forEach { prefs.addShield(prefs.activeProfile) }
        _state.update {
            it.copy(
                quests = prefs.openQuests.keys,
                progress = summary(records),
                celebrations = it.celebrations + change.completed.map(Celebration::QuestDone),
            )
        }
        if (change.completed.isNotEmpty()) announceQuest(listOf(Announcements.QUEST_DONE))
        if (change.opened.isNotEmpty()) {
            handler.postDelayed({
                val still = change.opened.filter { it in prefs.openQuests }
                if (still.isNotEmpty()) announceQuest(still.map(Announcements::questOpened))
            }, QUEST_CONFIRM_MS)
        }
    }

    /** 주행 중에 켜진 경고는 차량이 따로 알리므로 행차 안내는 주차 후에 한다. */
    private fun announceQuest(lines: List<String>) {
        if (tracker.inTrip) questLines += lines else speak(lines)
    }

    private fun onDeparted() {
        journal.append(TripJournal.extra("belt", meter.snapshot()["belt"].orEmpty()), flush = true)
        if (meter.belt == false) speak(listOf(Announcements.BELT))
        if (headlights && !nightAnnounced) announceNight()
    }

    private fun announceNight() {
        nightAnnounced = true
        journal.append(TripJournal.extra("night", "1"), flush = true)
        speak(listOf(Announcements.NIGHT))
    }

    /** 행차 음성. 주행 중 다음 회전이 가까우면 미룬다. [urgent]와 주차 중 안내는 미루지 않는다. */
    private fun speak(lines: List<String>, urgent: Boolean = false) {
        release(gate.offer(lines, System.currentTimeMillis(), urgent || !tracker.inTrip))
    }

    private fun release(chunks: List<List<String>>) {
        chunks.forEach(say)
        if (_state.value.voiceHeld != gate.holding) _state.update { it.copy(voiceHeld = gate.holding) }
        if (gate.holding) scheduleTick()
    }

    /** 주행 중에는 1초마다(주행 중 화면), 휴식 대기·미룬 음성이 있을 때는 5초마다 깬다. */
    private fun scheduleTick() {
        if (ticking) return
        ticking = true
        handler.postDelayed(::tick, if (tracker.inTrip) LIVE_TICK_MS else TICK_MS)
    }

    /** 주행 중 화면 값을 갱신하고, 새로 생긴 일이 있으면 알림 띠를 잠깐 띄운다. */
    private fun updateLive(now: Long) {
        val trip = tracker.live() ?: return
        val base = _state.value.progress.journey.totalKm
        val j = journey(base + maxOf(meter.odoKm ?: 0.0, trip.distanceKm))
        if (liveXp >= 0) {
            val station = stationsReached(liveJourneyKm ?: base, j.totalKm).lastOrNull()
            val text = when {
                station != null -> "📜 옛길 원정 · ${station.label} ${station.kind}에 이르렀습니다"
                trip.safeRun > liveRun && trip.safeRun % RUN_MILESTONE == 0 -> "무사고 ${trip.safeRun}분 행진!"
                trip.xp > liveXp -> "+${trip.xp - liveXp}XP · 1분 안전 구간"
                else -> null
            }
            if (text != null) {
                banner = text
                bannerUntil = now + BANNER_MS
            }
        }
        liveXp = trip.xp
        liveRun = trip.safeRun
        liveJourneyKm = j.totalKm
        _state.update { it.copy(live = LiveView(trip, j, banner.takeIf { now < bannerUntil })) }
    }

    private fun tick() {
        ticking = false
        val now = System.currentTimeMillis()
        release(gate.tick(now))
        if (tracker.inTrip) {
            updateLive(now)
            if (rest.onDriving(now)) restDue()
            if (now - lastJournalExtrasMs >= JOURNAL_EXTRAS_MS) {
                lastJournalExtrasMs = now
                meter.snapshot()["wet"]?.let { journal.append(TripJournal.extra("wet", it), flush = true) }
            }
        }
        val left = rest.restLeftMs(now, tracker.inTrip)?.let { ((it + 59_999) / 60_000).toInt() }
        if (_state.value.restLeftMin != left || _state.value.restDue != rest.due) {
            _state.update { it.copy(restDue = rest.due, restLeftMin = left) }
        }
        // 쉬는 중이면 보너스 시간이 찰 때까지만 센다
        if (tracker.inTrip || gate.holding || (left ?: 0) > 0) scheduleTick()
    }

    private fun restDue() {
        prefs.restState = rest.encode()
        _state.update { it.copy(restDue = true) }
        val ahead = restAreaM?.takeIf { it <= REST_AREA_AHEAD_M }
        speak(listOf(Announcements.REST_DUE, if (ahead != null) Announcements.REST_AREA_AHEAD else Announcements.REST_SEARCH))
        // 경로 위에 휴게소가 없으면 내비게이션에 휴게소 검색 화면을 띄워 경유지로 고르게 한다
        if (ahead == null) navi.searchRestArea()
        Log.i(TAG, "역참: 쉬어 갈 때. 경로 위 휴게소 ${restAreaM}m")
    }

    private fun syncInTrip() {
        if (_state.value.inTrip == tracker.inTrip) return
        _state.update { it.copy(inTrip = tracker.inTrip) }
        if (!tracker.inTrip) return
        epochOffsetMs = currentEpochOffsetMs()
        journal.begin(JournalHeader(tracker.tripStartNanos, tracker.readsGear, bootId, epochOffsetMs, tracker.plan))
        meter.begin()
        meter.snapshot().forEach { (k, v) -> journal.append(TripJournal.extra(k, v)) }
        nightAnnounced = false
        tripArrivedName = null
        tripStartPoint = lastPoint
        liveXp = -1
        liveRun = 0
        liveJourneyKm = null
        banner = null
        tripRested = rest.onTripStart(System.currentTimeMillis())
        prefs.restState = rest.encode()
        if (tripRested) journal.append(TripJournal.extra("rested", "1"), flush = true)
        _state.update { it.copy(restDue = rest.due, restLeftMin = null) }
        scheduleTick()
        val s = _state.value
        s.region?.let { tripRegions += it; journal.append(TripJournal.region(it), flush = true) }
        // 보호구역 안에서 출발하면 탑승 안내 대신 보호구역 안내만 한다
        if (s.schoolZone != null) {
            enterZone()
        } else {
            speak(listOfNotNull(Announcements.BOARDING, Announcements.RESTED.takeIf { tripRested }))
        }
    }

    private fun enterRegion(region: Region) {
        // 이번 주행에서 이미 지난 지역이면(일지에서 이어 간 경우 포함) 다시 안내하지 않는다
        if (!tripRegions.add(region)) return
        journal.append(TripJournal.region(region), flush = true)
        val today = LocalDate.now()
        if (!announced.add(region to today) || records.any { it.date == today && region in it.regions }) return
        if (_state.value.schoolZone == null) speak(listOf(Announcements.regionEntered(region)))
    }

    private fun enterZone() {
        zonePass = SchoolZonePass(SystemClock.elapsedRealtimeNanos())
        speak(listOf(Announcements.SCHOOL_ZONE), urgent = true)
    }

    private fun leaveZone(tNanos: Long) {
        val pass = zonePass ?: return
        zonePass = null
        val kept = pass.finish(tNanos)
        if (kept) {
            tripGuardian++
            journal.append(TripJournal.GUARDIAN, flush = true)
        }
        Log.i(TAG, "어린이보호구역 통과: 최고 %.1fkm/h, 아이 지킴이 %s".format(pass.maxKph, kept))
    }

    private fun save(trip: CompletedTrip) = save(trip, announce = true)

    private fun save(trip: CompletedTrip, announce: Boolean) {
        leaveZone(trip.endNanos)
        val regions = tripRegions.toSet()
        val guardian = tripGuardian
        // 도착: 내비게이션이 알렸거나, 목적지 반경 밖에서 출발해 반경 안에서 끝냄
        val arrivedName = tripArrivedName ?: destination?.takeIf { arrived(it, tripStartPoint, lastPoint) }?.name
        val stamp = Landmark.match(arrivedName)
        val extras = meter.finish(trip.result.distanceKm).copy(rested = tripRested, stamp = stamp)
        val pendingQuests = questLines.toList()
        tripRegions.clear()
        tripGuardian = 0
        tripRested = false
        tripArrivedName = null
        tripStartPoint = null
        questLines.clear()
        journal.clear()
        _state.update { it.copy(live = null) }
        rest.onTripEnd(System.currentTimeMillis())
        prefs.restState = rest.encode()
        if (pendingQuests.isNotEmpty() && announce) speak(pendingQuests)
        // D단에 잠깐 넣었다 뺀 경우처럼 거의 움직이지 않은 주행은 기록하지도 안내하지도 않는다
        if (trip.result.movingMinutes < MIN_TRIP_MOVING_MIN) {
            Log.i(TAG, "주행으로 보지 않음: 움직인 시간 ${trip.result.movingMinutes}분")
            return
        }
        // 차량 신호 시각(elapsedRealtimeNanos 기준)을 벽시계 시각으로 바꾼다
        fun epochMs(ns: Long) = epochOffsetMs + ns / 1_000_000

        val firstStamp = stamp != null && records.none { it.stamp == stamp }
        val record = TripRecord.of(
            trip, epochMs(trip.startNanos), epochMs(trip.endNanos), ZoneId.systemDefault(), regions, guardian, extras, firstStamp,
        )
        store.append(record)
        records = records + record
        val before = _state.value.progress
        val after = summary(records)
        val celebrations = if (!announce) emptyList() else buildList {
            if (trip.result.mapae) add(Celebration.Mapae)
            if (guardian > 0) add(Celebration.Guardian)
            record.kmPerKwh?.takeIf { record.eco }?.let { add(Celebration.Eco(it, savedKwh(record.journeyKm, it))) }
            record.weather?.takeIf { record.weatherCalm }?.let { add(Celebration.WeatherCalm(it)) }
            if (record.rested) add(Celebration.Rested)
            record.stamp?.let { add(Celebration.Stamp(it)) }
            stationsReached(before.journey.totalKm, after.journey.totalKm).forEach { add(Celebration.StationReached(it)) }
            if (after.shieldsUsed > before.shieldsUsed) add(Celebration.ShieldUsed)
            (after.themes - before.themes.toSet()).forEach { add(Celebration.ThemeUnlocked(it)) }
            after.regulars.forEach { now ->
                val was = before.regulars.firstOrNull { it.region == now.region }
                if (was == null) add(Celebration.RegionJoined(now)) else if (now.grade > was.grade) add(Celebration.GradeUp(now))
            }
            if (after.companion.joined && !before.companion.joined) add(Celebration.CompanionJoined(after.companion.tripsThisMonth))
        }
        _state.update { it.copy(progress = after, recentTrips = recent(records), celebrations = it.celebrations + celebrations, live = null) }
        Log.i(TAG, "주행 저장 ${record.toCsv()} 감찰=${trip.result.inspection}")
        // 재부팅 뒤 마감한 지난 주행은 조용히 저장만 한다
        if (announce) speak(Announcements.tripEndLines(trip.result, before, after, guardian, record))
    }

    private fun summary(all: List<TripRecord>) = summarize(all, LocalDate.now(), shieldsEarned = prefs.shields(prefs.activeProfile))

    private fun currentEpochOffsetMs() = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1_000_000

    private fun recent(all: List<TripRecord>) = all.takeLast(5).reversed()

    private companion object {
        const val TAG = "DriveController"
        const val MIN_TRIP_MOVING_MIN = 0.5
        const val DAY_MS = 86_400_000L
        const val TICK_MS = 5_000L
        const val LIVE_TICK_MS = 1_000L
        const val BANNER_MS = 6_000L
        /** 무사고 행진 알림을 띄우는 안전 구간 연속 수(5분마다). */
        const val RUN_MILESTONE = 5
        const val JOURNAL_EXTRAS_MS = 15_000L
        const val QUEST_CONFIRM_MS = 3_000L
        /** 경로 위 휴게소가 이보다 가까우면 검색 화면 대신 '앞길에 휴게소' 안내만 한다. */
        const val REST_AREA_AHEAD_M = 30_000.0
    }
}
