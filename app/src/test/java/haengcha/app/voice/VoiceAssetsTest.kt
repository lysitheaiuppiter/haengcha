package haengcha.app.voice

import haengcha.judge.Announcements
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 늘 녹음으로 재생하는 탑승 외침과, Pleos TTS SDK를 못 쓸 때 재생할 안전 안내 예비 녹음을 확인한다.
 * 문장을 바꾸고 녹음을 다시 만들지 않았으면 여기서 걸린다.
 */
class VoiceAssetsTest {

    private val dir = File("src/main/assets/voice")
    private val index = File(dir, "index.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        .associate { line -> line.split('\t').let { it[0] to it[1] } }

    @Test
    fun `탑승 외침과 안전 안내마다 녹음이 있다`() {
        val safety = listOf(
            Announcements.SCHOOL_ZONE, Announcements.BELT,
            Announcements.REST_DUE, Announcements.REST_AREA_AHEAD, Announcements.REST_SEARCH,
        )
        assertEquals(safety.toSet() + Announcements.BOARDING, index.keys)
    }

    @Test
    fun `녹음은 모두 앱이 말하는 문장이다`() {
        val all = Announcements.all().toSet()
        assertEquals(emptyList(), index.keys.filterNot { it in all })
    }

    @Test
    fun `녹음 파일이 모두 모노 Ogg Opus다`() {
        // 첫 Ogg 페이지(27바이트 머리글 + 구간 표 1바이트) 뒤에 OpusHead가 온다. 채널 수는 그다음 2번째 바이트
        index.values.forEach { name ->
            val b = File(dir, name).readBytes()
            assertEquals("OggS", String(b, 0, 4, Charsets.US_ASCII), name)
            assertEquals("OpusHead", String(b, 28, 8, Charsets.US_ASCII), name)
            assertEquals(1, b[37].toInt(), name)
        }
    }

    @Test
    fun `예비 녹음 앞에 재생할 묵음 녹음이 있다`() {
        assertTrue(File(dir, "_preroll.ogg").isFile)
    }

    @Test
    fun `녹음 폴더에는 색인에 있는 녹음과 묵음 녹음만 있다`() {
        val files = dir.listFiles()!!.map { it.name }.filter { it.endsWith(".ogg") }.toSet()
        assertEquals(index.values.toSet() + "_preroll.ogg", files)
    }
}
