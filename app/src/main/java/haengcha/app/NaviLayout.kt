package haengcha.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log

/**
 * Pleos 홈 화면 배치: [Driving View | 내비게이션 | 행차].
 * Pleos 홈은 새로 앞에 온 앱을 가운데 칸에 두고 그전 가운데 앱을 오른쪽 칸으로 민다.
 * 그래서 행차가 가운데 칸에서 열리면 내비게이션을 앞으로 불러 길 안내를 운전자 쪽(가운데)에 두고 행차는 오른쪽으로 비킨다.
 */
object NaviLayout {
    private const val TAG = "NaviLayout"
    /** Pleos 기본 내비게이션. */
    const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"

    /** 지금 창이 세 칸 가운데 칸이면 내비게이션을 앞으로 부른다. 옮겼으면 true. */
    fun moveAside(activity: Activity): Boolean {
        if (!activity.isInMultiWindowMode) return false
        val wm = activity.windowManager
        val me = wm.currentWindowMetrics.bounds
        val screen = wm.maximumWindowMetrics.bounds
        // 한 칸 너비(화면의 1/3쯤)이고 가운데 칸에 있을 때만. 두 칸을 다 쓰거나 이미 오른쪽이면 그대로 둔다
        val oneColumn = me.width() < screen.width() * 0.45
        val center = me.left > screen.width() * 0.2 && me.right < screen.width() * 0.8
        if (!oneColumn || !center) {
            Log.d(TAG, "가운데 칸이 아님: $me / $screen")
            return false
        }
        val intent = activity.packageManager.getLaunchIntentForPackage(NAVI_PACKAGE)
            ?: Intent().setClassName(NAVI_PACKAGE, "$NAVI_PACKAGE.MainActivity")
        return try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "내비게이션을 가운데 칸으로, 행차는 오른쪽 칸으로")
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "내비게이션 앱이 없어 배치를 바꾸지 않음")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "내비게이션을 열 수 없음: ${e.message}")
            false
        }
    }
}
