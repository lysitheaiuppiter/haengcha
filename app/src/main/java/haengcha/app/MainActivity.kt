package haengcha.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import haengcha.app.ui.HaengchaScreen
import haengcha.app.ui.HaengchaTheme
import haengcha.app.ui.ScreenActions
import haengcha.vehicle.CarVehicleSignals

class MainActivity : ComponentActivity() {

    private val app get() = application as HaengchaApp

    private val actions by lazy {
        ScreenActions(
            requestPermission = ::requestPermissions,
            dismissCelebration = app.drive::dismissCelebration,
            switchProfile = { app.drive.switchProfile(it) },
            addProfile = app.drive::addProfile,
            buy = app.drive::buy,
            chooseBuddy = app.drive::chooseBuddy,
            chooseTitle = app.drive::chooseTitle,
            naviCenter = { app.prefs.naviCenter },
            setNaviCenter = { on ->
                app.prefs.naviCenter = on
                if (on) NaviLayout.moveAside(this)
            },
        )
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.hasVehiclePermissions()) app.drive.start()
        app.drive.startLocation()
        CollectService.start(this)
    }

    override fun onStart() {
        super.onStart()
        // 화면이 보일 때 시작해야 위치 유형 포그라운드 서비스를 띄울 수 있다
        CollectService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by app.drive.state.collectAsState()
            HaengchaTheme {
                HaengchaScreen(state, actions)
            }
        }
        if (!app.hasVehiclePermissions() || !app.drive.state.value.locating) requestPermissions()
        // 처음 열릴 때 가운데 칸이면 내비게이션을 가운데로 부르고 행차는 오른쪽으로 비킨다
        if (savedInstanceState == null && app.prefs.naviCenter) arrangeWithNavi()
    }

    /**
     * Pleos 홈이 창을 칸에 앉히는 데 시간이 걸린다(앱을 새로 띄우면 수 초). 자리가 정해질 때까지 0.5초마다 다시 보고,
     * 15초가 지나도 모르면 그만둔다. 다시 켤 때 창이 포커스를 받지 못하는 경우가 있어 포커스를 기다리지 않는다.
     */
    private fun arrangeWithNavi() {
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                if (isFinishing || NaviLayout.moveAside(this@MainActivity) != NaviLayout.Result.WAIT || ++tries >= 30) return
                window.decorView.postDelayed(this, 500)
            }
        }
        window.decorView.postDelayed(check, 500)
    }

    private fun requestPermissions() = permissionRequest.launch(CarVehicleSignals.RUNTIME_PERMISSIONS + LocationSource.PERMISSIONS)
}
