package haengcha.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 주행 기록 포그라운드 서비스. 수집 자체는 Application의 [DriveController]가 하고, 이 서비스는 화면을 닫아도
 * 프로세스가 내려가지 않게 붙잡고 위치를 계속 받게 한다(유형 location). 앱 화면이 열릴 때 시작한다.
 * 그래도 프로세스가 죽으면 주행 일지로 이어 가거나 마감한다.
 */
class CollectService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "주행 기록", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_haengcha)
            .setContentTitle("행차")
            .setContentText("안전 주행을 기록하고 있습니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID, notification)
            }
        }.onFailure {
            // 위치 권한이 없으면 location 유형으로 시작할 수 없다. 이때는 프로세스가 사는 동안만 수집한다
            Log.w(TAG, "포그라운드 서비스 시작 실패: ${it.message}")
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "CollectService"
        private const val CHANNEL = "trip"
        private const val ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CollectService::class.java)) }
                .onFailure { Log.w(TAG, "서비스 시작 실패: ${it.message}") }
        }
    }
}
