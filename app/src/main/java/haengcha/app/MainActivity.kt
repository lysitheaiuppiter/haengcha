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
    }

    private fun requestPermissions() = permissionRequest.launch(CarVehicleSignals.RUNTIME_PERMISSIONS + LocationSource.PERMISSIONS)
}
