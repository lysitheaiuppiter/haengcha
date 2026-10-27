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
    fun `녹음 파일이 모두 같은 형식의 PCM WAV다`() {
        val parsed = index.values.map { name -> requireNotNull(Wav.parse(File(dir, name).readBytes())) { name } }
        assertTrue(parsed.all { it.sameFormat(parsed.first()) })
    }
}
