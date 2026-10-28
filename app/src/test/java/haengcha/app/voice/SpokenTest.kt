package haengcha.app.voice

import haengcha.judge.Announcements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SpokenTest {

    @Test
    fun `레벨과 한우 등급, 붙여 쓴 명소 이름을 읽기 쉽게 고친다`() {
        assertEquals("레벨 12 달성!", Spoken.of("Lv.12 달성!"))
        assertEquals("단골 등급이 올랐습니다. 투뿔 한우입니다.", Spoken.of("단골 등급이 올랐습니다. 투뿔(1++) 한우입니다."))
        assertEquals("단골 등급이 올랐습니다. 원플러스 한우입니다.", Spoken.of("단골 등급이 올랐습니다. 1+ 한우입니다."))
        assertEquals("엔 서울타워 도착! 스탬프를 찍었습니다.", Spoken.of("N서울타워 도착! 스탬프를 찍었습니다."))
    }

    @Test
    fun `고친 안내 문장에 Lv 표기나 플러스 기호가 남지 않는다`() {
        Announcements.all().map(Spoken::of).forEach { line ->
            assertFalse("Lv." in line, line)
            assertFalse("+" in line, line)
        }
    }

    @Test
    fun `고칠 것이 없는 문장은 그대로 둔다`() {
        assertEquals(Announcements.BOARDING, Spoken.of(Announcements.BOARDING))
    }
}
