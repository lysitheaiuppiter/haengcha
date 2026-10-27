package haengcha.judge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 역참에서 쉬어가기와 안내 타이밍. */
class RestTest {

    private val min = 60_000L

    @Test
    fun `두 시간을 쉬지 않고 달리면 한 번 알린다`() {
        val r = RestTracker()
        assertFalse(r.onTripStart(0))
        assertFalse(r.onDriving(119 * min))
        assertTrue(r.onDriving(120 * min))
        assertFalse(r.onDriving(121 * min), "한 번만 알린다")
        assertTrue(r.due)
    }

    @Test
    fun `짧게 선 것은 이어 달린 것으로 본다`() {
        val r = RestTracker()
        r.onTripStart(0)
        r.onTripEnd(60 * min)
        assertFalse(r.onTripStart(70 * min))
        assertTrue(r.onDriving(121 * min), "10분 쉰 것은 쉰 것이 아니다")
    }

    @Test
    fun `알린 뒤 15분 넘게 쉬고 떠나면 보너스를 주고 새로 잰다`() {
        val r = RestTracker()
        r.onTripStart(0)
        r.onDriving(120 * min)
        r.onTripEnd(125 * min)
        assertEquals(5 * min, r.restLeftMs(135 * min, inTrip = false))
        assertNull(r.restLeftMs(135 * min, inTrip = true))
        assertTrue(r.onTripStart(141 * min))
        assertFalse(r.due)
        assertFalse(r.onDriving(200 * min))
    }

    @Test
    fun `알리지 않았으면 오래 쉬어도 보너스가 없다`() {
        val r = RestTracker()
        r.onTripStart(0)
        r.onTripEnd(30 * min)
        assertFalse(r.onTripStart(90 * min))
    }

    @Test
    fun `상태를 문자열로 남겼다가 되살린다`() {
        val r = RestTracker()
        r.onTripStart(5)
        r.onDriving(120 * min + 5)
        r.onTripEnd(130 * min)
        val back = RestTracker()
        back.decode(r.encode())
        assertEquals(r.encode(), back.encode())
        assertTrue(back.due)
        RestTracker().apply { decode(null); assertNull(sessionStartMs) }
    }

    @Test
    fun `회전이 가까우면 음성을 미뤘다가 지나면 낸다`() {
        val g = VoiceGate()
        assertEquals(listOf(listOf("a")), g.offer(listOf("a"), 0))
        g.onNextTurn(150.0, 0)
        assertEquals(emptyList(), g.offer(listOf("b"), 1_000))
        assertEquals(emptyList(), g.offer(listOf("c"), 2_000))
        assertTrue(g.holding)
        assertEquals(listOf(listOf("b"), listOf("c")), g.onNextTurn(800.0, 5_000))
        assertFalse(g.holding)
    }

    @Test
    fun `어린이보호구역 안내는 미루지 않고 30초보다 오래 미루지도 않는다`() {
        val g = VoiceGate()
        g.onNextTurn(80.0, 0)
        assertEquals(listOf(listOf("zone")), g.offer(listOf("zone"), 0, urgent = true))
        assertEquals(emptyList(), g.offer(listOf("b"), 0))
        assertEquals(emptyList(), g.tick(29_000))
        assertEquals(listOf(listOf("b")), g.tick(30_000))
    }

    @Test
    fun `경로가 없어지면 미룬 안내를 낸다`() {
        val g = VoiceGate()
        g.onNextTurn(50.0, 0)
        g.offer(listOf("b"), 0)
        assertEquals(listOf(listOf("b")), g.onNextTurn(null, 1_000))
    }
}
