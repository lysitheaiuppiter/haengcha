package haengcha.judge

/**
 * 검약 행차 규칙(기획서 4.1, 임시값). 이번 주행 전비가 [targetKmPerKwh] 이상이면 보너스를 준다.
 * 전비는 Vehicle SDK가 주는 이번 주행 전비를 먼저 쓰고, 차량이 주지 않으면 적산거리와 배터리 잔량(%) 변화로 계산한다.
 * 잔량은 1% 단위라 [minKm] 이상 달리고 잔량이 1% 넘게 줄었을 때만 계산한다.
 * 곳간: [baselineKmPerKwh]로 달렸을 때보다 아낀 전력량을 쌀 섬으로 센다(1kWh = 1섬).
 */
data class EcoRule(
    val targetKmPerKwh: Double = 6.0,
    val baselineKmPerKwh: Double = 5.0,
    val minKm: Double = 3.0,
    val bonusXp: Int = 15,
    /** 배터리 용량(kWh). 차량에서 읽지 못해 Pleos 에뮬레이터 차량 값을 쓴다. */
    val batteryKwh: Double = 77.4,
)

/**
 * 빗길·눈길 차분 운전 규칙(임시값). 와이퍼를 켜고 [minWetSec] 넘게 달리고 급가속·급감속이 없으면 보너스를 준다.
 * 와이퍼를 켠 동안 바깥 기온이 [snowMaxC] 이하였으면 눈길로 본다.
 */
data class WeatherRule(val minWetSec: Double = 120.0, val snowMaxC: Double = 1.0, val bonusXp: Int = 15)

enum class Weather(val label: String) { RAIN("빗길"), SNOW("눈길") }

/** 주행 하나에 덧붙는 차량 신호 판정 재료(Vehicle·NaviHelper SDK, AAOS 바깥 기온). */
data class TripExtras(
    /** 이번 주행 전비(km/kWh). 모르면 null. */
    val kmPerKwh: Double? = null,
    /** 적산거리로 잰 이번 주행 거리(km). 적산거리를 못 읽으면 null. */
    val odoKm: Double? = null,
    /** 와이퍼를 켜고 움직인 시간(초). */
    val wetSec: Double = 0.0,
    val snow: Boolean = false,
    /** 전조등을 켜고 달렸는지(야행). */
    val night: Boolean = false,
    /** 출발할 때 안전띠를 맸는지. 신호를 못 읽으면 null. */
    val belt: Boolean? = null,
    /** 역참에서 쉬고 다시 떠난 주행인지. */
    val rested: Boolean = false,
    /** 이번 주행에서 도착해 스탬프를 찍은 명소. */
    val stamp: Landmark? = null,
)

/** 이번 주행 전비(km/kWh). SDK 값이 있으면 그 값을, 없으면 잔량 변화로 계산한다. 계산할 수 없으면 null. */
fun tripEfficiency(sdkKmPerKwh: Double?, km: Double, socStart: Int?, socEnd: Int?, rule: EcoRule = EcoRule()): Double? {
    if (km < rule.minKm) return null
    sdkKmPerKwh?.takeIf { it > 0 }?.let { return it }
    val used = (socStart ?: return null) - (socEnd ?: return null)
    if (used < 1) return null
    return km / (used / 100.0 * rule.batteryKwh)
}

/** 기준 전비로 달렸을 때보다 아낀 전력량(kWh). 기준보다 못하면 0. */
fun savedKwh(km: Double, kmPerKwh: Double, rule: EcoRule = EcoRule()): Double =
    (km / rule.baselineKmPerKwh - km / kmPerKwh).coerceAtLeast(0.0)

/**
 * 주행하는 동안 차량 신호(배터리 잔량, 적산거리, 전비, 와이퍼, 바깥 기온, 전조등, 안전띠)를 받아 [TripExtras] 재료를 모은다.
 * 신호는 바뀔 때만 오므로 마지막 값을 들고 있다가 차속 표본마다 시간을 더한다.
 * 진행 중인 값은 [snapshot]으로 주행 일지에 남기고, 프로세스가 다시 뜨면 [restore]로 이어 간다.
 */
class ExtrasMeter(
    private val weather: WeatherRule = WeatherRule(),
    private val movingMps: Double = 0.5,
    private val departMps: Double = 1.0,
) {
    // 지금 차량 상태(바뀔 때마다 갱신)
    var soc: Int? = null
    var odometerKm: Double? = null
    var efficiency: Double? = null
    var wiper = false
    var outsideC: Double? = null
    var headlights = false
    var belted: Boolean? = null

    // 이번 주행
    private var socStart: Int? = null
    private var odoStart: Double? = null
    private var prev: SpeedSample? = null
    var wetSec = 0.0
        private set
    var snow = false
        private set
    var night = false
        private set
    var belt: Boolean? = null
        private set
    var departed = false
        private set

    /** 이번 주행에서 적산거리로 잰 거리(km). 못 읽으면 null. */
    val odoKm: Double?
        get() = odoStart?.let { start -> odometerKm?.let { it - start } }?.takeIf { it >= 0 }

    fun begin() {
        socStart = soc
        odoStart = odometerKm
        prev = null
        wetSec = 0.0
        snow = false
        night = false
        belt = null
        departed = false
    }

    /** 이번 주행에서 처음 움직인 표본이면 true(출발). 그때의 안전띠 상태를 남긴다. */
    fun onSpeed(s: SpeedSample): Boolean {
        val p = prev
        if (p != null && s.tNanos <= p.tNanos) return false
        prev = s
        var departing = false
        if (!departed && s.mps >= departMps) {
            departed = true
            belt = belted
            departing = true
        }
        if (p == null) return departing
        val dt = (s.tNanos - p.tNanos) / 1e9
        if (dt > MAX_GAP_SEC || (p.mps + s.mps) / 2 < movingMps) return departing
        if (wiper) {
            wetSec += dt
            if ((outsideC ?: Double.MAX_VALUE) <= weather.snowMaxC) snow = true
        }
        if (headlights) night = true
        return departing
    }

    /** 이번 주행 결과. [distanceKm]는 차속으로 잰 거리로, 적산거리를 못 읽을 때 전비 계산에 쓴다. */
    fun finish(distanceKm: Double, eco: EcoRule = EcoRule()): TripExtras {
        val km = maxOf(odoKm ?: 0.0, distanceKm)
        return TripExtras(tripEfficiency(efficiency, km, socStart, soc, eco), odoKm, wetSec, snow, night, belt)
    }

    fun snapshot(): Map<String, String> = buildMap {
        socStart?.let { put("soc0", it.toString()) }
        odoStart?.let { put("odo0", it.toString()) }
        put("wet", "%.1f".format(java.util.Locale.ROOT, wetSec))
        if (snow) put("snow", "1")
        if (night) put("night", "1")
        if (departed) put("belt", when (belt) { true -> "1"; false -> "0"; null -> "" })
    }

    fun restore(m: Map<String, String>) {
        begin()
        socStart = m["soc0"]?.toIntOrNull()
        odoStart = m["odo0"]?.toDoubleOrNull()
        wetSec = m["wet"]?.toDoubleOrNull() ?: 0.0
        snow = m["snow"] == "1"
        night = m["night"] == "1"
        m["belt"]?.let {
            departed = true
            belt = when (it) { "1" -> true; "0" -> false; else -> null }
        }
    }

    private companion object {
        const val MAX_GAP_SEC = 2.0
    }
}
