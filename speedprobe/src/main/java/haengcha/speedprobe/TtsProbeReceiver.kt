package haengcha.speedprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb로 TTS 시험을 거는 입구.
 * adb shell am broadcast --user 10 -a haengcha.speedprobe.TTS -p haengcha.speedprobe --ei preset 1 --es mode ON_DEVICE
 * 문장은 preset(0부터, TtsProbe.PRESETS)이나 text로, 중지는 --ez stop true.
 */
class TtsProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tts = (context.applicationContext as ProbeApp).tts
        if (intent.getBooleanExtra("stop", false)) {
            tts.stop()
            return
        }
        val text = intent.getStringExtra("text")
            ?: TtsProbe.PRESETS.getOrNull(intent.getIntExtra("preset", 0))
            ?: return
        tts.speak(text, intent.getStringExtra("mode") ?: "ON_DEVICE")
    }
}
