package haengcha.speedprobe

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 행차 2차 기능(검약·대장간·역참·옛길·야행·날씨·안전띠·도착·안내 타이밍)에 쓸 값이
 * 에뮬레이터에서 실제로 나오는지 Pleos Vehicle·NaviHelper SDK와 AAOS 프로퍼티로 읽어 logcat(PleosProbe)에 남긴다.
 * 값은 Virtual Console로 바꿔 가며 확인한다.
 */
class PleosProbe(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    val lines = linkedMapOf<String, String>()

    fun start() {
        if (vehicle != null) return
        startVehicle()
        startNavi()
        startAaos()
    }

    private fun put(key: String, value: Any?) {
        val text = value?.toString() ?: "null"
        handler.post { lines[key] = text }
        Log.i(TAG, "$key = $text")
    }

    private fun fail(key: String, e: Throwable) {
        put(key, "실패 ${e.javaClass.name}: ${e.message}")
    }

    private fun startVehicle() {
        val v = Vehicle(context)
        vehicle = v
        runCatching { v.initialize() }.onFailure { fail("vehicle.init", it); return }
        put("vehicle.init", "ok")

        // 검약 행차: 전비·연비·드라이브 모드
        val ev = v.getEvBattery()
        ev.getCurrentEvEfficiency({ put("ev.current", it) }, { fail("ev.current", it) })
        ev.getInstantaneousEvEfficiency({ put("ev.instant", it) }, { fail("ev.instant", it) })
        ev.getEvBatteryStateOfCharge({ put("ev.soc", it) }, { fail("ev.soc", it) })
        ev.registerCurrentEvEfficiency(object : EvBatteryEfficiencyListener {
            override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) = put("ev.current~", efficiency)
            override fun onFailed(e: Exception) = fail("ev.current~", e)
        })
        ev.registerInstantaneousEvEfficiency(object : EvBatteryEfficiencyListener {
            override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) = put("ev.instant~", efficiency)
            override fun onFailed(e: Exception) = fail("ev.instant~", e)
        })
        ev.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) = put("ev.soc~", value)
            override fun onFailed(e: Exception) = fail("ev.soc~", e)
        })
        val fuel = v.getFuel()
        fuel.getCurrentFuelEconomy({ put("fuel.current", it) }, { fail("fuel.current", it) })
        fuel.getFuelPercent({ put("fuel.percent", it) }, { fail("fuel.percent", it) })
        val mode = v.getDrivingMode()
        mode.getCurrentDrivingModeState({ put("drivemode", it) }, { fail("drivemode", it) })
        mode.registerDrivingModeState(object : DrivingModeStateListener {
            override fun onDrivingModeStateUpdated(state: DrivingModeType?) = put("drivemode~", state)
            override fun onFailed(e: Exception) = fail("drivemode~", e)
        })

        // 옛길 원정·역참: 누적 주행 거리, 이번 주행 시간·거리
        val odo = v.getOdometer()
        odo.getCurrentDistanceDriven({ put("odo.km", it) }, { fail("odo.km", it) })
        odo.registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = put("odo.km~", value)
            override fun onFailed(e: Exception) = fail("odo.km~", e)
        })
        odo.getTripCurrentDrivingTime({ put("trip.min", it) }, { fail("trip.min", it) })
        odo.registerTripCurrentDrivingTime(object : DrivingTimeListener {
            override fun onDrivingTimeUpdated(value: Int?) = put("trip.min~", value)
            override fun onFailed(e: Exception) = fail("trip.min~", e)
        })
        odo.getTripCurrentDrivingDistance({ put("trip.m", it) }, { fail("trip.m", it) })
        odo.registerTripCurrentDrivingDistance(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = put("trip.m~", value)
            override fun onFailed(e: Exception) = fail("trip.m~", e)
        })
        v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = put("brake~", status)
            override fun onFailed(e: Exception) = fail("brake~", e)
        })

        // 대장간 퀘스트: 오일·브레이크액·워셔액·타이어·경고등
        val safety = v.getSafety()
        safety.getEngineOilLevelState({ put("oil", it) }, { fail("oil", it) })
        safety.registerEngineOilLevelState(object : OilLevelStateListener {
            override fun onOilLevelUpdated(level: OilLevelState?) = put("oil~", level)
            override fun onFailed(e: Exception) = fail("oil~", e)
        })
        safety.getBrakeFluidLevelState({ put("brakefluid", it) }, { fail("brakefluid", it) })
        safety.hasLowWasherFluidWarning({ put("washer", it) }, { fail("washer", it) })
        safety.registerLowWasherFluidWaringState(object : LowWasherFluidWaringListener {
            override fun onLowWasherFluidWaringUpdated(state: Boolean?) = put("washer~", state)
            override fun onFailed(e: Exception) = fail("washer~", e)
        })
        safety.getTelltalesInfo({ put("telltales", it) }, { fail("telltales", it) })
        safety.registerTelltales(object : ai.pleos.playground.vehicle.listener.TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: ai.pleos.playground.vehicle.api.model.telltale.Telltales?) =
                put("telltales~", telltales?.telltales?.filter { it.state != ai.pleos.playground.vehicle.constant.telltale.TelltaleState.OFF })
            override fun onFailed(e: Exception) = fail("telltales~", e)
        })
        v.getTire().getLowTirePressure(WheelArea(WheelValue.LEFT_FRONT), { put("tire.lowKpa", it) }, { fail("tire.lowKpa", it) })

        // 안전띠 개근
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        safety.hasSeatBeltNotBuckledWarning(driver, { put("belt.warning", it) }, { fail("belt.warning", it) })
        safety.registerSeatBeltNotBuckledWarning(driver, object : SeatBeltNotBuckledWarningListener {
            override fun onSeatBeltNotBuckledWarningUpdated(state: Boolean?) = put("belt.warning~", state)
            override fun onFailed(e: Exception) = fail("belt.warning~", e)
        })
        val seat = v.getSeat()
        seat.getCurrentSeatBeltBuckledState(driver, { put("belt.buckled", it) }, { fail("belt.buckled", it) })
        seat.registerSeatBeltBuckledState(driver, object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = put("belt.buckled~", state)
            override fun onFailed(e: Exception) = fail("belt.buckled~", e)
        })

        // 야행: 전조등·미등
        val light = v.getLight()
        light.getCurrentLowBeamLightState({ put("lowbeam", it) }, { fail("lowbeam", it) })
        light.getCurrentTailLampLightState({ put("taillamp", it) }, { fail("taillamp", it) })
        light.getCurrentHighBeamLightState({ put("highbeam", it) }, { fail("highbeam", it) })
        light.registerLowBeamLightState(object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = put("lowbeam~", state)
            override fun onFailed(e: Exception) = fail("lowbeam~", e)
        })
        light.registerTailLampLightState(object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = put("taillamp~", state)
            override fun onFailed(e: Exception) = fail("taillamp~", e)
        })

        // 날씨 반응 장면: 와이퍼
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        v.getWiper().getCurrentWiperState(front, { put("wiper", it) }, { fail("wiper", it) })
        v.getWiper().registerWiperState(front, object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = put("wiper~", state)
            override fun onFailed(e: Exception) = fail("wiper~", e)
        })
    }

    private fun startNavi() {
        val n = NaviHelper(context)
        navi = n
        n.addListener(object : NaviHelperEventListener {
            override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) = put("navi.routeStarted", routeStartInfo)
            override fun onRouteCancelled() = put("navi.routeCancelled", "-")
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = put("navi.arrived", destinationArrivedInfo)
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = put("navi.location", currentLocationInfo)
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = put("navi.routeState", routeStateInfo)
            override fun onDestinationInfo(destinationInfo: DestinationInfo) = put("navi.destination", destinationInfo)
            override fun onNavigationStatus(status: NavigationStatus) = put("navi.status", status)
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = put("navi.tbt", tbtInfoList)
            override fun onError(errorCode: NaviErrorCode) = put("navi.error", errorCode)
            override fun onRoutingCompleted() = put("navi.routingCompleted", "-")
            override fun onDrivingInfo(drivingInfo: ai.pleos.playground.navi.data.DrivingInfo) = put("navi.drivingInfo", drivingInfo)
            override fun onTripInfo(tripInfo: ai.pleos.playground.navi.data.TripInfo) = put("navi.trip", tripInfo)
            override fun onCurrentRoadSpeedLimit(speedLimit: Int) = put("navi.speedLimit", speedLimit)
        })
        n.initialize({
            put("navi.init", "connected")
            n.getRouteStateInfo()
            n.getCurrentLocationInfo()
            n.getDestinationInfo()
            n.getTBTInfo()
            // 판교역 근처로 경로를 요청해 TBT·도착 이벤트가 오는지 본다
            handler.postDelayed({
                runCatching {
                    n.requestRoute(ai.pleos.playground.navi.data.RouteInfo(
                        37.3947, 127.1112, null, null, "판교역", "", "", "",
                        ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED, "", false,
                    ))
                }.onFailure { fail("navi.requestRoute", it) }
                put("navi.requestRoute", "sent")
            }, 4000)
        }, { fail("navi.init", it) })
    }

    // Pleos SDK에 없는 바깥 온도와, SDK 값과 맞대 볼 AAOS 프로퍼티
    private fun startAaos() {
        car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (!ready) return@createCar
            val pm = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            put("aaos.visible", pm.propertyList.joinToString(" ") { VehiclePropertyIds.toString(it.propertyId) })
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val v = value.value
                    val text = if (v is Array<*>) v.contentToString() else v.toString()
                    put("aaos.${VehiclePropertyIds.toString(value.propertyId)}@${value.areaId}", "$text (status ${value.status})")
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) =
                    put("aaos.${VehiclePropertyIds.toString(propertyId)}@$areaId", "error")
            }
            for (id in AAOS) {
                val name = VehiclePropertyIds.toString(id)
                val ok = runCatching { pm.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                put("aaos.$name.subscribe", ok.fold({ it }, { "${it.javaClass.simpleName}: ${it.message}" }))
            }
        }
    }

    private companion object {
        const val TAG = "PleosProbe"
        // compileSdk 35의 android.car 스텁에 상수가 없어 VHAL ID를 직접 쓴다
        const val WINDSHIELD_WIPERS_STATE = 0x13400BC6
        const val WINDSHIELD_WIPERS_SWITCH = 0x13400BC7
        val AAOS = listOf(
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE,
            VehiclePropertyIds.TIRE_PRESSURE,
            VehiclePropertyIds.CRITICALLY_LOW_TIRE_PRESSURE,
            VehiclePropertyIds.HEADLIGHTS_STATE,
            VehiclePropertyIds.NIGHT_MODE,
            WINDSHIELD_WIPERS_STATE,
            WINDSHIELD_WIPERS_SWITCH,
            VehiclePropertyIds.SEAT_BELT_BUCKLED,
            VehiclePropertyIds.PERF_ODOMETER,
            VehiclePropertyIds.EV_BATTERY_LEVEL,
            VehiclePropertyIds.ENGINE_OIL_LEVEL,
        )
    }
}
