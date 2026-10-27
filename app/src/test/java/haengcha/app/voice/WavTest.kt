package haengcha.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavTest {

    private fun wav(sampleRate: Int, bits: Int, pcm: ByteArray, withList: Boolean = false, dataSize: Int = pcm.size): ByteArray {
        val list = if (withList) byteArrayOf(*"LIST".toByteArray(), 4, 0, 0, 0, *"INFO".toByteArray()) else ByteArray(0)
        return ByteBuffer.allocate(44 + list.size + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + list.size + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * bits / 8); putShort((bits / 8).toShort()); putShort(bits.toShort())
            put(list)
            put("data".toByteArray()); putInt(dataSize); put(pcm)
        }.array()
    }

    private fun dataOf(out: ByteArray): ByteArray {
        val size = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        return out.copyOfRange(44, 44 + size)
    }

    @Test
    fun `16비트 모노 22050Hz에 180ms 묵음을 붙인다`() {
        val pcm = ByteArray(1000) { 7 }
        val out = requireNotNull(Wav.withLeadingSilence(wav(22050, 16, pcm), 180))
        val data = dataOf(out)
        val silenceBytes = 22050 * 180 / 1000 * 2
        assertEquals(silenceBytes + pcm.size, data.size)
        assertTrue(data.copyOfRange(0, silenceBytes).all { it == 0.toByte() })
        assertTrue(data.copyOfRange(silenceBytes, data.size).contentEquals(pcm))
    }

    @Test
    fun `부가 청크를 건너뛰고 data 크기가 0으로 남은 파일도 끝까지 읽는다`() {
        val pcm = ByteArray(400) { 3 }
        val out = requireNotNull(Wav.withLeadingSilence(wav(16000, 16, pcm, withList = true, dataSize = 0), 150))
        assertEquals(16000 * 150 / 1000 * 2 + pcm.size, dataOf(out).size)
    }

    @Test
    fun `8비트 PCM의 묵음은 0x80이다`() {
        val out = requireNotNull(Wav.withLeadingSilence(wav(8000, 8, ByteArray(10)), 100))
        assertTrue(dataOf(out).copyOfRange(0, 800).all { it == 0x80.toByte() })
    }

    @Test
    fun `WAV가 아니면 null`() {
        assertNull(Wav.withLeadingSilence(ByteArray(64), 180))
    }

    @Test
    fun `여러 문장을 이으면 묵음은 맨 앞에만 들어간다`() {
        val a = ByteArray(100) { 1 }
        val b = ByteArray(60) { 2 }
        val out = requireNotNull(Wav.concatWithLeadingSilence(listOf(wav(22050, 16, a), wav(22050, 16, b)), 180))
        val silenceBytes = 22050 * 180 / 1000 * 2
        val data = dataOf(out)
        assertEquals(silenceBytes + a.size + b.size, data.size)
        assertTrue(data.copyOfRange(silenceBytes, data.size).contentEquals(a + b))
    }

    @Test
    fun `표본율이 다른 WAV는 잇지 않는다`() {
        assertNull(Wav.concatWithLeadingSilence(listOf(wav(22050, 16, ByteArray(10)), wav(44100, 16, ByteArray(10))), 180))
    }
}
