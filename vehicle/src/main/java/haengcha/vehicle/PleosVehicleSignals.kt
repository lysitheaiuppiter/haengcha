package haengcha.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Pleos Vehicle SDK 구독. 배터리 잔량·전비·적산거리(검약 행차, 옛길 원정), 경고등·엔진오일(대장간 퀘스트),
 * 안전띠(안전띠 개근), 전조등(야행), 와이퍼(날씨 장면)를 받아 [VehicleListener]로 넘긴다.
 * 항목마다 처음 읽기를 해 보고 실패하면(차량 미지원·권한 없음) 그 항목은 없는 것으로 둔다.
 * SDK 콜백 스레드가 정해져 있지 않아 모두 메인 스레드로 옮긴다.
 *
 * 안전띠: 경고등을 읽을 수 있으면 운전석 안전띠 경고등이 꺼져 있을 때 맨 것으로 보고, 못 읽으면 운전석 체결 신호를 쓴다.
 * 에뮬레이터는 체결 신호가 늘 '안 맴'이고 Virtual Console로는 경고등만 바꿀 수 있어서다.
 */
class PleosVehicleSignals(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var listener: VehicleListener? = null
    private val available = linkedMapOf<String, Boolean>()
    private var telltalesReadable = false

    fun start(listener: VehicleListener) {
        if (vehicle != null) return
        this.listener = listener
        val v = Vehicle(context)
        val ok = runCatching { v.initialize() }
            .onFailure { Log.w(TAG, "Vehicle SDK 초기화 실패", it) }
            .isSuccess
        if (!ok) {
            ITEMS.forEach { mark(it, false) }
            return
        }
        vehicle = v
        subscribe(v)
    }

    fun stop() {
        runCatching { vehicle?.release() }
        vehicle = null
        listener = null
    }

    private fun post(block: VehicleListener.() -> Unit) {
        handler.post { listener?.block() }
    }

    private fun mark(item: String, ok: Boolean) = handler.post {
        if (available[item] == ok) return@post
        available[item] = ok
        listener?.onSdkStatus(available.map { (k, v) -> SignalStatus(k, v) })
    }

    private fun failed(item: String): (Exception) -> Unit = { e ->
        Log.i(TAG, "$item 읽지 못함: ${e.message}")
        mark(item, false)
    }

    private fun subscribe(v: Vehicle) {
        val ev = v.getEvBattery()
        ev.getEvBatteryStateOfCharge({ soc -> mark(SOC, true); soc?.let { post { onBatterySoc(it) } } }, failed(SOC))
        ev.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) { value?.let { post { onBatterySoc(it) } } }
            override fun onFailed(e: Exception) = failed(SOC)(e)
        })
        ev.getCurrentEvEfficiency({ eff ->
            mark(EFFICIENCY, true)
            eff?.let { post { onEvEfficiency(it) } }
            ev.registerCurrentEvEfficiency(object : EvBatteryEfficiencyListener {
                override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) { efficiency?.let { post { onEvEfficiency(it) } } }
                override fun onFailed(e: Exception) = failed(EFFICIENCY)(e)
            })
        }, failed(EFFICIENCY))

        val odo = v.getOdometer()
        odo.getCurrentDistanceDriven({ km -> mark(ODOMETER, true); km?.let { post { onOdometer(it) } } }, failed(ODOMETER))
        odo.registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) { value?.let { post { onOdometer(it) } } }
            override fun onFailed(e: Exception) = failed(ODOMETER)(e)
        })

        val safety = v.getSafety()
        safety.getTelltalesInfo({ t ->
            mark(TELLTALES, true)
            telltalesReadable = true
            onTelltales(t)
        }, failed(TELLTALES))
        safety.registerTelltales(object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) = onTelltales(telltales)
            override fun onFailed(e: Exception) = failed(TELLTALES)(e)
        })
        safety.getEngineOilLevelState({ level -> mark(OIL, true); post { onEngineOilLow(level.isLow()) } }, failed(OIL))
        safety.registerEngineOilLevelState(object : OilLevelStateListener {
            override fun onOilLevelUpdated(level: OilLevelState?) = post { onEngineOilLow(level.isLow()) }
            override fun onFailed(e: Exception) = failed(OIL)(e)
        })

        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        val seat = v.getSeat()
        seat.getCurrentSeatBeltBuckledState(driver, { b -> mark(BELT, true); onBuckled(b) }, failed(BELT))
        seat.registerSeatBeltBuckledState(driver, object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = onBuckled(state)
            override fun onFailed(e: Exception) = failed(BELT)(e)
        })

        val light = v.getLight()
        light.getCurrentLowBeamLightState({ s -> mark(HEADLIGHTS, true); post { onHeadlights(s == LightState.ON) } }, failed(HEADLIGHTS))
        light.registerLowBeamLightState(object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = post { onHeadlights(state == LightState.ON) }
            override fun onFailed(e: Exception) = failed(HEADLIGHTS)(e)
        })

        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = v.getWiper()
        wiper.getCurrentWiperState(front, { s -> mark(WIPER, true); post { onWiper(s == WiperState.ON) } }, failed(WIPER))
        wiper.registerWiperState(front, object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = post { onWiper(state == WiperState.ON) }
            override fun onFailed(e: Exception) = failed(WIPER)(e)
        })
    }

    private fun onTelltales(t: Telltales?) {
        val active = t?.telltales.orEmpty()
            .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
            .mapNotNull { it.type?.name }
            .toSet()
        post {
            onTelltales(active)
            onSeatBelt(SEAT_BELT_TELLTALE !in active)
        }
    }

    private fun onBuckled(buckled: Boolean?) {
        if (buckled == null || telltalesReadable) return
        post { onSeatBelt(buckled) }
    }

    private fun OilLevelState?.isLow() = this == OilLevelState.LOW || this == OilLevelState.CRITICALLY_LOW

    companion object {
        private const val TAG = "PleosVehicleSignals"
        private const val SEAT_BELT_TELLTALE = "SEAT_BELT"
        const val SOC = "배터리 잔량"
        const val EFFICIENCY = "전비"
        const val ODOMETER = "적산거리"
        const val TELLTALES = "경고등"
        const val OIL = "엔진오일"
        const val BELT = "안전띠"
        const val HEADLIGHTS = "전조등"
        const val WIPER = "와이퍼"
        private val ITEMS = listOf(SOC, EFFICIENCY, ODOMETER, TELLTALES, OIL, BELT, HEADLIGHTS, WIPER)
    }
}
