package haengcha.vehicle

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import haengcha.drive.NaviListener
import haengcha.judge.Destination
import haengcha.judge.GeoPoint

/**
 * Pleos NaviHelper SDK 연동. 목적지(도착 스탬프), 회전 안내 TBT(안내 타이밍 조절, 경로 위 휴게소)를 받고,
 * 쉬어 갈 때 내비게이션에 휴게소 검색 화면을 띄운다. 내비게이션이 없는 차량이면 연결이 실패하고 기능만 빠진다.
 */
class PleosNavi(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    private var listener: NaviListener? = null

    private val events = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            // 경로가 잡히면 목적지 좌표와 회전 안내를 다시 받는다
            navi?.getDestinationInfo()
            navi?.getTBTInfo()
        }

        override fun onRouteCancelled() = clear()

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            val name = destinationArrivedInfo.name
            post { onArrived(name) }
            clear()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val lat = destinationInfo.latitude ?: 0.0
            val lon = destinationInfo.longitude ?: 0.0
            val dest = if (lat == 0.0 && lon == 0.0) null else Destination(destinationInfo.name.orEmpty(), GeoPoint(lat, lon))
            post { onDestination(dest) }
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val turns = tbtInfoList.filter { (it.distance ?: -1.0) >= 0 }.sortedBy { it.distance }
            val next = turns.firstOrNull()?.distance
            val rest = turns.firstOrNull { it.type == TurnType.TURN_ENTER_SERVICEAREA || it.type == TurnType.TURN_RESTAREA }?.distance
            post { onTurns(next, rest) }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            if (status != NavigationStatus.ROUTE_DRIVE) clear()
        }
    }

    fun start(listener: NaviListener) {
        if (navi != null) return
        this.listener = listener
        val n = NaviHelper(context)
        navi = n
        runCatching {
            n.addListener(events)
            n.initialize({
                post { onNaviConnected(true) }
                n.getDestinationInfo()
                n.getTBTInfo()
            }, { e ->
                Log.i(TAG, "내비게이션 연결 실패: ${e.message}")
                post { onNaviConnected(false) }
            })
        }.onFailure {
            Log.w(TAG, "NaviHelper 초기화 실패", it)
            navi = null
        }
    }

    fun stop() {
        runCatching {
            navi?.removeListener(events)
            navi?.release()
        }
        navi = null
        listener = null
    }

    /** 쉬어 갈 때: 내비게이션에 휴게소 검색 화면을 띄운다. 운전자가 고르면 내비게이션이 경유지로 넣는다. */
    fun searchRestArea() {
        runCatching { navi?.requestNavigationScreen(NavigationScreenRequest.SearchPlace(REST_AREA_KEYWORD)) }
            .onFailure { Log.w(TAG, "휴게소 검색 요청 실패", it) }
    }

    /** 시연용: 명소로 경로를 요청한다. */
    fun requestRoute(name: String, point: GeoPoint) {
        runCatching {
            navi?.requestRoute(RouteInfo(point.lat, point.lon, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
        }.onFailure { Log.w(TAG, "경로 요청 실패", it) }
    }

    private fun clear() = post {
        onTurns(null, null)
        onDestination(null)
    }

    private fun post(block: NaviListener.() -> Unit) {
        handler.post { listener?.block() }
    }

    private companion object {
        const val TAG = "PleosNavi"
        const val REST_AREA_KEYWORD = "휴게소"
    }
}
