package haengcha.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import haengcha.judge.GeoPoint
import haengcha.judge.InspectionRule
import haengcha.judge.Landmark
import haengcha.judge.Region
import haengcha.judge.RestRule

/**
 * 디버그 빌드 전용 시연 도구. Pleos 홈 화면은 앱을 TaskView로 띄워서 이미 떠 있는 화면에 보낸 인텐트가
 * onNewIntent로 오지 않으므로 브로드캐스트로 받는다.
 *   adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app <옵션>
 * - 감찰 규칙: --es inspection "20,120,60" (시작 초, 감찰 초, 최소 주행 초). 10분 감찰을 짧은 주행으로 시험한다
 * - 충전: --ez charging true|false. 에뮬레이터에서 충전 포트 신호를 흉내 낸다
 * - 지난 기록: --es seed ULSAN --ei seedDays 2 --ei seedMonthTrips 7 --ef seedKm 20. 단골 등급·동반자·옛길 원정 진행을 시연한다
 * - 전국 유람 기록: --ez seedTour true. 지역 도감·명소·칭호·동반자를 시연한다
 * - 역참: --es rest "1,0.5" (쉬지 않고 달린 분, 쉰 분). 두 시간·15분 기준을 줄여 시험한다
 * - 바깥 기온: --ef temp -3. 에뮬레이터는 25도로 고정이라 눈길을 시연할 때 쓴다. --ez tempReset true로 차량 값으로 돌린다
 * - 명소 경로: --es route DAEWANGAM. 내비게이션에 시연용 명소 좌표로 경로를 잡는다
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val drive = (context.applicationContext as HaengchaApp).drive
        if (intent.hasExtra("charging")) drive.onChargePort(intent.getBooleanExtra("charging", false))
        if (intent.hasExtra("seed") || intent.hasExtra("seedMonthTrips") || intent.hasExtra("seedKm")) {
            val region = intent.getStringExtra("seed")?.let { runCatching { Region.valueOf(it) }.getOrNull() }
            drive.seedDemo(
                region,
                intent.getIntExtra("seedDays", if (region != null) 2 else 0),
                intent.getIntExtra("seedMonthTrips", 0),
                intent.getFloatExtra("seedKm", 0f).toDouble(),
            )
        }
        if (intent.getBooleanExtra("seedTour", false)) drive.seedTour()
        intent.getStringExtra("inspection")?.let { spec ->
            val values = spec.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (values.size != 3) return@let
            val (offset, window, moving) = values
            // 시험 때는 매 주행 감찰하고 하루 횟수 제한도 풀어 둔다
            drive.overrideInspection(
                InspectionRule(
                    windowSec = window, minOffsetSec = offset, maxOffsetSec = offset, minMovingSec = moving,
                    chancePerTrip = 1.0, maxPerDay = Int.MAX_VALUE,
                ),
            )
            Log.i(TAG, "감찰 규칙 변경: 시작 ${offset}초, ${window}초 동안, 최소 주행 ${moving}초")
        }
        intent.getStringExtra("rest")?.let { spec ->
            val values = spec.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (values.size != 2) return@let
            drive.overrideRest(RestRule(driveLimitMin = values[0], restMin = values[1]))
            Log.i(TAG, "역참 기준 변경: ${values[0]}분 운전, ${values[1]}분 휴식")
        }
        if (intent.hasExtra("temp")) drive.overrideOutsideTemperature(intent.getFloatExtra("temp", 25f))
        if (intent.getBooleanExtra("tempReset", false)) drive.overrideOutsideTemperature(null)
        intent.getStringExtra("route")?.let { key ->
            val landmark = runCatching { Landmark.valueOf(key) }.getOrNull() ?: return@let
            val point = DEMO_POINTS[landmark] ?: return@let
            drive.requestRoute(landmark.label, point)
            Log.i(TAG, "시연 경로 요청: ${landmark.label} $point")
        }
    }

    private companion object {
        const val TAG = "DebugReceiver"
        /** 시연용 명소 좌표(대략값). 앱은 명소를 좌표가 아니라 내비게이션 목적지 이름으로 알아본다. */
        val DEMO_POINTS = mapOf(
            Landmark.DAEWANGAM to GeoPoint(35.4925, 129.4395),
            Landmark.JANGSAENGPO to GeoPoint(35.5050, 129.3870),
            Landmark.TAEHWAGANG to GeoPoint(35.5490, 129.2990),
        )
    }
}
