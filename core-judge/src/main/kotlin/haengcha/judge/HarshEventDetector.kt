package haengcha.judge

import kotlin.math.max
import kotlin.math.min

/** 차속 표본. [tNanos]는 단조 증가하는 시각(VHAL 이벤트 타임스탬프 등), [mps]는 m/s. */
data class SpeedSample(val tNanos: Long, val mps: Double)

enum class HarshType { ACCEL, DECEL }

/** 급가속·급감속 한 건. 기준을 넘은 첫 표본과 마지막 표본의 시각, 그 사이 최대 가감속(m/s²). */
data class HarshEvent(val type: HarshType, val startNanos: Long, val endNanos: Long, val peak: Double)

/**
 * 판정 설정. 가감속은 한국교통안전공단 운행기록 분석과 같이 1초 전 속도와 현재 속도의 차이로 구한다.
 * 임계값은 공식 수치를 확인하기 전까지 쓰는 임시값이다.
 */
data class HarshConfig(
    val windowSec: Double = 1.0,
    val accelThreshold: Double = 3.0,
    val decelThreshold: Double = -3.5,
    /** 기준을 벗어난 뒤 이 시간 안에 다시 넘으면 같은 건으로 묶는다(공단 과속 판정의 3초 규칙). */
    val mergeGapSec: Double = 3.0,
    /** 비교할 표본이 창보다 이만큼 넘게 오래됐으면 수신이 끊긴 것으로 보고 판정하지 않는다. */
    val maxGapSec: Double = 1.0,
)

/**
 * 차속 표본을 시간순으로 받아 급가속·급감속 건을 낸다.
 * 한 건은 기준을 벗어나고 [HarshConfig.mergeGapSec]가 지나야 확정되므로, 주행이 끝나면 [flush]를 부른다.
 */
class HarshEventDetector(private val config: HarshConfig = HarshConfig()) {

    private val windowNanos = (config.windowSec * 1e9).toLong()
    private val maxGapNanos = (config.maxGapSec * 1e9).toLong()
    private val mergeGapNanos = (config.mergeGapSec * 1e9).toLong()

    private val recent = ArrayDeque<SpeedSample>()
    private val open = mutableMapOf<HarshType, OpenEvent>()

    private class OpenEvent(val start: Long, var lastOver: Long, var peak: Double)

    /** 표본 하나를 넣고 이번에 확정된 건을 돌려준다. 시간이 거꾸로 가는 표본은 버린다. */
    fun add(sample: SpeedSample): List<HarshEvent> {
        if (recent.isNotEmpty() && sample.tNanos <= recent.last().tNanos) return emptyList()
        val a = acceleration(sample)
        recent.addLast(sample)
        // 다음 표본의 비교 대상이 될 수 있는 가장 오래된 표본 하나만 남긴다
        while (recent.size > 1 && recent[1].tNanos <= sample.tNanos - windowNanos) recent.removeFirst()

        val done = mutableListOf<HarshEvent>()
        for (type in HarshType.entries) {
            val over = a != null && if (type == HarshType.ACCEL) a >= config.accelThreshold else a <= config.decelThreshold
            val ev = open[type]
            when {
                over && ev == null -> open[type] = OpenEvent(sample.tNanos, sample.tNanos, a!!)
                over -> {
                    ev!!.lastOver = sample.tNanos
                    ev.peak = if (type == HarshType.ACCEL) max(ev.peak, a!!) else min(ev.peak, a!!)
                }
                ev != null && sample.tNanos - ev.lastOver > mergeGapNanos -> done += close(type)
            }
        }
        return done
    }

    /** 아직 열려 있는 건을 모두 확정한다. */
    fun flush(): List<HarshEvent> = open.keys.toList().map { close(it) }

    private fun close(type: HarshType): HarshEvent {
        val ev = open.remove(type)!!
        return HarshEvent(type, ev.start, ev.lastOver, ev.peak)
    }

    private fun acceleration(sample: SpeedSample): Double? {
        val target = sample.tNanos - windowNanos
        val ref = recent.lastOrNull { it.tNanos <= target } ?: return null
        if (target - ref.tNanos > maxGapNanos) return null
        return (sample.mps - ref.mps) / ((sample.tNanos - ref.tNanos) / 1e9)
    }
}
