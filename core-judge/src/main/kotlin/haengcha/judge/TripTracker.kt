package haengcha.judge

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 끝난 주행 한 번. 시각은 차속 표본과 같은 단조 시계(ns)다. */
data class CompletedTrip(val startNanos: Long, val endNanos: Long, val result: TripResult)

/**
 * 주행의 시작과 끝을 정하고 그 사이 차속을 판정 엔진에 넘긴다.
 * 기어를 읽을 수 있으면 P단을 벗어날 때 시작하고 P단으로 돌아올 때 끝낸다.
 * 기어를 못 읽는 차량이면 [startMps] 넘게 움직일 때 시작하고 [idleEndSec] 넘게 서 있으면 끝낸다.
 * 주행이 시작될 때마다 [planInspection]으로 이번 주행의 암행어사 감찰 계획을 받는다.
 */
class TripTracker(
    private val harsh: HarshConfig = HarshConfig(),
    private val xp: XpRule = XpRule(),
    private val startMps: Double = 1.0,
    private val idleEndSec: Double = 180.0,
    private val planInspection: () -> InspectionPlan? = { null },
) {
    private var judge: TripJudge? = null
    private var startNanos = 0L
    private var gearKnown = false
    private var stoppedSince: Long? = null

    val inTrip get() = judge != null

    /** 기어를 읽어 주행 시작·종료를 정하는지. */
    val readsGear get() = gearKnown

    /** 진행 중인 주행의 시작 시각과 감찰 계획(일지에 남긴다). */
    val tripStartNanos get() = startNanos
    var plan: InspectionPlan? = null
        private set

    /** 프로세스가 다시 뜬 뒤 일지에 남긴 주행을 이어 간다. 일지의 차속 표본은 [onSpeed]로 다시 넣는다. */
    fun resume(startNanos: Long, gearKnown: Boolean, inspection: InspectionPlan?) {
        this.gearKnown = gearKnown
        begin(startNanos, inspection)
    }

    /** 진행 중인 주행의 지금까지 판정(주행 중 화면용). 주행 중이 아니면 null. */
    fun live(): LiveTrip? = judge?.live()

    /** 진행 중인 주행을 [tNanos]에 끝낸다(다른 부팅에서 일지를 마감할 때). */
    fun endAt(tNanos: Long): CompletedTrip? = if (judge != null) end(tNanos) else null

    fun onGear(gear: Gear, tNanos: Long): CompletedTrip? {
        if (gear == Gear.UNKNOWN) return null
        gearKnown = true
        return when {
            gear == Gear.PARK && judge != null -> end(tNanos)
            gear != Gear.PARK && judge == null -> {
                begin(tNanos)
                null
            }
            else -> null
        }
    }

    fun onSpeed(sample: SpeedSample): CompletedTrip? {
        if (!gearKnown && judge == null && sample.mps >= startMps) begin(sample.tNanos)
        val current = judge ?: return null
        current.add(sample)
        if (gearKnown) return null

        // 기어를 못 읽을 때만 쓰는 정차 종료
        if (sample.mps >= xp.movingMps) {
            stoppedSince = null
            return null
        }
        val since = stoppedSince ?: sample.tNanos.also { stoppedSince = it }
        return if (sample.tNanos - since >= (idleEndSec * 1e9).toLong()) end(sample.tNanos) else null
    }

    private fun begin(tNanos: Long, inspection: InspectionPlan? = planInspection()) {
        plan = inspection
        judge = TripJudge(harsh, xp, inspection)
        startNanos = tNanos
        stoppedSince = null
    }

    private fun end(tNanos: Long): CompletedTrip {
        val result = judge!!.finish()
        judge = null
        return CompletedTrip(startNanos, tNanos, result)
    }
}
