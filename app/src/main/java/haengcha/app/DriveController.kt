package haengcha.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import haengcha.drive.Clock
import haengcha.drive.DriveEngine
import haengcha.drive.DriveUiState
import haengcha.judge.Buddy
import haengcha.judge.GeoPoint
import haengcha.judge.InspectionRule
import haengcha.judge.Region
import haengcha.judge.RestRule
import haengcha.vehicle.CarVehicleSignals
import haengcha.vehicle.PleosNavi
import haengcha.vehicle.PleosVehicleSignals
import kotlinx.coroutines.flow.StateFlow

/**
 * 주행 엔진([DriveEngine])을 Android에 잇는다. 시계는 SystemClock, 예약 실행은 메인 Handler, 설정·기록·일지는 앱 저장소,
 * 차량 신호는 AAOS Car API·Pleos Vehicle SDK, 내비게이션은 NaviHelper, 위치는 Android 위치 API다.
 * 판정·저장·안내 규칙은 모두 엔진에 있고 이 클래스는 연결만 한다.
 * Application 수명이라 다른 앱이 앞에 있거나 주행 중 화면이 가려져도 프로세스가 살아 있는 동안은 수집이 이어진다.
 */
class DriveController(
    prefs: AppPrefs,
    storeFor: (profileId: String) -> TripStore,
    journal: TripJournalFile,
    bootId: String,
    private val signals: CarVehicleSignals,
    private val pleos: PleosVehicleSignals,
    private val navi: PleosNavi,
    private val location: LocationSource,
    say: (List<String>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val engine = DriveEngine(
        settings = prefs,
        storeFor = storeFor,
        journal = journal,
        bootId = bootId,
        clock = object : Clock {
            override fun nowMs() = System.currentTimeMillis()
            override fun elapsedNanos() = SystemClock.elapsedRealtimeNanos()
        },
        scheduler = { delayMs, action -> handler.postDelayed(action, delayMs) },
        say = say,
        searchRestArea = navi::searchRestArea,
        log = { Log.i(TAG, it) },
    )

    val state: StateFlow<DriveUiState> = engine.state

    fun start() {
        if (state.value.collecting) return
        signals.start(engine)
        pleos.start(engine)
        navi.start(engine)
        engine.setCollecting(true)
    }

    fun startLocation() {
        if (state.value.locating) return
        if (location.start(engine::onLocation)) engine.setLocating(true)
    }

    fun switchProfile(id: String) = engine.switchProfile(id)

    fun addProfile() = engine.addProfile()

    fun buy(itemId: String) = engine.buy(itemId)

    fun chooseBuddy(buddy: Buddy) = engine.chooseBuddy(buddy)

    fun dismissCelebration() = engine.dismissCelebration()

    // 디버그 빌드 시연 도구(DebugReceiver)

    fun onChargePort(connected: Boolean) = engine.onChargePort(connected)

    fun seedDemo(region: Region?, days: Int, monthTrips: Int, journeyKm: Double = 0.0) = engine.seedDemo(region, days, monthTrips, journeyKm)

    fun seedTour() = engine.seedTour()

    fun overrideInspection(rule: InspectionRule) = engine.overrideInspection(rule)

    fun overrideRest(rule: RestRule) = engine.overrideRest(rule)

    fun overrideOutsideTemperature(celsius: Float?) = engine.overrideOutsideTemperature(celsius)

    fun requestRoute(name: String, point: GeoPoint) = navi.requestRoute(name, point)

    private companion object {
        const val TAG = "DriveController"
    }
}
