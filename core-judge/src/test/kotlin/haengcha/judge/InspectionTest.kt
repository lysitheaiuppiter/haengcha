package haengcha.judge

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InspectionTest {

    private val plan = InspectionPlan(offsetSec = 60.0, rule = InspectionRule())

    private fun judge(samples: List<SpeedSample>, plan: InspectionPlan? = this.plan): TripResult {
        val judge = TripJudge(inspection = plan)
        samples.forEach { judge.add(it) }
        return judge.finish()
    }

    @Test
    fun `10분 동안 차분히 달리면 통과하고 마패 경험치를 받는다`() {
        val result = judge(Profile(50.0).hold(900.0).build())
        assertEquals(InspectionOutcome.PASSED, result.inspection?.outcome)
        assertTrue(result.mapae)
        // 안전 15구간 150XP + 마패 30XP
        assertEquals(180, result.xp)
    }

    @Test
    fun `감찰 중 급가속이 있으면 실패하고 마패가 없다`() {
        // 300초쯤 50→80km/h 2초 급가속
        val result = judge(Profile(50.0).hold(300.0).ramp(2.0, 80.0).ramp(5.0, 50.0).hold(600.0).build())
        assertEquals(InspectionOutcome.FAILED, result.inspection?.outcome)
        assertEquals(1, result.inspection?.harshCount)
        assertFalse(result.mapae)
    }

    @Test
    fun `감찰이 끝난 뒤의 급가속은 감찰과 상관없다`() {
        // 감찰은 60~660초, 급가속은 700초쯤
        val result = judge(Profile(50.0).hold(700.0).ramp(2.0, 80.0).ramp(5.0, 50.0).hold(100.0).build())
        assertEquals(InspectionOutcome.PASSED, result.inspection?.outcome)
        assertEquals(1, result.harshAccel)
    }

    @Test
    fun `주행이 감찰보다 먼저 끝나면 미완료`() {
        val result = judge(Profile(50.0).hold(300.0).build())
        assertEquals(InspectionOutcome.NOT_COMPLETED, result.inspection?.outcome)
        assertFalse(result.mapae)
    }

    @Test
    fun `감찰 시간 대부분을 서 있었으면 미완료`() {
        // 2분 달리고 10분 정차 후 다시 출발
        val result = judge(Profile(50.0).hold(120.0).ramp(10.0, 0.0).hold(600.0).ramp(10.0, 50.0).hold(60.0).build())
        assertEquals(InspectionOutcome.NOT_COMPLETED, result.inspection?.outcome)
    }

    @Test
    fun `감찰 계획이 없으면 결과도 없다`() {
        assertNull(judge(Profile(50.0).hold(900.0).build(), plan = null).inspection)
    }

    @Test
    fun `하루 횟수를 채웠거나 확률에 걸리지 않으면 감찰하지 않는다`() {
        val always = InspectionPlanner(InspectionRule(chancePerTrip = 1.0), Random(7))
        val p = assertNotNull(always.plan(inspectionsToday = 0))
        assertTrue(p.offsetSec in 60.0..900.0)
        assertNull(always.plan(inspectionsToday = 2))
        assertNull(InspectionPlanner(InspectionRule(chancePerTrip = 0.0), Random(7)).plan(0))
    }

    @Test
    fun `주행을 시작할 때마다 감찰 계획을 받는다`() {
        var asked = 0
        val tracker = TripTracker(planInspection = { asked++; plan })
        tracker.onGear(Gear.DRIVE, 0)
        Profile(50.0).hold(700.0).build().forEach { tracker.onSpeed(it.copy(tNanos = it.tNanos + 1)) }
        val trip = assertNotNull(tracker.onGear(Gear.PARK, 701_000_000_000))
        assertEquals(1, asked)
        assertEquals(InspectionOutcome.PASSED, trip.result.inspection?.outcome)
    }

    @Test
    fun `감찰 결과를 기록에 남기고 예전 10열 기록도 읽는다`() {
        val passed = TripResult(5.0, 10.0, 10, 0, 130, emptyList(), InspectionResult(InspectionOutcome.PASSED, 600.0, 0))
        val record = TripRecord.of(CompletedTrip(0, 1, passed), 1_792_990_000_000, 1_792_990_600_000, ZoneOffset.ofHours(9))
        assertTrue(record.inspected && record.mapae)
        assertEquals(record, TripRecord.parse(record.toCsv()))

        val notCompleted = passed.copy(inspection = InspectionResult(InspectionOutcome.NOT_COMPLETED, 100.0, 0))
        assertFalse(TripRecord.of(CompletedTrip(0, 1, notCompleted), 0, 0, ZoneOffset.UTC).inspected)

        val legacy = TripRecord.parse("1792991848004,1792992078029,2026-10-25,1.670,3.63,50,0,0,4,2")
        assertEquals(false, legacy?.inspected)
        assertEquals(50, legacy?.xp)
    }

    @Test
    fun `마패 수와 오늘 감찰 횟수를 센다`() {
        val today = LocalDate.of(2026, 10, 27)
        fun trip(date: LocalDate, inspected: Boolean, mapae: Boolean) =
            TripRecord(0, 0, date, 10.0, 20.0, 100, 0, 0, 10, 0, inspected, mapae)
        val summary = summarize(
            listOf(trip(today.minusDays(1), true, true), trip(today, true, false), trip(today, false, false)),
            today,
        )
        assertEquals(1, summary.mapaeCount)
        assertEquals(1, summary.inspectionsToday)
    }
}
