package haengcha.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM WAV를 다루는 최소 도구. 안내 음성 앞에 선행 묵음을 붙이고 여러 문장을 이어 붙이는 데 쓴다. */
object Wav {

    class Pcm(val sampleRate: Int, val channels: Int, val bits: Int, val data: ByteArray) {
        val blockAlign get() = channels * bits / 8
        fun sameFormat(other: Pcm) = sampleRate == other.sampleRate && channels == other.channels && bits == other.bits
    }

    /** PCM WAV를 읽는다. PCM이 아니거나 형식이 깨졌으면 null. 부가 청크(LIST 등)는 건너뛴다. */
    fun parse(wav: ByteArray): Pcm? {
        if (wav.size < 12 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val src = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteArray? = null
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = src.getInt(pos + 4)
            val body = pos + 8
            // 스트리밍 합성기는 data 크기를 0이나 최댓값으로 남기기도 해서 파일 끝까지로 본다
            val end = if (size <= 0 || body + size > wav.size) wav.size else body + size
            when (id) {
                "fmt " -> {
                    if (src.getShort(body).toInt() != 1) return null  // PCM만
                    channels = src.getShort(body + 2).toInt()
                    sampleRate = src.getInt(body + 4)
                    bits = src.getShort(body + 14).toInt()
                }
                "data" -> data = wav.copyOfRange(body, end)
            }
            pos = end + (end - body) % 2
        }
        if (channels <= 0 || sampleRate <= 0 || bits <= 0) return null
        return Pcm(sampleRate, channels, bits, data ?: return null)
    }

    /** 표준 44바이트 머리글로 WAV를 만든다. */
    fun build(pcm: Pcm): ByteArray =
        ByteBuffer.allocate(44 + pcm.data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.data.size); put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(pcm.channels.toShort())
            putInt(pcm.sampleRate); putInt(pcm.sampleRate * pcm.blockAlign); putShort(pcm.blockAlign.toShort()); putShort(pcm.bits.toShort())
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.data.size); put(pcm.data)
        }.array()

    /** [wav] 앞에 [silenceMs] 길이의 묵음을 붙인 새 WAV. PCM이 아니면 null. */
    fun withLeadingSilence(wav: ByteArray, silenceMs: Int): ByteArray? = concatWithLeadingSilence(listOf(wav), silenceMs)

    /**
     * 같은 형식의 WAV들을 하나로 잇고 맨 앞에만 [silenceMs] 묵음을 넣는다.
     * 한 번에 이어 말하는 안내에는 문장 사이에 묵음을 더 넣지 않는다(Pleos 선행 묵음 가이드). 형식이 다르면 null.
     */
    fun concatWithLeadingSilence(wavs: List<ByteArray>, silenceMs: Int): ByteArray? {
        val parts = wavs.map { parse(it) ?: return null }
        val first = parts.firstOrNull() ?: return null
        if (parts.any { !it.sameFormat(first) }) return null
        val silence = ByteArray(first.sampleRate * silenceMs / 1000 * first.blockAlign)
        // 8비트 PCM의 무음은 0x80, 16비트 이상은 0
        if (first.bits == 8) silence.fill(0x80.toByte())
        val data = parts.fold(silence) { acc, p -> acc + p.data }
        return build(Pcm(first.sampleRate, first.channels, first.bits, data))
    }
}
