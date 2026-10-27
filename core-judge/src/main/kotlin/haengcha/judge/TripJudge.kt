package haengcha.judge

/**
 * 경험치 규칙(임시값). 주행을 1분 구간으로 나눠, 충분히 달린 구간 중 급가속·급감속이 없는 구간에 경험치를 준다.
 * 깎는 규칙은 없다. 기준을 못 지킨 구간은 경험치를 주지 않을 뿐이다.
 */
data class XpRule(
    val segmentSec: Double = 60.0,
    /** 구간 안에서 이보다 적게 움직였으면 정차 구간으로 보고 경험치를 주지 않는다. */
    val minMovingSec: Double = 30.0,
    val movingMps: Double = 0.5,
    val safeSegmentXp: Int = 10,
    /** 평정심 보너스: 움직인 동안 평균 차속이 이보다 낮은 정체 구간을 차분히 달린 경우. */
    val calmMaxKmh: Double = 20.0,
    val calmBonusXp: Int = 5,
)

data class TripResult(
    val distanceKm: Double,
    val movingMinutes: Double,
    val safeSegments: Int,
    val calmSegments: Int,
    /** 마패 보너스를 포함한 경험치. */
    val xp: Int,
    val events: List<HarshEvent>,
    /** 이번 주행에 감찰이 없었으면 null. */
    val inspection: InspectionResult? = null,
) {
    val harshAccel get() = events.count { it.type == HarshType.ACCEL }
    val harshDecel get() = events.count { it.type == HarshType.DECEL }
    val mapae get() = inspection?.outcome == InspectionOutcome.PASSED
}

/** 주행 중 화면의 1분 구간 표시. */
enum class SegmentMark { SAFE, CALM, HARSH, IDLE }

/**
 * 진행 중인 주행의 지금까지 판정(주행 중 화면용). [safeRun]은 끝에서부터 이어진 안전 구간 수(서 있던 구간은 건너뜀),
 * [segmentSec]은 진행 중인 1분 구간에서 지난 초, [segmentClean]은 그 구간에 아직 급가속·급감속이 없는지.
 */
data class LiveTrip(
    val distanceKm: Double = 0.0,
    val movingMinutes: Double = 0.0,
    val xp: Int = 0,
    val safeRun: Int = 0,
    val marks: List<SegmentMark> = emptyList(),
    val segmentSec: Double = 0.0,
    val segmentClean: Boolean = true,
)

/** 한 번의 주행(D단 진입부터 P단 복귀까지)을 받아 거리, 급가속·급감속, 경험치, 감찰 결과를 낸다. */
class TripJudge(
    harsh: HarshConfig = HarshConfig(),
    private val rule: XpRule = XpRule(),
    private val inspection: InspectionPlan? = null,
) {

    private class Segment {
        var movingSec = 0.0
        var movingM = 0.0
        var harsh = 0
    }

    private val detector = HarshEventDetector(harsh)
    private val events = mutableListOf<HarshEvent>()
    private val segments = sortedMapOf<Int, Segment>()
    private var startNanos: Long? = null
    private var prev: SpeedSample? = null
    private var inspectionMovingSec = 0.0

    fun add(sample: SpeedSample) {
        val p = prev
        if (p != null && sample.tNanos <= p.tNanos) return
        val start = startNanos ?: sample.tNanos.also { startNanos = it }
        if (p != null) {
            val dt = (sample.tNanos - p.tNanos) / 1e9
            // 수신이 끊긴 구간은 거리·주행 시간에 넣지 않는다
            if (dt <= MAX_INTEGRATION_GAP_SEC) {
                val v = (p.mps + sample.mps) / 2
                if (v >= rule.movingMps) {
                    val seg = segment(p.tNanos, start)
                    seg.movingSec += dt
                    seg.movingM += v * dt
                    if (inspectionWindow(start)?.contains(p.tNanos) == true) inspectionMovingSec += dt
                }
            }
        }
        prev = sample
        events += detector.add(sample)
    }

    fun finish(): TripResult {
        events += detector.flush()
        val start = startNanos ?: return TripResult(0.0, 0.0, 0, 0, 0, emptyList())
        for (e in events) segment(e.startNanos, start).harsh++

        val counted = segments.values.filter { it.movingSec >= rule.minMovingSec }
        val safe = counted.filter { it.harsh == 0 }
        val calm = safe.filter { it.movingM / it.movingSec * 3.6 < rule.calmMaxKmh }
        val inspected = inspection?.let { judgeInspection(it, inspectionWindow(start)!!) }
        val mapaeXp = if (inspected?.outcome == InspectionOutcome.PASSED) inspection!!.rule.mapaeXp else 0
        return TripResult(
            distanceKm = segments.values.sumOf { it.movingM } / 1000,
            movingMinutes = segments.values.sumOf { it.movingSec } / 60,
            safeSegments = safe.size,
            calmSegments = calm.size,
            xp = safe.size * rule.safeSegmentXp + calm.size * rule.calmBonusXp + mapaeXp,
            events = events.sortedBy { it.startNanos },
            inspection = inspected,
        )
    }

    /**
     * 주행 중 화면에 보여 줄 지금까지의 판정. 끝난 1분 구간만 경험치에 넣고(감찰 보너스 제외),
     * 진행 중인 구간은 지난 시간과 급가속·급감속이 아직 없는지만 낸다.
     */
    fun live(): LiveTrip {
        val start = startNanos ?: return LiveTrip()
        val now = prev?.tNanos ?: start
        val current = ((now - start) / 1e9 / rule.segmentSec).toInt()
        val harsh = mutableMapOf<Int, Int>()
        for (e in events) {
            val i = ((e.startNanos - start) / 1e9 / rule.segmentSec).toInt()
            harsh[i] = (harsh[i] ?: 0) + 1
        }
        val done = segments.filterKeys { it < current }
        val marks = (0 until current).map { i ->
            val seg = done[i]
            when {
                seg == null || seg.movingSec < rule.minMovingSec -> SegmentMark.IDLE
                (harsh[i] ?: 0) > 0 -> SegmentMark.HARSH
                seg.movingM / seg.movingSec * 3.6 < rule.calmMaxKmh -> SegmentMark.CALM
                else -> SegmentMark.SAFE
            }
        }
        val safe = marks.count { it == SegmentMark.SAFE || it == SegmentMark.CALM }
        val calm = marks.count { it == SegmentMark.CALM }
        // 서 있던 구간은 건너뛰고 끝에서부터 이어진 안전 구간을 센다
        val run = marks.filter { it != SegmentMark.IDLE }.takeLastWhile { it != SegmentMark.HARSH }.size
        return LiveTrip(
            distanceKm = segments.values.sumOf { it.movingM } / 1000,
            movingMinutes = segments.values.sumOf { it.movingSec } / 60,
            xp = safe * rule.safeSegmentXp + calm * rule.calmBonusXp,
            safeRun = run,
            marks = marks,
            segmentSec = (now - start) / 1e9 - current * rule.segmentSec,
            segmentClean = (harsh[current] ?: 0) == 0,
        )
    }

    private fun inspectionWindow(start: Long): LongRange? = inspection?.let {
        val from = start + (it.offsetSec * 1e9).toLong()
        from..from + (it.rule.windowSec * 1e9).toLong()
    }

    private fun judgeInspection(plan: InspectionPlan, window: LongRange): InspectionResult {
        val harsh = events.count { it.startNanos <= window.last && it.endNanos >= window.first }
        val outcome = when {
            (prev?.tNanos ?: 0) < window.last -> InspectionOutcome.NOT_COMPLETED
            harsh > 0 -> InspectionOutcome.FAILED
            inspectionMovingSec < plan.rule.minMovingSec -> InspectionOutcome.NOT_COMPLETED
            else -> InspectionOutcome.PASSED
        }
        return InspectionResult(outcome, inspectionMovingSec, harsh)
    }

    private fun segment(tNanos: Long, start: Long): Segment =
        segments.getOrPut(((tNanos - start) / 1e9 / rule.segmentSec).toInt()) { Segment() }

    private companion object {
        const val MAX_INTEGRATION_GAP_SEC = 2.0
    }
}
