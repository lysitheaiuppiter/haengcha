package haengcha.speedprobe

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.content.pm.CarPackageManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 차속·기어·주차 브레이크·충전 포트·주행 규제 상태를 구독해 수신 주기를 재고 원본 이벤트를 CSV로 남긴다.
 * Car를 메인 Handler로 연결하므로 콜백과 통계 갱신은 모두 메인 스레드에서 일어난다.
 */
class SpeedProbe(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var writer: BufferedWriter? = null

    var logFile: File? = null
        private set
    var status = "대기"
        private set
    private val configSummary = mutableListOf<String>()

    // 차속 수신 통계
    private val recvWindow = ArrayDeque<Long>()                // 최근 5초 수신 시각(ns)
    private val speedWindow = ArrayDeque<Pair<Long, Float>>()  // 최근 2.5초 (이벤트 시각 ns, m/s)
    private var lastEventNs = 0L
    private var speedEvents = 0L
    private var lastSpeedMps = Float.NaN
    private var minGapMs = Double.MAX_VALUE
    private var maxGapMs = 0.0
    private var gapSumMs = 0.0
    private var gapCount = 0L
    private var maxAccel = 0f  // 0.5~2.5초 앞 표본과 비교한 최대 가속(m/s²)
    private var maxDecel = 0f  // 같은 기준의 최대 감속(m/s², 음수)

    private var gear: Int? = null
    private var parkingBrake: Boolean? = null
    private var chargePortConnected: Boolean? = null
    private var uxState = "-"

    private val watched = listOf(
        VehiclePropertyIds.PERF_VEHICLE_SPEED,
        VehiclePropertyIds.GEAR_SELECTION,
        VehiclePropertyIds.PARKING_BRAKE_ON,
        VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED,
    )

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onProperty(value)

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            write("ERROR", VehiclePropertyIds.toString(propertyId), areaId, -1, "")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { onUx(it) }

    private val flusher = object : Runnable {
        override fun run() {
            writer?.flush()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (car != null) return
        openLog()
        status = "Car 서비스 연결 중"
        car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (ready) subscribe(connected) else status = "Car 서비스 연결 끊김"
        }
    }

    private fun subscribe(connected: Car) {
        val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        configSummary.clear()
        for (id in watched) {
            val name = VehiclePropertyIds.toString(id)
            // 권한이 없으면 SecurityException, 미지원이면 null
            val config = runCatching { properties.getCarPropertyConfig(id) }.getOrNull()
            if (config == null) {
                configSummary += "$name: 미지원 또는 권한 없음"
                info("$name unavailable")
                continue
            }
            val continuous = config.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS
            val rate = if (continuous) config.maxSampleRate else CarPropertyManager.SENSOR_RATE_ONCHANGE
            val subscribed = runCatching { properties.registerCallback(callback, id, rate) }
                .onFailure { Log.w(TAG, "구독 실패 $name", it) }
                .getOrDefault(false)
            val line = "$name: mode=${config.changeMode} 지원 ${config.minSampleRate}~${config.maxSampleRate}Hz " +
                "요청 ${rate}Hz 구독 ${if (subscribed) "성공" else "실패"}"
            configSummary += line
            info(line)
        }

        val ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        onUx(ux.currentCarUxRestrictions)
        ux.registerListener(uxListener)

        // 주행 중 사용 가능(DO) 화면으로 시스템이 인정하는지
        val packages = connected.getCarManager(Car.PACKAGE_SERVICE) as CarPackageManager
        for ((pkg, activity) in DO_CHECK) {
            val line = "DO $pkg/$activity: ${runCatching { packages.isActivityDistractionOptimized(pkg, activity) }.getOrElse { it.toString() }}"
            configSummary += line
            info(line)
        }
        status = "수집 중"
    }

    private fun onProperty(value: CarPropertyValue<*>) {
        val recvNs = SystemClock.elapsedRealtimeNanos()
        write("PROP", VehiclePropertyIds.toString(value.propertyId), value.areaId, value.status,
            value.value?.toString().orEmpty(), value.timestamp, recvNs)
        if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
        when (value.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> onSpeed(value.timestamp, recvNs, value.value as Float)
            VehiclePropertyIds.GEAR_SELECTION -> gear = value.value as Int
            VehiclePropertyIds.PARKING_BRAKE_ON -> parkingBrake = value.value as Boolean
            VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED -> chargePortConnected = value.value as Boolean
        }
    }

    private fun onSpeed(eventNs: Long, recvNs: Long, mps: Float) {
        speedEvents++
        lastSpeedMps = mps
        recvWindow.addLast(recvNs)

        if (lastEventNs != 0L && eventNs > lastEventNs) {
            val gap = (eventNs - lastEventNs) / 1e6
            minGapMs = minOf(minGapMs, gap)
            maxGapMs = maxOf(maxGapMs, gap)
            gapSumMs += gap
            gapCount++
        }
        lastEventNs = maxOf(lastEventNs, eventNs)

        speedWindow.addLast(eventNs to mps)
        while (speedWindow.first().first < eventNs - 2_500_000_000L) speedWindow.removeFirst()
        // 0.5초 이상 앞선 가장 최근 표본과 비교해 가감속을 구한다
        val ref = speedWindow.lastOrNull { it.first <= eventNs - 500_000_000L } ?: return
        val a = ((mps - ref.second) / ((eventNs - ref.first) / 1e9)).toFloat()
        if (a > maxAccel) maxAccel = a
        if (a < maxDecel) maxDecel = a
    }

    private fun onUx(restrictions: CarUxRestrictions) {
        uxState = if (restrictions.isRequiresDistractionOptimization) {
            "주행 규제 중 (0x${Integer.toHexString(restrictions.activeRestrictions)})"
        } else {
            "규제 없음"
        }
        write("UX", "-", 0, 0,
            "do=${restrictions.isRequiresDistractionOptimization};restrictions=${restrictions.activeRestrictions}")
    }

    fun snapshot(): String {
        val now = SystemClock.elapsedRealtimeNanos()
        while (recvWindow.isNotEmpty() && recvWindow.first() < now - 5_000_000_000L) recvWindow.removeFirst()
        val rate = if (recvWindow.size < 2) 0.0 else (recvWindow.size - 1) / ((recvWindow.last() - recvWindow.first()) / 1e9)
        val kmh = if (lastSpeedMps.isNaN()) "-" else "%.1f".format(lastSpeedMps * 3.6f)
        val gaps = if (gapCount == 0L) "-" else "%.0f / %.0f / %.0f".format(minGapMs, gapSumMs / gapCount, maxGapMs)
        return buildString {
            appendLine("상태: $status")
            appendLine("차속: $kmh km/h   수신 ${speedEvents}건, 최근 5초 %.1f Hz".format(rate))
            appendLine("이벤트 간격 최소/평균/최대(ms): $gaps")
            appendLine("가감속 최대(m/s²): +%.2f / %.2f".format(maxAccel, maxDecel))
            appendLine("기어: ${gearName(gear)}   주차 브레이크: ${parkingBrake ?: "-"}   충전 포트 연결: ${chargePortConnected ?: "-"}")
            appendLine("주행 규제: $uxState")
            appendLine()
            configSummary.forEach { appendLine(it) }
            appendLine()
            append("로그: ${logFile?.absolutePath ?: "-"}")
        }
    }

    private fun gearName(g: Int?) = when (g) {
        null -> "-"
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        else -> g.toString()
    }

    private fun openLog() {
        // AAOS는 운전자 계정(user 10) 외부 저장소를 adb로 못 읽으므로 내부 저장소에 쓰고 run-as로 꺼낸다
        val file = File(context.filesDir, "probe_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
        writer = file.bufferedWriter().apply {
            write("kind,recv_ns,event_ns,prop,area,status,value")
            newLine()
        }
        logFile = file
        handler.postDelayed(flusher, 1000)
    }

    private fun info(text: String) = write("INFO", "-", 0, 0, text.replace(',', ';'))

    private fun write(
        kind: String, prop: String, area: Int, status: Int, value: String,
        eventNs: Long = 0L, recvNs: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        writer?.apply {
            write("$kind,$recvNs,$eventNs,$prop,$area,$status,$value")
            newLine()
        }
    }

    private companion object {
        const val TAG = "SpeedProbe"
        val DO_CHECK = listOf(
            "haengcha.speedprobe" to "haengcha.speedprobe.ProbeActivity",
            "haengcha.app" to "haengcha.app.MainActivity",
        )
    }
}
