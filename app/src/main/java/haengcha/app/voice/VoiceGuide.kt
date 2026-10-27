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
 * - 문장마다 미리 녹음한 음성(assets/voice, 소개 영상과 같은 목소리)을 찾아 이어 붙이고 앞에 180ms 묵음을 넣어 재생한다.
 *   Pleos 에뮬레이터 음성 엔진은 표준 TextToSpeech API로 보이지 않아서다. 녹음이 없는 문장은 쓸 수 있는 TTS 엔진이 있을 때만
 *   파일로 합성해 같은 방식으로 재생한다. 묵음은 재생을 늦추지 않고 데이터 앞에 붙인다(가이드 권장).
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
    private val queue = ArrayDeque<File>()
    private var playing: File? = null
    private var player: MediaPlayer? = null
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

    private fun enqueueRecorded(names: List<String>) {
        val padded = runCatching {
            Wav.concatWithLeadingSilence(names.map { context.assets.open("voice/$it").use { s -> s.readBytes() } }, PREROLL_MS)
        }.getOrNull()
        if (padded == null) {
            Log.w(TAG, "녹음을 잇지 못함: $names")
            return
        }
        enqueue(padded, "voice-${seq++}")
    }

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

    private fun enqueue(wav: ByteArray, id: String) {
        queue += File(context.cacheDir, "$id.wav").apply { writeBytes(wav) }
        playNext(SystemClock.elapsedRealtime() + NAVIGATION_WAIT_MS)
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
            clip.delete()
            playNext(deadline)
            return
        }
        focus = request
        playing = clip
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(clip.path)
                setOnCompletionListener {
                    Log.i(TAG, "재생 끝 ${clip.name}")
                    finish()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "재생 오류 $what/$extra")
                    finish()
                    true
                }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "재생 실패", it) }.getOrNull()
        if (player == null) finish() else Log.i(TAG, "재생 시작 ${clip.name}")
    }

    private fun finish() {
        player?.release()
        player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        playing?.delete()
        playing = null
        playNext(SystemClock.elapsedRealtime() + NAVIGATION_WAIT_MS)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "포커스 변경 $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                queue.forEach { it.delete() }
                queue.clear()
                finish()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(DUCK_VOLUME, DUCK_VOLUME)
            AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
        }
    }

    private fun navigationSpeaking() = audio.activePlaybackConfigurations.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private companion object {
        const val TAG = "VoiceGuide"
        /** Pleos 권장 150~200ms. */
        const val PREROLL_MS = 180
        const val NAVIGATION_WAIT_MS = 10_000L
        const val DUCK_VOLUME = 0.3f
        const val MAX_WAITING = 5
    }
}
