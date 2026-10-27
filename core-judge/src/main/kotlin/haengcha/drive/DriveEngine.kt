package haengcha.drive

import haengcha.judge.Announcements
import haengcha.judge.CompletedTrip
import haengcha.judge.Destination
import haengcha.judge.ExtrasMeter
import haengcha.judge.Gear
import haengcha.judge.GeoPoint
import haengcha.judge.InspectionPlanner
import haengcha.judge.InspectionRule
import haengcha.judge.JournalHeader
import haengcha.judge.QuestChange
import haengcha.judge.Region
import haengcha.judge.RestRule
import haengcha.judge.SchoolZone
import haengcha.judge.SchoolZonePass
import haengcha.judge.SpeedSample
import haengcha.judge.TripJournal
import haengcha.judge.TripRecord
import haengcha.judge.TripTracker
import haengcha.judge.VoiceGate
import haengcha.judge.summarize
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 차량 신호·위치 → 주행 시작·종료 판단 → 판정(암행어사 감찰, 아이 지킴이 포함) → 저장 → 음성 안내·축하 연출을 잇는다.
 * Android에 의존하지 않아 가짜 시계와 차량 신호로 주행을 재생해 시험할 수 있다(앱은 DriveController가 연결한다).
 *
 * - 주행 중 음성은 탑승, 지역 진입(그날 처음), 어린이보호구역 진입, 안전띠·야행·역참 안내뿐이고 나머지는 차를 세운 뒤에 낸다.
 *   감찰 시작·진행은 알리지 않는다(기획서 4.6). 어린이보호구역 안에서는 다른 안내를 내지 않는다(4.7).
 * - 주행 중 내비게이션의 다음 회전이 가까우면 행차 음성을 미룬다(9.3, 어린이보호구역 안내는 미루지 않는다).
 * - 주행 중에는 일지([journal])를 남겨, 프로세스가 죽었다 다시 뜨면 같은 부팅([bootId])에서는 이어 가고 재부팅 뒤에는 마감한다.
 * - Pleos Vehicle SDK·NaviHelper 판정은 부품에 맡긴다: 대장간 퀘스트([QuestDesk]), 역참([RestStop]), 명소 도착([ArrivalWatch]),
 *   주행 중 계기판([LiveBoard]), 야행·날씨([SceneSignals]). 검약·빗길·안전띠·야행 기록은 [ExtrasMeter]가 모은다.
 */
class DriveEngine(
    private val settings: DriveSettings,
    private val storeFor: (profileId: String) -> RecordStore,
    private val journal: JournalStore,
    private val bootId: String,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val say: (List<String>) -> Unit,
    /** 쉬어 갈 때 내비게이션에 휴게소 검색 화면을 띄운다. */
    private val searchRestArea: () -> Unit = {},
    private val log: (String) -> Unit = {},
) : VehicleListener, NaviListener {

    private val planner = InspectionPlanner()
    private val tracker = TripTracker(planInspection = { planner.plan(_state.value.progress.inspectionsToday) })
    private val meter = ExtrasMeter()
    private val quests = QuestDesk(settings)
    private val rest = RestStop(settings)
    private val arrival = ArrivalWatch()
    private val board = LiveBoard()
    private val scene = SceneSignals()
    private val gate = VoiceGate()
    private var store = storeFor(settings.activeProfile)
    private var records = store.load()
    private val _state = MutableStateFlow(
        DriveUiState(
            summary(records), recent(records),
            profiles = settings.profiles, activeProfile = settings.activeProfile, owned = settings.owned,
            quests = quests.open, night = scene.night(clock.timeOfDay()),
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

    // 이번 주행의 역참 휴식, 야행 안내, 주차 후에 알릴 퀘스트
    private var tripRested = false
    private var nightAnnounced = false
    private val questLines = mutableListOf<String>()
    private var lastJournalExtrasMs = 0L
    private var restAreaM: Double? = null
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
        arrival.resume(j.extras["arr"])
        epochOffsetMs = h.epochOffsetMs
        var ended: CompletedTrip? = null
        for (s in j.samples) {
            ended = tracker.onSpeed(s)
            if (ended != null) break
        }
        if (ended == null && h.bootId != bootId) ended = tracker.endAt(j.samples.lastOrNull()?.tNanos ?: h.startNanos)
        if (ended != null) {
            log("일지의 주행 마감: 표본 ${j.samples.size}개")
            save(ended, announce = false)
        } else {
            journal.reopen()
            _state.update { it.copy(inTrip = true) }
            scheduleTick()
            log("일지의 주행 이어 감: 표본 ${j.samples.size}개, 지역 ${j.regions}, 아이 지킴이 ${j.guardian}, 판정 ${j.extras}")
        }
    }

    /** 차량 신호를 받기 시작했는지(앱이 구독을 시작하면 알린다). */
    fun setCollecting(collecting: Boolean) = _state.update { it.copy(collecting = collecting) }

    fun setLocating(locating: Boolean) = _state.update { it.copy(locating = locating) }

    /** 운전자 프로필을 바꾼다. 주행 중에는 바꾸지 않는다. */
    fun switchProfile(id: String): Boolean {
        if (tracker.inTrip || id == _state.value.activeProfile) return false
        settings.activeProfile = id
        store = storeFor(id)
        records = store.load()
        announced.clear()
        _state.update {
            it.copy(progress = summary(records), recentTrips = recent(records), activeProfile = id, celebrations = emptyList())
        }
        return true
    }

    fun addProfile() {
        settings.addProfile()
        _state.update { it.copy(profiles = settings.profiles) }
    }

    /** 콜라보 동반자 확정 구매(시연용: 결제 없이 보유만 표시). */
    fun buy(itemId: String) {
        settings.owned = settings.owned + itemId
        _state.update { it.copy(owned = settings.owned) }
    }

    fun dismissCelebration() {
        _state.update { it.copy(celebrations = it.celebrations.drop(1)) }
    }

    /**
     * 디버그 빌드 시연용: 지난 며칠의 주행 기록을 지금 프로필에 넣는다(단골 등급·동반자·옛길 원정 진행 시연).
     * 경험치는 적게 넣어 다음 주행에서 레벨업·테마 해금까지 함께 보이게 한다. [journeyKm]은 원정 거리를 채울 지난 주행 한 건.
     */
    fun seedDemo(region: Region?, days: Int, monthTrips: Int, journeyKm: Double = 0.0) {
        val today = clock.today()
        val base = clock.nowMs()
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
        log("시연 기록 추가: ${seeded.size}건")
    }

    /** 디버그 빌드에서 감찰 규칙을 바꿔 짧은 주행으로 시험할 때 쓴다. */
    fun overrideInspection(rule: InspectionRule) {
        planner.rule = rule
    }

    /** 디버그 빌드에서 역참 기준 시간을 줄여 시험할 때 쓴다. */
    fun overrideRest(rule: RestRule) {
        rest.rule = rule
    }

    /** 디버그 빌드 시연용: 바깥 기온을 [celsius]로 고정한다(null이면 차량 값으로 돌아감). */
    fun overrideOutsideTemperature(celsius: Float?) {
        scene.override = celsius
        celsius?.let(::onOutsideTemperature)
    }

    fun onLocation(p: GeoPoint) {
        arrival.onLocation(p)
        val region = Region.at(p)
        val zone = SchoolZone.at(p)
        val was = _state.value
        _state.update { it.copy(region = region, schoolZone = zone) }
        if (zone != was.schoolZone) {
            leaveZone(clock.elapsedNanos())
            if (zone != null && tracker.inTrip) enterZone()
        }
        if (region != null && region != was.region && tracker.inTrip) enterRegion(region)
    }

    // ---- AAOS Car API ----

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

    override fun onOutsideTemperature(celsius: Float) {
        // 시연용 고정값이 있으면 차량 값(에뮬레이터는 25도를 1초마다 보냄)으로 덮지 않는다
        meter.outsideC = scene.temperature(celsius).toDouble()
        updateScene()
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
        scene.headlights = on
        meter.headlights = on
        updateScene()
        // 야행: 주행 중 전조등을 켜면 한 번 알린다
        if (on && tracker.inTrip && meter.departed && !nightAnnounced) announceNight()
    }

    override fun onWiper(on: Boolean) {
        scene.wiper = on
        meter.wiper = on
        updateScene()
    }

    override fun onTelltales(active: Set<String>) {
        quests.onTelltales(active, clock.nowMs())?.let(::onQuestChange)
    }

    override fun onEngineOilLow(low: Boolean) {
        quests.onOilLow(low, clock.nowMs())?.let(::onQuestChange)
    }

    // ---- NaviHelper ----

    override fun onDestination(dest: Destination?) {
        arrival.destination = dest
    }

    override fun onTurns(nextTurnM: Double?, restAreaM: Double?) {
        this.restAreaM = restAreaM
        release(gate.onNextTurn(nextTurnM, clock.nowMs()))
    }

    override fun onArrived(name: String?) {
        if (!tracker.inTrip) return
        val arrived = arrival.onArrived(name)
        arrived?.let { journal.append(TripJournal.extra("arr", it.replace(",", " ")), flush = true) }
        log("내비게이션 도착: $arrived")
    }

    // ---- 내부 ----

    private fun updateScene() {
        _state.update { it.copy(night = scene.night(clock.timeOfDay()), weather = scene.weather()) }
    }

    /** 퀘스트가 닫히면 보호권 연출과 안내, 열리면 3초 뒤에도 열려 있을 때만 안내한다. */
    private fun onQuestChange(change: QuestChange) {
        log("대장간 퀘스트 열림 ${change.opened}, 마침 ${change.completed}, 짧게 켜졌다 꺼짐 ${change.dropped}")
        _state.update {
            it.copy(
                quests = quests.open,
                progress = summary(records),
                celebrations = it.celebrations + change.completed.map(Celebration::QuestDone),
            )
        }
        if (change.completed.isNotEmpty()) announceQuest(listOf(Announcements.QUEST_DONE))
        if (change.opened.isNotEmpty()) {
            scheduler.post(QUEST_CONFIRM_MS) {
                val still = quests.stillOpen(change.opened)
                if (still.isNotEmpty()) announceQuest(still.map(Announcements::questOpened))
            }
        }
    }

    /** 주행 중에 켜진 경고는 차량이 따로 알리므로 행차 안내는 주차 후에 한다. */
    private fun announceQuest(lines: List<String>) {
        if (tracker.inTrip) questLines += lines else speak(lines)
    }

    private fun onDeparted() {
        journal.append(TripJournal.extra("belt", meter.snapshot()["belt"].orEmpty()), flush = true)
        if (meter.belt == false) speak(listOf(Announcements.BELT))
        if (scene.headlights && !nightAnnounced) announceNight()
    }

    private fun announceNight() {
        nightAnnounced = true
        journal.append(TripJournal.extra("night", "1"), flush = true)
        speak(listOf(Announcements.NIGHT))
    }

    /** 행차 음성. 주행 중 다음 회전이 가까우면 미룬다. [urgent]와 주차 중 안내는 미루지 않는다. */
    private fun speak(lines: List<String>, urgent: Boolean = false) {
        release(gate.offer(lines, clock.nowMs(), urgent || !tracker.inTrip))
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
        scheduler.post(if (tracker.inTrip) LIVE_TICK_MS else TICK_MS, ::tick)
    }

    private fun tick() {
        ticking = false
        val now = clock.nowMs()
        release(gate.tick(now))
        if (tracker.inTrip) {
            tracker.live()?.let { trip ->
                val view = board.update(trip, _state.value.progress.journey.totalKm, meter.odoKm, now)
                _state.update { it.copy(live = view) }
            }
            if (rest.onDriving(now)) restDue()
            if (now - lastJournalExtrasMs >= JOURNAL_EXTRAS_MS) {
                lastJournalExtrasMs = now
                meter.snapshot()["wet"]?.let { journal.append(TripJournal.extra("wet", it), flush = true) }
            }
        }
        val left = rest.leftMin(now, tracker.inTrip)
        if (_state.value.restLeftMin != left || _state.value.restDue != rest.due) {
            _state.update { it.copy(restDue = rest.due, restLeftMin = left) }
        }
        // 쉬는 중이면 보너스 시간이 찰 때까지만 센다
        if (tracker.inTrip || gate.holding || (left ?: 0) > 0) scheduleTick()
    }

    private fun restDue() {
        _state.update { it.copy(restDue = true) }
        val ahead = restAreaM?.takeIf { it <= REST_AREA_AHEAD_M }
        speak(listOf(Announcements.REST_DUE, if (ahead != null) Announcements.REST_AREA_AHEAD else Announcements.REST_SEARCH))
        // 경로 위에 휴게소가 없으면 내비게이션에 휴게소 검색 화면을 띄워 경유지로 고르게 한다
        if (ahead == null) searchRestArea()
        log("역참: 쉬어 갈 때. 경로 위 휴게소 ${restAreaM}m")
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
        arrival.beginTrip()
        board.begin()
        tripRested = rest.onTripStart(clock.nowMs())
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
        val today = clock.today()
        if (!announced.add(region to today) || records.any { it.date == today && region in it.regions }) return
        if (_state.value.schoolZone == null) speak(listOf(Announcements.regionEntered(region)))
    }

    private fun enterZone() {
        zonePass = SchoolZonePass(clock.elapsedNanos())
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
        log("어린이보호구역 통과: 최고 %.1fkm/h, 아이 지킴이 %s".format(pass.maxKph, kept))
    }

    private fun save(trip: CompletedTrip) = save(trip, announce = true)

    private fun save(trip: CompletedTrip, announce: Boolean) {
        leaveZone(trip.endNanos)
        val regions = tripRegions.toSet()
        val guardian = tripGuardian
        val stamp = arrival.finish()
        val extras = meter.finish(trip.result.distanceKm).copy(rested = tripRested, stamp = stamp)
        val pendingQuests = questLines.toList()
        tripRegions.clear()
        tripGuardian = 0
        tripRested = false
        questLines.clear()
        journal.clear()
        _state.update { it.copy(live = null) }
        rest.onTripEnd(clock.nowMs())
        if (pendingQuests.isNotEmpty() && announce) speak(pendingQuests)
        // D단에 잠깐 넣었다 뺀 경우처럼 거의 움직이지 않은 주행은 기록하지도 안내하지도 않는다
        if (trip.result.movingMinutes < MIN_TRIP_MOVING_MIN) {
            log("주행으로 보지 않음: 움직인 시간 ${trip.result.movingMinutes}분")
            return
        }
        // 차량 신호 시각(단조 시계 기준)을 벽시계 시각으로 바꾼다
        fun epochMs(ns: Long) = epochOffsetMs + ns / 1_000_000

        val firstStamp = stamp != null && records.none { it.stamp == stamp }
        val record = TripRecord.of(trip, epochMs(trip.startNanos), epochMs(trip.endNanos), clock.zone, regions, guardian, extras, firstStamp)
        store.append(record)
        records = records + record
        val before = _state.value.progress
        val after = summary(records)
        val celebrations = if (announce) celebrationsFor(trip, record, before, after, guardian) else emptyList()
        _state.update { it.copy(progress = after, recentTrips = recent(records), celebrations = it.celebrations + celebrations) }
        log("주행 저장 ${record.toCsv()} 감찰=${trip.result.inspection}")
        // 재부팅 뒤 마감한 지난 주행은 조용히 저장만 한다
        if (announce) speak(Announcements.tripEndLines(trip.result, before, after, guardian, record))
    }

    private fun summary(all: List<TripRecord>) = summarize(all, clock.today(), shieldsEarned = settings.shields(settings.activeProfile))

    private fun currentEpochOffsetMs() = clock.nowMs() - clock.elapsedNanos() / 1_000_000

    private fun recent(all: List<TripRecord>) = all.takeLast(5).reversed()

    private companion object {
        const val MIN_TRIP_MOVING_MIN = 0.5
        const val DAY_MS = 86_400_000L
        const val TICK_MS = 5_000L
        const val LIVE_TICK_MS = 1_000L
        const val JOURNAL_EXTRAS_MS = 15_000L
        const val QUEST_CONFIRM_MS = 3_000L
        /** 경로 위 휴게소가 이보다 가까우면 검색 화면 대신 '앞길에 휴게소' 안내만 한다. */
        const val REST_AREA_AHEAD_M = 30_000.0
    }
}
