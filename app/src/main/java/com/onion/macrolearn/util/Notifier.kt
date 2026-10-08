package com.onion.macrolearn.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onion.macrolearn.R

/** 알림 채널/알림 생성 유틸. 실행 중 표시(포그라운드)와 실패 보고를 담당한다. */
object Notifier {
    private const val CH_RUN = "macro_run"
    private const val CH_ALERT = "macro_alert"
    const val RUN_NOTIFICATION_ID = 1001
    private const val ALERT_NOTIFICATION_ID = 2001

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUN, "매크로 실행 상태", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "매크로 실패 알림", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** 포그라운드 서비스용 진행 알림 */
    fun progress(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("매크로 실행 중")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    /** 스텝 실패(3회 재시도 후)를 사용자에게 보고 */
    fun reportFailure(ctx: Context, macroName: String, stepNo: Int, reason: String) =
        notifyAlert(ctx, "매크로 실패: $macroName", "${stepNo}번째 스텝에서 중단됨 — $reason")

    fun reportSuccess(ctx: Context, macroName: String) =
        notifyAlert(ctx, "매크로 완료", "$macroName 실행을 마쳤습니다.")

    fun reportCancelled(ctx: Context, macroName: String) =
        notifyAlert(ctx, "매크로 취소됨", "$macroName — 홈 버튼을 눌러 실행을 중단했습니다.")

    private fun notifyAlert(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        // POST_NOTIFICATIONS 미허용 시 조용히 무시 (크래시 방지)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ALERT_NOTIFICATION_ID, n) }
    }
}
