package haengcha.judge

import java.util.Locale

/**
 * 진행 중인 주행 일지의 머리. 시각은 차속 표본과 같은 단조 시계(ns, 부팅 기준)라 [bootId]가 다르면 이어 갈 수 없다.
 * [epochOffsetMs]는 단조 시계 0 시점의 벽시계 시각(ms)으로, 다른 부팅에서 마감할 때 기록 시각을 계산하는 데 쓴다.
 */
data class JournalHeader(
    val startNanos: Long,
    val gearKnown: Boolean,
    val bootId: String,
    val epochOffsetMs: Long,
    val inspection: InspectionPlan?,
)

/** 일지에서 다시 읽은 주행. [extras]는 차량 신호 판정 중간값(같은 키는 마지막 값). */
data class JournalState(
    val header: JournalHeader,
    val samples: List<SpeedSample>,
    val regions: Set<Region>,
    val guardian: Int,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * 진행 중인 주행 일지 형식. 앱 프로세스가 주행 도중 종료돼도 다시 떠서 이어 가거나(같은 부팅) 마감할 수 있게
 * 주행 시작 정보, 차속 표본, 지나간 지역, 아이 지킴이 통과, 차량 신호 판정 중간값을 한 줄씩 남긴다. 좌표는 남기지 않는다.
 */
object TripJournal {
    const val GUARDIAN = "g"

    /** 차량 신호 판정 중간값 한 줄(키에는 쉼표를 쓰지 않는다). */
    fun extra(key: String, value: String): String = "x,$key,$value"

    fun header(h: JournalHeader): String = listOf(
        "trip", h.startNanos, if (h.gearKnown) 1 else 0, h.bootId, h.epochOffsetMs,
        h.inspection?.offsetSec ?: "", h.inspection?.rule?.windowSec ?: "",
        h.inspection?.rule?.minMovingSec ?: "", h.inspection?.rule?.mapaeXp ?: "",
    ).joinToString(",")

    fun sample(s: SpeedSample): String = String.format(Locale.ROOT, "s,%d,%.4f", s.tNanos, s.mps)

    fun region(r: Region): String = "r,${r.name}"

    /** 첫 줄이 머리가 아니면 null. 쓰다 끊긴 마지막 줄처럼 깨진 줄은 건너뛴다. */
    fun parse(lines: List<String>): JournalState? {
        val head = lines.firstOrNull()?.split(',') ?: return null
        if (head.size != 9 || head[0] != "trip") return null
        val header = runCatching {
            val plan = head[5].takeIf { it.isNotEmpty() }?.let {
                InspectionPlan(it.toDouble(), InspectionRule(windowSec = head[6].toDouble(), minMovingSec = head[7].toDouble(), mapaeXp = head[8].toInt()))
            }
            JournalHeader(head[1].toLong(), head[2] == "1", head[3], head[4].toLong(), plan)
        }.getOrNull() ?: return null
        val samples = mutableListOf<SpeedSample>()
        val regions = mutableSetOf<Region>()
        var guardian = 0
        val extras = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val f = line.split(',')
            runCatching {
                when (f[0]) {
                    "s" -> if (f.size == 3) samples += SpeedSample(f[1].toLong(), f[2].toDouble())
                    "r" -> regions += Region.valueOf(f[1])
                    GUARDIAN -> guardian++
                    "x" -> if (f.size == 3) extras[f[1]] = f[2]
                }
            }
        }
        return JournalState(header, samples, regions, guardian, extras)
    }
}
