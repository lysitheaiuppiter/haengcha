package haengcha.judge

import kotlin.random.Random

/**
 * 암행어사 감찰 규칙(기획서 4.6, 값은 임시). 주행 중 무작위로 정한 [windowSec] 동안 몰래 살피고,
 * 그동안 급가속·급감속이 없었으면 주차 후에 출두해 마패를 준다. 기준에 못 미치면 아무것도 알리지 않는다.
 */
data class InspectionRule(
    val windowSec: Double = 600.0,
    val maxPerDay: Int = 2,
    val chancePerTrip: Double = 0.6,
    /** 감찰 시작 시점. 주행 시작 후 이 범위에서 고르므로 이보다 짧은 주행은 감찰을 마치지 못한다. */
    val minOffsetSec: Double = 60.0,
    val maxOffsetSec: Double = 900.0,
    /** 감찰 시간 중 이만큼은 달려야 판정한다. 거의 서 있었으면 미완료로 본다. */
    val minMovingSec: Double = 300.0,
    val mapaeXp: Int = 30,
)

/** 이번 주행의 감찰 계획. 시작 시점은 주행 첫 차속 표본 기준이다. */
data class InspectionPlan(val offsetSec: Double, val rule: InspectionRule)

enum class InspectionOutcome {
    PASSED,
    FAILED,
    /** 주행이 감찰 시간보다 먼저 끝났거나 거의 서 있었다. 하루 횟수에 넣지 않는다. */
    NOT_COMPLETED,
}

data class InspectionResult(val outcome: InspectionOutcome, val movingSec: Double, val harshCount: Int)

/** 주행마다 감찰을 할지, 한다면 주행 시작 몇 초 뒤에 시작할지 정한다. */
class InspectionPlanner(var rule: InspectionRule = InspectionRule(), private val random: Random = Random.Default) {

    fun plan(inspectionsToday: Int): InspectionPlan? {
        if (inspectionsToday >= rule.maxPerDay) return null
        if (random.nextDouble() >= rule.chancePerTrip) return null
        val offset = rule.minOffsetSec + random.nextDouble() * (rule.maxOffsetSec - rule.minOffsetSec)
        return InspectionPlan(offset, rule)
    }
}
