package haengcha.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import haengcha.drive.SignalStatus
import haengcha.drive.VehicleListener
import haengcha.judge.Gear
import haengcha.judge.SpeedSample

/**
 * AAOS Car API 구독. 차속은 지원 최대 주기로, 나머지는 바뀔 때만 받는다.
 * 시각은 VHAL 이벤트 타임스탬프(elapsedRealtimeNanos 기준)를 그대로 넘긴다.
 */
class CarVehicleSignals(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var uxRestrictions: CarUxRestrictionsManager? = null
    private var listener: VehicleListener? = null

    var statuses: List<SignalStatus> = emptyList()
        private set

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        // API 34 기기에는 API 35의 propertyStatus가 없어 status를 쓴다
        @Suppress("DEPRECATION")
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
            val l = listener ?: return
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    l.onSpeed(SpeedSample(value.timestamp, (value.value as Float).toDouble()))
                VehiclePropertyIds.GEAR_SELECTION -> l.onGear(gearOf(value.value as Int), value.timestamp)
                VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED -> l.onChargePort(value.value as Boolean)
                VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> l.onOutsideTemperature(value.value as Float)
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "프로퍼티 오류 ${VehiclePropertyIds.toString(propertyId)}")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener(::report)

    private fun report(r: CarUxRestrictions) {
        listener?.onDrivingRestricted(
            r.isRequiresDistractionOptimization,
            r.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0,
        )
    }

    fun start(listener: VehicleListener) {
        if (car != null) return
        this.listener = listener
        car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (ready) subscribe(connected) else unsubscribe()
        }
    }

    fun stop() {
        unsubscribe()
        car?.disconnect()
        car = null
        listener = null
    }

    private fun subscribe(connected: Car) {
        val pm = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        properties = pm
        statuses = WATCHED.map { id -> SignalStatus(VehiclePropertyIds.toString(id), register(pm, id)) }
        // 없어도 되는 신호는 상태 목록에 넣지 않는다
        OPTIONAL.forEach { register(pm, it) }

        val ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        uxRestrictions = ux
        ux.registerListener(uxListener)
        report(ux.currentCarUxRestrictions)
        listener?.onStatusChanged(statuses)
    }

    // API 35의 subscribePropertyEvents는 API 34 기기에 없어 registerCallback을 쓴다
    @Suppress("DEPRECATION")
    private fun register(pm: CarPropertyManager, id: Int): Boolean {
        // 권한이 없으면 SecurityException, 미지원이면 null
        val config = runCatching { pm.getCarPropertyConfig(id) }.getOrNull() ?: return false
        val rate = if (config.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            config.maxSampleRate
        } else {
            CarPropertyManager.SENSOR_RATE_ONCHANGE
        }
        return runCatching { pm.registerCallback(callback, id, rate) }
            .onFailure { Log.w(TAG, "구독 실패 ${VehiclePropertyIds.toString(id)}", it) }
            .getOrDefault(false)
    }

    // registerCallback과 짝인 해제 메서드(API 35에서 unsubscribePropertyEvents로 바뀜)
    @Suppress("DEPRECATION")
    private fun unsubscribe() {
        properties?.unregisterCallback(callback)
        uxRestrictions?.unregisterListener()
        properties = null
        uxRestrictions = null
    }

    private fun gearOf(value: Int): Gear = when (value) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        // D단과 수동 변속 단수(1~9단)는 모두 주행으로 본다
        else -> Gear.DRIVE
    }

    companion object {
        /** 실행 중 사용자 동의가 필요한 권한. 시스템 창에는 '위치 정보 액세스'로 뜬다. */
        val RUNTIME_PERMISSIONS = arrayOf("android.car.permission.CAR_SPEED")

        private const val TAG = "CarVehicleSignals"
        private val WATCHED = listOf(
            VehiclePropertyIds.PERF_VEHICLE_SPEED,
            VehiclePropertyIds.GEAR_SELECTION,
            VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED,
        )
        private val OPTIONAL = listOf(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE)
    }
}
