package haengcha.app.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import haengcha.drive.Voice
import haengcha.judge.Announcements
import java.util.Locale

/**
 * 짧은 음성 안내(기획서 9.3, Pleos 오디오 포커스 가이드).
 * - 안내는 Pleos TextToSpeech SDK의 ON_DEVICE 모드로 합성한다. 차량 Speech Service가 합성·재생하고, 네트워크와 앱 등록이
 *   필요 없다. 읽기 어려운 표기(Lv.3, 1+ 한우 등)는 [Spoken]으로 고쳐 넘긴다.
 * - 탑승 외침(주상전하 납시오!)은 팀원 목소리로 만든 녹음(assets/voice/boarding.ogg, 5.8초)을 늘 그대로 재생한다.
 * - SDK를 쓸 수 없거나 합성이 실패하면 안전 안내(어린이보호구역, 안전띠, 휴식)만 예비 녹음(assets/voice, Ogg Opus)으로
 *   180ms 묵음 녹음 뒤에 이어 재생한다(MediaPlayer.setNextMediaPlayer). 나머지 안내는 화면으로만 보인다.
 * - Speech Service는 재생할 때 오디오 포커스를 잡지 않아(에뮬레이터 확인) 재생 직전에 앱이 GAIN_TRANSIENT_MAY_DUCK으로
 *   받고 끝나면 반납한다. 거절되면 반납하고 한 번 더 청하며, 그래도 안 되면 그 안내는 버린다.
 * - LOSS·LOSS_TRANSIENT(음성 비서 호출 등)면 바로 멈추고 대기 중인 안내까지 버린다. CAN_DUCK이면 녹음 소리를 줄인다.
 * - 내비게이션 음성이 나오는 중이면 끝날 때까지 기다린다(최대 10초). 길 안내보다 앞서지 않기 위해서다.
 * 안내는 들어온 순서대로 하나씩 재생하고, 끝나거나 버리면 요청할 때 받은 onDone을 한 번 부른다(축하 연출을 넘기는 시점).
 * SDK 이벤트에는 요청 구분이 없어, 재생이 시작(onStart)된 뒤의 끝 알림만 지금 안내의 끝으로 본다. 모든 처리는 메인 스레드에서 한다.
 */
class VoiceGuide(private val context: Context) : Voice {

    private enum class TtsState { INIT, READY, UNAVAILABLE }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    // NOTIFICATION 컨텍스트: 음악과는 덕킹으로 함께 나오고, 길 안내 컨텍스트보다 앞서지 않는다
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 문장 → assets/voice 안의 예비 녹음 파일 이름. */
    private val recorded: Map<String, String> = runCatching {
        context.assets.open("voice/index.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }
    }.getOrElse { emptyMap() }

    private var tts: TextToSpeech? = null
    private var ttsState = TtsState.INIT
    private val waiting = ArrayDeque<Pair<List<String>, () -> Unit>>()
    private val queue = ArrayDeque<Utterance>()
    private var playing: Utterance? = null
    private var players: List<MediaPlayer> = emptyList()
    private var focus: AudioFocusRequest? = null
    private var seq = 0
    /** 지금 합성 안내의 재생이 시작됐는지. 앞 안내가 늦게 보낸 끝 알림을 걸러 낸다. */
    private var started = false
    /** 끊으라고 했고 SDK의 중지 알림을 기다리는 중. */
    private var stopping = false

    fun start() {
        if (tts != null || ttsState != TtsState.INIT) return
        Log.i(TAG, "예비 녹음 ${recorded.size}개")
        val engine = runCatching { TextToSpeech(context.applicationContext, Mode.ON_DEVICE) }
            .getOrElse { unavailable("생성 실패 ${it.javaClass.simpleName}"); return }
        tts = engine
        runCatching {
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post { onReady(engine) }
                }

                override fun onFailure(errorType: ErrorType) {
                    handler.post { if (engine === tts) unavailable("초기화 실패 $errorType") }
                }
            })
        }.onFailure { unavailable("초기화 호출 실패 ${it.javaClass.simpleName}") }
    }

    /** [lines]를 한 번에 이어 말한다. */
    override fun say(lines: List<String>, onDone: () -> Unit) {
        Log.i(TAG, "안내 요청: ${lines.joinToString(" ")}")
        if (lines.isNotEmpty() && lines.all { it in SIGNATURE && it in recorded }) {
            fallback("voice-${seq++}", lines, onDone)?.let(::enqueue)
            return
        }
        when (ttsState) {
            TtsState.READY -> enqueue(Utterance.Spoken("voice-${seq++}", lines, onDone))
            TtsState.INIT -> if (waiting.size < MAX_WAITING) waiting += lines to onDone else handler.post(onDone)
            TtsState.UNAVAILABLE -> fallback("voice-${seq++}", lines, onDone)?.let(::enqueue) ?: handler.post(onDone)
        }
    }

    /** 기다리는 안내를 버리고 지금 말하는 안내도 끊는다. */
    override fun hush() {
        while (waiting.isNotEmpty()) handler.post(waiting.removeFirst().second)
        queue.forEach(::notifyDone)
        queue.clear()
        interrupt()
    }

    private fun onReady(engine: TextToSpeech) {
        if (engine !== tts) return
        engine.addEventListener(events)
        runCatching { engine.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "로케일 설정 실패", it) }
        ttsState = TtsState.READY
        Log.i(TAG, "Pleos TTS 사용 가능(ON_DEVICE)")
        flushWaiting()
    }

    private fun unavailable(why: String) {
        Log.w(TAG, "Pleos TTS 못 씀($why). 안전 안내만 예비 녹음으로 재생")
        tts?.let { runCatching { it.release() } }
        tts = null
        ttsState = TtsState.UNAVAILABLE
        flushWaiting()
    }

    private fun flushWaiting() {
        while (waiting.isNotEmpty()) waiting.removeFirst().let { (lines, onDone) -> say(lines, onDone) }
    }

    /** 녹음이 있는 문장만 모은 예비 안내. 하나도 없으면 null. */
    private fun fallback(id: String, lines: List<String>, onDone: () -> Unit): Utterance.Recorded? =
        lines.mapNotNull { recorded[it] }.takeIf { it.isNotEmpty() }?.let { Utterance.Recorded(id, lines, it, onDone) }

    private fun enqueue(u: Utterance) {
        queue += u
        playNext(SystemClock.elapsedRealtime() + NAVIGATION_WAIT_MS)
    }

    /** 재생할 안내 하나: SDK로 합성할 문장들 또는 예비 녹음 파일들(앞에 묵음 녹음). */
    private sealed class Utterance(val id: String, val lines: List<String>, val onDone: () -> Unit) {
        var notified = false

        class Spoken(id: String, lines: List<String>, onDone: () -> Unit) : Utterance(id, lines, onDone)
        class Recorded(id: String, lines: List<String>, val names: List<String>, onDone: () -> Unit) : Utterance(id, lines, onDone)
    }

    private val events = object : EventListener {
        override fun onReady() = Unit

        override fun onStart() {
            handler.post { if (playing is Utterance.Spoken && !stopping) started = true }
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            handler.post { spokenEnded("끝") }
        }

        override fun onStop() {
            handler.post { spokenEnded("중지") }
        }

        override fun onError(errorType: ErrorType) {
            handler.post { spokenFailed(errorType) }
        }
    }

    private fun spokenEnded(how: String) {
        val u = playing as? Utterance.Spoken ?: return
        if (!stopping && !started) return
        Log.i(TAG, "재생 $how ${u.id}")
        finish()
    }

    /** 합성이 실패하면 그 안내에 예비 녹음이 있을 때만 녹음으로 바꿔 재생한다. */
    private fun spokenFailed(error: ErrorType) {
        val u = playing as? Utterance.Spoken ?: return
        if (stopping) return finish()
        Log.w(TAG, "합성 실패 ${u.id} $error")
        val backup = fallback(u.id, u.lines, u.onDone)
        if (backup == null) return finish()
        handler.removeCallbacks(watchdog)
        playing = backup
        startRecorded(backup)
    }

    /** 예비 녹음 하나를 이어 재생할 플레이어들. 준비하지 못하면 예외. */
    private fun prepare(u: Utterance.Recorded): List<MediaPlayer> {
        val made = mutableListOf<MediaPlayer>()
        try {
            (listOf(PREROLL) + u.names).forEach { name ->
                made += MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    context.assets.openFd("voice/$name").use { setDataSource(it) }
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

    private fun playNext(deadline: Long, retried: Boolean = false) {
        if (playing != null) return
        val next = queue.firstOrNull() ?: return
        if (navigationSpeaking() && SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed({ playNext(deadline, retried) }, 500)
            return
        }

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "포커스 요청 usage=NOTIFICATION gain=TRANSIENT_MAY_DUCK 결과=${if (granted) "grant" else "reject"}")
        if (!granted) {
            // 차량 포커스 정책이 늦게 허용해 포커스가 남는 일이 있어(에뮬레이터 확인) 거절돼도 반납한다. 한 번은 잠시 뒤 다시 청한다
            audio.abandonAudioFocusRequest(request)
            if (!retried) {
                handler.postDelayed({ playNext(deadline, retried = true) }, FOCUS_RETRY_MS)
                return
            }
            queue.removeFirst()
            notifyDone(next)
            playNext(deadline)
            return
        }
        queue.removeFirst()
        focus = request
        playing = next
        when (next) {
            is Utterance.Spoken -> startSpoken(next)
            is Utterance.Recorded -> startRecorded(next)
        }
    }

    private fun startSpoken(u: Utterance.Spoken) {
        val text = Spoken.of(u.lines.joinToString(" "))
        started = false
        val ok = runCatching { tts?.speak(text) }.onFailure { Log.w(TAG, "합성 요청 실패", it) }.isSuccess && tts != null
        if (!ok) return finish()
        // 끝 알림이 오지 않아도 다음 안내가 막히지 않게 한다(한국어 합성은 글자당 0.15초 안팎)
        handler.postDelayed(watchdog, SPOKEN_BASE_MS + text.length * SPOKEN_PER_CHAR_MS)
        Log.i(TAG, "재생 시작 ${u.id}")
    }

    private fun startRecorded(u: Utterance.Recorded) {
        players = runCatching { prepare(u) }.onFailure { Log.w(TAG, "재생 실패", it) }.getOrDefault(emptyList())
        if (players.isEmpty()) return finish()
        players.first().start()
        handler.postDelayed(watchdog, players.sumOf { it.duration.coerceAtLeast(0) } + WATCHDOG_SLACK_MS)
        Log.i(TAG, "재생 시작 ${u.id} (예비 녹음)")
    }

    /** 지금 안내를 끊는다. 합성 안내는 SDK 중지 알림이 오거나 잠시 뒤에 치운다(그사이 다음 안내가 겹치지 않게). */
    private fun interrupt() {
        val u = playing ?: return
        if (u is Utterance.Spoken && tts != null) {
            notifyDone(u)
            if (stopping) return
            stopping = true
            handler.removeCallbacks(watchdog)
            runCatching { tts?.stop() }
            handler.postDelayed(watchdog, STOP_WAIT_MS)
        } else {
            finish()
        }
    }

    /** 요청한 쪽에 끝났다고 메인 스레드 다음 차례에 한 번만 알린다. */
    private fun notifyDone(u: Utterance) {
        if (u.notified) return
        u.notified = true
        handler.post(u.onDone)
    }

    private val watchdog = Runnable {
        if (!stopping) Log.w(TAG, "재생 끝 알림이 없어 마감")
        finish()
    }

    private fun finish() {
        val done = playing ?: return
        handler.removeCallbacks(watchdog)
        players.forEach { it.release() }
        players = emptyList()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        notifyDone(done)
        playing = null
        started = false
        stopping = false
        playNext(SystemClock.elapsedRealtime() + NAVIGATION_WAIT_MS)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "포커스 변경 $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                queue.forEach(::notifyDone)
                queue.clear()
                interrupt()
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
        /** 180ms 묵음 녹음(Pleos 권장 150~200ms). 예비 녹음 앞에 재생한다. */
        const val PREROLL = "_preroll.ogg"
        const val NAVIGATION_WAIT_MS = 10_000L
        const val DUCK_VOLUME = 0.3f
        const val MAX_WAITING = 5
        const val WATCHDOG_SLACK_MS = 3_000L
        const val SPOKEN_BASE_MS = 3_000L
        const val SPOKEN_PER_CHAR_MS = 250L
        const val STOP_WAIT_MS = 1_000L
        const val FOCUS_RETRY_MS = 600L
        /** TTS 대신 늘 녹음으로 재생하는 연출 문장. */
        val SIGNATURE = setOf(Announcements.BOARDING)
    }
}
