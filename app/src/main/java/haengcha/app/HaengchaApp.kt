package haengcha.app

import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import haengcha.app.voice.VoiceGuide
import haengcha.vehicle.CarVehicleSignals
import haengcha.vehicle.PleosNavi
import haengcha.vehicle.PleosVehicleSignals
import java.io.File

class HaengchaApp : Application() {

    lateinit var drive: DriveController
        private set

    override fun onCreate() {
        super.onCreate()
        val voice = VoiceGuide(this).also { it.start() }
        // 부팅 횟수로 같은 부팅인지 가린다(주행 일지의 시각이 부팅 기준이다)
        val bootId = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
        drive = DriveController(
            AppPrefs(this),
            { id -> TripStore(File(filesDir, AppPrefs.tripsFile(id))) },
            TripJournalFile(File(filesDir, "trip_journal.csv")),
            bootId,
            CarVehicleSignals(this),
            PleosVehicleSignals(this),
            PleosNavi(this),
            LocationSource(this),
            voice::say,
        )
        // 권한이 이미 있으면 화면을 열지 않아도 바로 수집을 시작한다
        if (hasVehiclePermissions()) drive.start()
        drive.startLocation()
    }

    fun hasVehiclePermissions() =
        CarVehicleSignals.RUNTIME_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
