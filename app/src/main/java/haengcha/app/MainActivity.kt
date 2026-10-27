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
        arrangePending = savedInstanceState == null && app.prefs.naviCenter
    }

    private var arrangePending = false

    /** Pleos 홈이 창을 칸에 앉히는 데 시간이 걸려, 포커스를 받은 뒤 몇 번 나눠 본다. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !arrangePending) return
        arrangePending = false
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                if (NaviLayout.moveAside(this@MainActivity) || ++tries >= 4) return
                window.decorView.postDelayed(this, 500)
            }
        }
        window.decorView.postDelayed(check, 300)
    }

    private fun requestPermissions() = permissionRequest.launch(CarVehicleSignals.RUNTIME_PERMISSIONS + LocationSource.PERMISSIONS)
}
