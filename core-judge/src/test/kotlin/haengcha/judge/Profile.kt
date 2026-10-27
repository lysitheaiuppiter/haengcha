package haengcha.judge

import kotlin.math.roundToInt

/** 시험용 차속 프로필. 정해진 주기로 구간을 이어 붙인다. 속도 단위는 km/h. */
class Profile(startKmh: Double, private val hz: Double = 10.0) {

    private val samples = mutableListOf(SpeedSample(0, startKmh / 3.6))
    private var t = 0.0
    private var kmh = startKmh

    fun hold(sec: Double) = ramp(sec, kmh)

    fun ramp(sec: Double, toKmh: Double): Profile {
        val n = (sec * hz).roundToInt()
        val from = kmh
        repeat(n) { i ->
            t += 1 / hz
            kmh = from + (toKmh - from) * (i + 1) / n
            samples += SpeedSample((t * 1e9).toLong(), kmh / 3.6)
        }
        return this
    }

    /** 수신이 끊긴 시간. 표본 없이 시각만 건너뛴다. */
    fun gap(sec: Double): Profile {
        t += sec
        return this
    }

    fun build(): List<SpeedSample> = samples.toList()
}
