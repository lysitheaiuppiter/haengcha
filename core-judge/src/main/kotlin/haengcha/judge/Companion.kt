package haengcha.judge

import java.time.LocalDate
import java.time.YearMonth

/**
 * 주행 보상 동반자(기획서 4.8). 이달 주행 횟수가 [monthlyTrips]에 닿으면 행차 자체 캐릭터(거북이)가 합류한다(임시값).
 * 판매하지 않고, 달이 바뀌면 다시 모은다.
 */
data class CompanionRule(val monthlyTrips: Int = 10)

data class CompanionStatus(val tripsThisMonth: Int, val goal: Int) {
    val joined: Boolean get() = tripsThisMonth >= goal
}

fun companionStatus(records: List<TripRecord>, today: LocalDate, rule: CompanionRule = CompanionRule()) =
    CompanionStatus(records.count { YearMonth.from(it.date) == YearMonth.from(today) }, rule.monthlyTrips)
