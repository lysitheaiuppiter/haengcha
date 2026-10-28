package haengcha.speedprobe

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Pleos TextToSpeech SDK가 에뮬레이터에서 실제로 합성·재생되는지 확인한다.
 * 모드는 생성자에서 정해지므로 모드가 바뀌면 객체를 새로 만든다. 결과는 logcat(TtsProbe)에 남긴다.
 * 요청부터 재생 시작(onStart)까지와 재생 끝(onDone)까지의 시간, 재생 중 RMS 최댓값을 함께 적는다.
 */
class TtsProbe(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var mode: Mode? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private var requestedAt = 0L
    private var rmsCount = 0
    private var rmsMax = 0.0

    private val listener = object : EventListener {
        override fun onReady() = log("onReady")
        override fun onStart() = log("onStart (요청 뒤 ${since()}ms)")
        override fun onUpdatedRms(rms: Double) {
            rmsCount++
            if (rms > rmsMax) rmsMax = rms
        }
        override fun onDone() = log("onDone (요청 뒤 ${since()}ms, RMS ${rmsCount}번, 최대 ${"%.2f".format(rmsMax)})")
        override fun onStop() = log("onStop (요청 뒤 ${since()}ms)")
        override fun onError(errorType: ErrorType) = log("onError $errorType (요청 뒤 ${since()}ms)")
    }

    /** text를 modeName(HYBRID, SERVER, ON_DEVICE) 모드로 읽는다. 초기화가 끝나기 전 요청은 모아 두었다가 읽는다. */
    fun speak(text: String, modeName: String) = handler.post {
        val m = runCatching { Mode.valueOf(modeName) }.getOrElse {
            log("모르는 모드 $modeName (가능: ${Mode.values().joinToString()})")
            return@post
        }
        if (tts == null || mode != m) open(m)
        if (ready) say(text) else pending.addLast(text)
    }

    fun stop() = handler.post { runCatching { tts?.stop() }.onFailure { log("stop 실패 $it") } }

    private fun open(m: Mode) {
        close()
        mode = m
        val start = SystemClock.elapsedRealtime()
        val t = runCatching { TextToSpeech(context.applicationContext, m) }.getOrElse {
            log("생성 실패 ${it.javaClass.name}: ${it.message}")
            return
        }
        tts = t
        log("생성 mode=$m, 초기화 시작")
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post { initialized(t, start) }
                }
                override fun onFailure(errorType: ErrorType) = log("초기화 실패 $errorType (${SystemClock.elapsedRealtime() - start}ms)")
            })
        }.onFailure { log("초기화 호출 실패 ${it.javaClass.name}: ${it.message}") }
    }

    private fun initialized(t: TextToSpeech, start: Long) {
        if (t !== tts) return
        log("초기화 성공 (${SystemClock.elapsedRealtime() - start}ms)")
        t.addEventListener(listener)
        runCatching { t.setLocale(Locale.KOREA) }.onFailure { log("setLocale 실패 $it") }
        ready = true
        while (pending.isNotEmpty()) say(pending.removeFirst())
    }

    private fun say(text: String) {
        requestedAt = SystemClock.elapsedRealtime()
        rmsCount = 0
        rmsMax = 0.0
        log("speak \"$text\"")
        runCatching { tts?.speak(text) }.onFailure { log("speak 실패 ${it.javaClass.name}: ${it.message}") }
    }

    private fun close() {
        tts?.let { t ->
            runCatching { t.removeEventListener(listener) }
            runCatching { t.release() }
        }
        tts = null
        ready = false
        pending.clear()
    }

    private fun since() = SystemClock.elapsedRealtime() - requestedAt

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    companion object {
        const val TAG = "TtsProbe"

        /** adb로 한글을 넘기기 어려워 번호로 고르는 시험 문장. 행차 안내 문장과 긴 문장 하나 */
        val PRESETS = listOf(
            "주상전하 납시오!",
            "어린이보호구역입니다. 천천히!",
            "암행어사 출두요! 마패를 드립니다.",
            "울산에 오셨습니다. 고래 테마가 켜집니다.",
            "옛길 원정, 문경새재 조령관에 이르렀습니다. 영남대로의 절반을 넘었습니다. 오늘도 안전하게, 행차하셨습니다.",
        )
    }
}
