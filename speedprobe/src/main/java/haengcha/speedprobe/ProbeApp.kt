package haengcha.speedprobe

import android.app.Application

/** 화면이 가려져도 수집이 이어지도록 프로브를 프로세스 수명에 묶는다. */
class ProbeApp : Application() {

    lateinit var probe: SpeedProbe
        private set
    lateinit var pleos: PleosProbe
        private set

    override fun onCreate() {
        super.onCreate()
        probe = SpeedProbe(this)
        pleos = PleosProbe(this)
    }
}
