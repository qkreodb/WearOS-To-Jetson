package com.example.dsandroidapp.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object WatchNotificationHelper {

    private const val CHANNEL_ID = "watch_hazard_alert"
    private const val TAG = "WATCH_NOTI"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "위험 감지 알림",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Jetson 서버로부터 위험 경보 수신"
                enableVibration(false) // 진동은 Service에서 별도 처리
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun showAlertNotification(context: Context, command: HazardAlertCommand) {
        val level = command.level ?: "warning"
        // info 레벨은 알림 표시 생략 (너무 잦은 알림 방지)
        if (level == "info") return

        // Android 13 이상 권한 체크 (Wear OS는 보통 자동 허용이지만 안전하게 체크)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS 권한 없음, 알림 생략")
                return
            }
        }

        val title = command.title ?: "위험 알림"
        val body  = command.message ?: "위험 상황이 감지되었습니다."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            context, command.eventId?.toInt() ?: 0, intent, pendingFlags
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notifId = (command.eventId?.toInt() ?: System.currentTimeMillis().toInt()) and Int.MAX_VALUE
        try {
            nm.notify(notifId, notification)
            Log.d(TAG, "show notifId=$notifId level=$level title=$title")
        } catch (e: Exception) {
            Log.e(TAG, "알림 표시 실패: ${e.message}")
        }
    }
}
