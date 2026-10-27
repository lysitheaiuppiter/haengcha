package haengcha.drive

import haengcha.judge.Destination
import haengcha.judge.Gear
import haengcha.judge.SpeedSample

/** 신호별 구독 결과. 지원하지 않거나 권한이 없으면 [available]이 false. */
data class SignalStatus(val name: String, val available: Boolean)

/** 차량 신호를 받는 쪽(AAOS Car API, Pleos Vehicle SDK). 모든 콜백은 메인 스레드에서 온다. */
interface VehicleListener {
    fun onSpeed(sample: SpeedSample)
    fun onGear(gear: Gear, tNanos: Long)
    /** [noVideo]는 규제에 영상 금지(UX_RESTRICTIONS_NO_VIDEO)가 들어 있는지. 이때는 움직이는 화면을 멈춘다. */
    fun onDrivingRestricted(restricted: Boolean, noVideo: Boolean)
    fun onChargePort(connected: Boolean) {}
    fun onStatusChanged(statuses: List<SignalStatus>) {}
    /** 바깥 기온(AAOS, Pleos SDK에는 없음). 눈길 판정에 쓴다. */
    fun onOutsideTemperature(celsius: Float) {}

    // Pleos Vehicle SDK
    fun onBatterySoc(percent: Int) {}
    fun onEvEfficiency(kmPerKwh: Float) {}
    fun onOdometer(km: Float) {}
    /** 켜져 있는 경고등 종류 이름(TelltaleType). */
    fun onTelltales(active: Set<String>) {}
    fun onEngineOilLow(low: Boolean) {}
    fun onSeatBelt(buckled: Boolean) {}
    fun onHeadlights(on: Boolean) {}
    fun onWiper(on: Boolean) {}
    fun onSdkStatus(statuses: List<SignalStatus>) {}
}

/** 내비게이션에서 받는 쪽(Pleos NaviHelper). 모든 콜백은 메인 스레드에서 온다. */
interface NaviListener {
    fun onNaviConnected(connected: Boolean) {}
    /** 안내 중인 목적지. 경로가 없으면 null. */
    fun onDestination(dest: Destination?) {}
    /** 다음 회전까지 거리와, 경로 앞에 휴게소·졸음쉼터가 있으면 그곳까지 거리(m). 경로가 없으면 둘 다 null. */
    fun onTurns(nextTurnM: Double?, restAreaM: Double?) {}
    /** 내비게이션이 목적지 도착을 알렸다. */
    fun onArrived(name: String?) {}
}
