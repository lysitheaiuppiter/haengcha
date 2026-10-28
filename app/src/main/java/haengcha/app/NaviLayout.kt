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

    /** 배치를 본 결과. [WAIT]이면 창이 아직 칸에 앉지 않아 조금 뒤에 다시 본다. */
    enum class Result { MOVED, STAY, WAIT }

    /** 지금 창이 세 칸 가운데 칸이면 내비게이션을 앞으로 부른다. */
    fun moveAside(activity: Activity): Result {
        val wm = activity.windowManager
        val me = wm.currentWindowMetrics.bounds
        val screen = wm.maximumWindowMetrics.bounds
        // Pleos 홈이 창을 칸에 앉히기 전에는 (0,0)에서 시작하는 크기만 보인다(맨 왼쪽은 Driving View 자리라 앱이 올 수 없다)
        if (!activity.isInMultiWindowMode || me.left == 0) {
            Log.d(TAG, "자리 기다림: 멀티 윈도=${activity.isInMultiWindowMode}, $me")
            return Result.WAIT
        }
        // 한 칸 너비(화면의 1/3쯤)이고 가운데 칸에 있을 때만. 두 칸을 다 쓰거나 이미 오른쪽이면 그대로 둔다
        val oneColumn = me.width() < screen.width() * 0.45
        val center = me.left > screen.width() * 0.2 && me.right < screen.width() * 0.8
        if (!oneColumn || !center) {
            Log.d(TAG, "가운데 칸이 아님: $me / $screen")
            return Result.STAY
        }
        val intent = activity.packageManager.getLaunchIntentForPackage(NAVI_PACKAGE)
            ?: Intent().setClassName(NAVI_PACKAGE, "$NAVI_PACKAGE.MainActivity")
        return try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "내비게이션을 가운데 칸으로, 행차는 오른쪽 칸으로")
            Result.MOVED
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "내비게이션 앱이 없어 배치를 바꾸지 않음")
            Result.STAY
        } catch (e: SecurityException) {
            Log.w(TAG, "내비게이션을 열 수 없음: ${e.message}")
            Result.STAY
        }
    }
}
