package haengcha.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 짧은 음성 안내(기획서 9.3, Pleos 오디오 포커스·선행 묵음 가이드).
 * - 문장마다 미리 녹음한 음성(assets/voice, 소개 영상과 같은 목소리, Ogg Opus)을 180ms 묵음 녹음 뒤에 끊김 없이 이어 재생한다
 *   (MediaPlayer.setNextMediaPlayer). 미리 풀어 잇지 않는 것은 에뮬레이터 코덱으로 3초 문장을 푸는 데 1초 넘게 걸려서다.
 *   Pleos 에뮬레이터 음성 엔진은 표준 TextToSpeech API로 보이지 않아 녹음을 쓴다. 녹음이 없는 문장은 쓸 수 있는 TTS 엔진이
 *   있을 때만 WAV 파일로 합성하고 앞에 묵음을 붙여 재생한다. 묵음은 재생을 늦추지 않고 데이터 앞에 둔다(가이드 권장).
 * - 재생 직전에 GAIN_TRANSIENT_MAY_DUCK으로 포커스를 받고 끝나면 반납한다. 거절되면 그 안내는 버린다.
 * - LOSS·LOSS_TRANSIENT(음성 비서 호출 등)면 바로 멈추고 대기 중인 안내까지 버린다. CAN_DUCK이면 소리를 줄인다.
 * - 내비게이션 음성이 나오는 중이면 끝날 때까지 기다린다(최대 10초). 길 안내보다 앞서지 않기 위해서다.
 * 안내는 들어온 순서대로 하나씩 재생한다. 모든 처리는 메인 스레드에서 한다.
 */
class VoiceGuide(private val context: Context) {

    private enum class TtsState { INIT, READY, UNAVAILABLE }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    // NOTIFICATION 컨텍스트: 음악과는 덕킹으로 함께 나오고, 길 안내 컨텍스트보다 앞서지 않는다
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 문장 → assets/voice 안의 녹음 파일 이름. */
    private val recorded: Map<String, String> = runCatching {
        context.assets.open("voice/index.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }
    }.getOrElse { emptyMap() }

    private var tts: TextToSpeech? = null
    private var ttsState = TtsState.INIT
    private val waiting = ArrayDeque<List<String>>()
    private val synthesizing = mutableMapOf<String, File>()
    private val queue = ArrayDeque<Utterance>()
    private var playing: Utterance? = null
    private var players: List<MediaPlayer> = emptyList()
    private var focus: AudioFocusRequest? = null
    private var seq = 0

    fun start() {
        if (tts != null) return
        Log.i(TAG, "녹음 ${recorded.size}개")
        tts = TextToSpeech(context.applicationContext) { result -> handler.post { onInit(result) } }
    }

    /** [lines]를 한 번에 이어 말한다. */
    fun say(lines: List<String>) {
        Log.i(TAG, "안내 요청: ${lines.joinToString(" ")}")
        val clips = lines.map { recorded[it] }
        when {
            clips.all { it != null } -> enqueueRecorded(clips.filterNotNull())
            ttsState == TtsState.READY -> synthesize(lines.joinToString(" "))
            ttsState == TtsState.INIT -> if (waiting.size < MAX_WAITING) waiting += lines
            // TTS를 못 쓰면 녹음이 있는 문장만 말한다
            else -> clips.filterNotNull().takeIf { it.isNotEmpty() }?.let(::enqueueRecorded)
        }
    }

    private fun onInit(result: Int) {
        val engine = tts ?: return
        val language = if (result == TextToSpeech.SUCCESS) engine.setLanguage(Locale.KOREAN) else TextToSpeech.ERROR
        if (language >= TextToSpeech.LANG_AVAILABLE) {
            engine.setOnUtteranceProgressListener(progress)
            ttsState = TtsState.READY
            Log.i(TAG, "TTS 사용 가능 (${engine.defaultEngine})")
        } else {
            ttsState = TtsState.UNAVAILABLE
            Log.i(TAG, "TTS 못 씀(결과 $result, 언어 $language), 녹음만 재생. 보이는 엔진: ${engine.engines.map { it.name }}")
        }
        while (waiting.isNotEmpty()) say(waiting.removeFirst())
    }

    private fun enqueueRecorded(names: List<String>) = enqueue(Utterance.Recorded("voice-${seq++}", names))

    private fun synthesize(text: String) {
        val id = "voice-${seq++}"
        val raw = File(context.cacheDir, "$id-raw.wav")
        synthesizing[id] = raw
        if (tts?.synthesizeToFile(text, Bundle(), raw, id) != TextToSpeech.SUCCESS) {
            synthesizing.remove(id)
            Log.w(TAG, "합성 요청 실패: $text")
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            handler.post { onSynthesized(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            handler.post { synthesizing.remove(utteranceId)?.delete() }
            Log.w(TAG, "합성 실패 $utteranceId")
        }
    }

    private fun onSynthesized(id: String) {
        val raw = synthesizing.remove(id) ?: return
        val padded = runCatching { Wav.withLeadingSilence(raw.readBytes(), PREROLL_MS) }.getOrNull()
        raw.delete()
        if (padded == null) {
            Log.w(TAG, "합성 결과가 PCM WAV가 아니라 재생하지 않음")
            return
        }
        enqueue(padded, id)
    }

    private fun enqueue(wav: ByteArray, id: String) = enqueue(Utterance.Synthesized(id, File(context.cacheDir, "$id.wav").apply { writeBytes(wav) }))

    private fun enqueue(u: Utterance) {
        queue += u
        playNext(SystemClock.elapsedRealtime() + NAVIGATION_WAIT_MS)
    }

    /** 재생할 안내 하나: 녹음 문장들(앞에 묵음 녹음) 또는 묵음을 붙여 합성한 WAV 파일. */
    private sealed interface Utterance {
        val id: String

        data class Recorded(override val id: String, val names: List<String>) : Utterance
        data class Synthesized(override val id: String, val file: File) : Utterance
    }

    /** 안내 하나를 이어 재생할 플레이어들. 준비하지 못하면 예외. */
    private fun prepare(u: Utterance): List<MediaPlayer> {
        val made = mutableListOf<MediaPlayer>()
        try {
            val sources: List<(MediaPlayer) -> Unit> = when (u) {
                is Utterance.Recorded -> (listOf(PREROLL) + u.names).map { name ->
                    { mp: MediaPlayer -> context.assets.openFd("voice/$name").use { mp.setDataSource(it) } }
                }
                is Utterance.Synthesized -> listOf { mp: MediaPlayer -> mp.setDataSource(u.file.path) }
            }
            sources.forEach { source ->
                made += MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    source(this)
                    setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "재생 오류 $what/$extra")
                        finish()
                        true
                    }
                    prepare()
                }
            }
            made.zipWithNext { a, b -> a.setNextMediaPlayer(b) }
            made.last().setOnCompletionListener {
                Log.i(TAG, "재생 끝 ${u.id}")
                finish()
            }
            return made
        } catch (e: Exception) {
            made.forEach { it.release() }
            throw e
        }
    }

    private fun playNext(deadline: Long) {
        if (playing != null) return
        val clip = queue.firstOrNull() ?: return
        if (navigationSpeaking() && SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed({ playNext(deadline) }, 500)
            return
        }
        queue.removeFirst()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "포커스 요청 usage=NOTIFICATION gain=TRANSIENT_MAY_DUCK 결과=${if (granted) "grant" else "reject"}")
        if (!granted) {
            discard(clip)
            playNext(deadline)
            return
        }
        focus = request
        playing = clip
        players = runCatching { prepare(clip) }.onFailure { Log.w(TAG, "재생 실패", it) }.getOrDefault(emptyList())
        if (players.isEmpty()) {
            finish()
        } else {
            players.first().start()
            // 이어 재생이 끊겨 끝 알림이 오지 않아도 다음 안내가 막히지 않게 한다
            handler.postDelayed(watchdog, players.sumOf { it.duration.coerceAtLeast(0) } + WATCHDOG_SLACK_MS)
            Log.i(TAG, "재생 시작 ${clip.id}")
        }
    }

    private fun discard(u: Utterance) {
        if (u is Utterance.Synthesized) u.file.delete()
    }

    private val watchdog = Runnable {
        Log.w(TAG, "재생 끝 알림이 없어 마감")
        finish()
    }

    private fun finish() {
        val done = playing ?: return
        handler.removeCallbacks(watchdog)
        players.forEach { it.release() }
        players = emptyList()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        discard(done)
        playing = null
        playNext(SystemClock.elapsedRealtime() + NAVIGATION_WAIT_MS)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "포커스 변경 $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                queue.forEach(::discard)
                queue.clear()
                finish()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> players.forEach { it.setVolume(DUCK_VOLUME, DUCK_VOLUME) }
            AudioManager.AUDIOFOCUS_GAIN -> players.forEach { it.setVolume(1f, 1f) }
        }
    }

    private fun navigationSpeaking() = audio.activePlaybackConfigurations.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private companion object {
        const val TAG = "VoiceGuide"
        /** Pleos 권장 150~200ms. */
        const val PREROLL_MS = 180
        /** 180ms 묵음 녹음(합성 WAV에는 PREROLL_MS만큼 묵음을 직접 붙인다). */
        const val PREROLL = "_preroll.ogg"
        const val NAVIGATION_WAIT_MS = 10_000L
        const val DUCK_VOLUME = 0.3f
        const val MAX_WAITING = 5
        const val WATCHDOG_SLACK_MS = 3_000L
    }
}
