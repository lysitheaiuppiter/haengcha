package haengcha.drive

import haengcha.judge.JournalHeader
import haengcha.judge.JournalState
import haengcha.judge.Quest
import haengcha.judge.TripRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * 주행 엔진([DriveEngine])이 바깥과 주고받는 것. 앱은 Android 구현(Handler, SharedPreferences, 파일)을,
 * 테스트는 가짜 시계·메모리 저장소를 넣는다.
 */

/** 벽시계(ms)와 부팅 기준 단조 시계(ns). 차량 신호 시각은 단조 시계 기준이다. */
interface Clock {
    fun nowMs(): Long
    fun elapsedNanos(): Long
    val zone: ZoneId get() = ZoneId.systemDefault()
}

fun Clock.today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()

fun Clock.timeOfDay(): LocalTime = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalTime()

/** [delayMs] 뒤에 [action]을 실행한다. 엔진의 모든 호출은 한 스레드에서 일어난다(앱은 메인 스레드). */
fun interface Scheduler {
    fun post(delayMs: Long, action: () -> Unit)
}

/** 운전자 한 명의 주행 기록. */
interface RecordStore {
    fun load(): List<TripRecord>
    fun append(record: TripRecord)
}

/** 진행 중인 주행 일지(형식은 [haengcha.judge.TripJournal]). 주행이 끝나면 지운다. */
interface JournalStore {
    fun load(): JournalState?
    fun begin(header: JournalHeader)
    /** 다시 읽은 일지에 이어 쓴다. */
    fun reopen()
    fun append(line: String, flush: Boolean = false)
    fun clear()
}

/** 운전자 프로필(기획서 12.1 목업). 프로필마다 주행 기록을 따로 쌓는다. */
data class Profile(val id: String, val name: String) {
    companion object {
        val DEFAULT = Profile("default", "운전자 1")
    }
}

/**
 * 앱 설정에 두는 값. 차량에 딸린 상태(열린 대장간 퀘스트, 역참 연속 운전)는 운전자와 상관없이 하나,
 * 개근 보호권은 운전자마다 둔다.
 */
interface DriveSettings {
    val profiles: List<Profile>
    var activeProfile: String
    fun addProfile(): Profile
    /** 보유한 콜라보 동반자(시연용 목업). */
    var owned: Set<String>
    /** 대장간 퀘스트로 받은 개근 보호권 수(쓴 것 포함). */
    fun shields(profileId: String): Int
    fun addShield(profileId: String)
    /** 열려 있는 대장간 퀘스트와 연 시각(ms). */
    var openQuests: Map<Quest, Long>
    /** 역참 연속 운전 상태([haengcha.judge.RestTracker.encode]). */
    var restState: String?
}
