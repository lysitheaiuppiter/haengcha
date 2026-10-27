package haengcha.drive

import haengcha.judge.Buddy
import haengcha.judge.Gear
import haengcha.judge.JournalHeader
import haengcha.judge.JournalState
import haengcha.judge.Quest
import haengcha.judge.SpeedSample
import haengcha.judge.Title
import haengcha.judge.TripJournal
import haengcha.judge.TripRecord
import java.time.ZoneId
import java.time.ZonedDateTime

/** 가짜 시계 겸 예약 실행기. [advance]로 시간을 넘기면 그 사이에 예약된 일을 시각 순서대로 실행한다. */
class FakeClock(start: ZonedDateTime = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, SEOUL)) : Clock, Scheduler {
    private var ms = start.toInstant().toEpochMilli()
    private var nanos = 50_000_000_000L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    override val zone: ZoneId = start.zone

    override fun nowMs() = ms
    override fun elapsedNanos() = nanos

    override fun post(delayMs: Long, action: () -> Unit) {
        tasks += (ms + delayMs) to action
    }

    fun advance(stepMs: Long) {
        val end = ms + stepMs
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            moveTo(maxOf(ms, next.first))
            next.second()
        }
        moveTo(end)
    }

    private fun moveTo(t: Long) {
        nanos += (t - ms) * 1_000_000
        ms = t
    }

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

class MemoryStore : RecordStore {
    val records = mutableListOf<TripRecord>()
    override fun load() = records.toList()
    override fun append(record: TripRecord) {
        records += record
    }
}

/** 메모리 일지. 줄은 [TripJournal] 형식 그대로 쌓아, 다시 뜬 엔진이 같은 방식으로 읽는다. */
class MemoryJournal : JournalStore {
    var lines: MutableList<String>? = null
    override fun load(): JournalState? = lines?.let { TripJournal.parse(it) }
    override fun begin(header: JournalHeader) {
        lines = mutableListOf(TripJournal.header(header))
    }
    override fun reopen() = Unit
    override fun append(line: String, flush: Boolean) {
        lines?.add(line)
    }
    override fun clear() {
        lines = null
    }
}

class FakeSettings : DriveSettings {
    override var profiles = listOf(Profile.DEFAULT)
    override var activeProfile = Profile.DEFAULT.id
    override fun addProfile() = Profile("p${profiles.size}", "운전자 ${profiles.size + 1}").also { profiles = profiles + it }
    override var owned = emptySet<String>()
    val shieldCounts = mutableMapOf<String, Int>()
    override fun shields(profileId: String) = shieldCounts[profileId] ?: 0
    override fun addShield(profileId: String) {
        shieldCounts[profileId] = shields(profileId) + 1
    }
    val buddies = mutableMapOf<String, Buddy>()
    override fun buddy(profileId: String) = buddies[profileId]
    override fun setBuddy(profileId: String, buddy: Buddy) {
        buddies[profileId] = buddy
    }
    val titles = mutableMapOf<String, Title>()
    override fun title(profileId: String) = titles[profileId]
    override fun setTitle(profileId: String, title: Title) {
        titles[profileId] = title
    }
    override var openQuests = emptyMap<Quest, Long>()
    override var restState: String? = null
}

/**
 * 주행 엔진 시험대. 가짜 시계·저장소·설정 위에 엔진을 띄우고, Virtual Console 시나리오처럼 기어와 차속을 10Hz로 넣는다.
 * [restart]는 같은 저장소·일지로 엔진을 새로 띄운다(프로세스가 죽었다 다시 뜬 경우).
 */
class Rig(
    val clock: FakeClock = FakeClock(),
    val settings: FakeSettings = FakeSettings(),
    val journal: MemoryJournal = MemoryJournal(),
    val stores: MutableMap<String, MemoryStore> = mutableMapOf(),
    bootId: String = "boot-1",
) {
    val said = mutableListOf<List<String>>()
    var restSearches = 0
    var engine = newEngine(bootId)
        private set

    val lines get() = said.flatten()
    val state get() = engine.state.value
    val records get() = stores.getValue(settings.activeProfile).records

    private fun newEngine(bootId: String) = DriveEngine(
        settings, { id -> stores.getOrPut(id) { MemoryStore() } }, journal, bootId, clock, clock,
        say = { said += it }, searchRestArea = { restSearches++ },
    )

    fun restart(bootId: String) {
        engine = newEngine(bootId)
    }

    /** [sec]초 동안 시간만 흐른다(정차, 신호 없음). */
    fun wait(sec: Double) = clock.advance((sec * 1000).toLong())

    /** 기어를 D에 넣는다. */
    fun shiftDrive() = engine.onGear(Gear.DRIVE, clock.elapsedNanos())

    fun shiftPark() = engine.onGear(Gear.PARK, clock.elapsedNanos())

    /**
     * 구간마다 (초, 시작 km/h, 끝 km/h)로 차속을 10Hz로 넣는다. [events]의 (시작 뒤 초, 할 일)은 그 시각에 실행한다.
     */
    fun speeds(vararg segments: Triple<Double, Double, Double>, events: List<Pair<Double, () -> Unit>> = emptyList()) {
        val pending = events.sortedBy { it.first }.toMutableList()
        var t = 0.0
        for ((dur, v0, v1) in segments) {
            val n = (dur * 10).toInt()
            for (i in 1..n) {
                clock.advance(100)
                t += 0.1
                while (pending.isNotEmpty() && pending.first().first <= t + 1e-9) pending.removeAt(0).second()
                engine.onSpeed(SpeedSample(clock.elapsedNanos(), (v0 + (v1 - v0) * i / n) / 3.6))
            }
        }
        pending.forEach { it.second() }
    }

    /** 급조작 없는 100초 주행(D단 → 출발 → 40km/h → 정지 → P단). 안전 구간 2개, 20XP. */
    fun calmTrip(events: List<Pair<Double, () -> Unit>> = emptyList()) {
        shiftDrive()
        speeds(*CALM, events = events)
        shiftPark()
    }

    companion object {
        val CALM = arrayOf(
            Triple(5.0, 0.0, 0.0),
            Triple(15.0, 0.0, 40.0),
            Triple(60.0, 40.0, 40.0),
            Triple(15.0, 40.0, 0.0),
            Triple(5.0, 0.0, 0.0),
        )
    }
}
