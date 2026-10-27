package haengcha.judge

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressTest {

    @Test
    fun `Lv0에서 시작하고 레벨별 누적 경험치가 늘어난다`() {
        assertEquals(0, Levels.xpFor(0))
        assertEquals(100, Levels.xpFor(1))
        assertEquals(300, Levels.xpFor(2))
        assertEquals(1000, Levels.xpFor(4))
        assertEquals(0, Levels.levelOf(0))
        assertEquals(0, Levels.levelOf(99))
        assertEquals(1, Levels.levelOf(100))
        assertEquals(3, Levels.levelOf(999))
        assertEquals(4, Levels.levelOf(1000))
    }

    @Test
    fun `Lv0 소달구지, Lv1 인력거, Lv4 가마`() {
        assertEquals(listOf(JoseonTheme.OX_CART), JoseonTheme.unlocked(0))
        assertEquals(listOf(JoseonTheme.OX_CART, JoseonTheme.RICKSHAW), JoseonTheme.unlocked(3))
        assertEquals(listOf(JoseonTheme.OX_CART, JoseonTheme.RICKSHAW, JoseonTheme.GAMA), JoseonTheme.unlocked(4))
    }

    private val monday = LocalDate.of(2026, 10, 26)
    private fun day(offset: Long, minutes: Double = 30.0, km: Double = 15.0, harsh: Int = 0) =
        DayDriving(monday.plusDays(offset), minutes, km, harsh)

    @Test
    fun `운전하지 않은 날은 개근을 끊지 않는다`() {
        // 월·화 안전, 수요일 운전 안 함, 목 안전
        assertEquals(3, currentStreak(listOf(day(0), day(1), day(3))))
    }

    @Test
    fun `기준을 못 지킨 날 개근이 처음부터 다시 쌓인다`() {
        assertEquals(1, currentStreak(listOf(day(0), day(1), day(2, harsh = 2), day(3))))
    }

    @Test
    fun `10km마다 1건까지 봐주고 잠깐 달린 날은 세지 않는다`() {
        assertEquals(true, day(0, km = 15.0, harsh = 1).isSafe())
        assertEquals(false, day(0, km = 8.0, harsh = 1).isSafe())
        assertNull(day(0, minutes = 3.0, harsh = 3).isSafe())
    }
}
