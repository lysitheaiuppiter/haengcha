package haengcha.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import haengcha.judge.GeoPoint

/**
 * 차량 위치. 지역 테마와 어린이보호구역 판정에만 쓰고 좌표는 저장하지 않는다(기록에는 지역 이름만 남는다).
 * Pleos FusedLocation SDK는 아직 붙이지 않아 Android 표준 LocationManager를 쓴다(기획서 8장).
 */
class LocationSource(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private var callback: ((GeoPoint) -> Unit)? = null
    private val listener = LocationListener { loc -> callback?.invoke(GeoPoint(loc.latitude, loc.longitude)) }

    val granted: Boolean
        get() = PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** 위치를 받기 시작한다. 권한이나 위치 제공자가 없으면 false. 콜백은 메인 스레드에서 온다. */
    @SuppressLint("MissingPermission")
    fun start(onLocation: (GeoPoint) -> Unit): Boolean {
        if (callback != null) return true
        if (!granted) return false
        val provider = PROVIDERS.firstOrNull { it in manager.allProviders } ?: run {
            Log.w(TAG, "위치 제공자 없음: ${manager.allProviders}")
            return false
        }
        callback = onLocation
        manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
        manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        Log.i(TAG, "위치 수신 시작: $provider")
        return true
    }

    companion object {
        private const val TAG = "LocationSource"
        // FUSED_PROVIDER 상수는 API 31부터라 문자열로 쓴다
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
