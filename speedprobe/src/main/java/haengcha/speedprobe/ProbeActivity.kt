package haengcha.speedprobe

import android.app.Activity
import android.car.Car
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView

/** 프로브 통계를 0.25초마다 보여 주는 화면. 수집 자체는 ProbeApp이 쥔 SpeedProbe가 한다. */
class ProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val probe get() = (application as ProbeApp).probe
    private val pleos get() = (application as ProbeApp).pleos

    private val refresh = object : Runnable {
        override fun run() {
            text.text = probe.snapshot() + "\n\n" + pleos.lines.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.MONOSPACE
            setPadding(48, 48, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(text) })

        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startAll() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 거부돼도 시작한다. 권한이 없는 프로퍼티는 화면에 '미지원 또는 권한 없음'으로 나온다.
        if (requestCode == REQUEST_PERMISSIONS) startAll()
    }

    private fun startAll() {
        probe.start()
        pleos.start()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        val PERMISSIONS = arrayOf(
            Car.PERMISSION_SPEED, Car.PERMISSION_POWERTRAIN, Car.PERMISSION_ENERGY_PORTS,
            Car.PERMISSION_ENERGY, Car.PERMISSION_EXTERIOR_ENVIRONMENT,
            "android.car.permission.CAR_TIRES", "android.car.permission.CAR_EXTERIOR_LIGHTS",
            "android.car.permission.CAR_MILEAGE",
        )
    }
}
