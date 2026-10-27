package haengcha.app.voice

import haengcha.judge.Announcements
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 앱이 말할 수 있는 문장마다 녹음이 있는지 확인한다. 문장을 바꾸고 녹음을 다시 만들지 않았으면 여기서 걸린다. */
class VoiceAssetsTest {

    private val dir = File("src/main/assets/voice")
    private val index = File(dir, "index.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        .associate { line -> line.split('\t').let { it[0] to it[1] } }

    @Test
    fun `모든 안내 문장에 녹음이 있다`() {
        val missing = Announcements.all().filterNot { it in index }
        assertEquals(emptyList(), missing)
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
    fun `모든 안내 앞에 재생할 묵음 녹음이 있다`() {
        assertTrue(File(dir, "_preroll.ogg").isFile)
    }

    @Test
    fun `녹음은 모두 합쳐 5MB를 넘지 않는다`() {
        val total = index.values.sumOf { File(dir, it).length() }
        assertTrue(total < 5_000_000, "녹음 ${total}바이트")
    }
}
