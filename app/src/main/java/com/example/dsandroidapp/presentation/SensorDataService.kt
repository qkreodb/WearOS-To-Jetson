package com.example.dsandroidapp.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// Samsung Health Sensor SDK (Health Tracking)
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

class SensorDataService : Service() {

    private val tag = "DS_SERVICE"
    private val channelID = "SensorServiceChannel"

    // ====== Jetson UDP ======
    private val jetsonIp = "192.168.0.10"
    private val jetsonPort = 5005
    private var udpSocket: DatagramSocket? = null

    // ====== Periods ======
    private val heartRateSendPeriodMs = 5_000L        // 심박 UDP 송신 주기
    private val skinTempTriggerPeriodMs = 60_000L     // 피부온도 측정 트리거 주기(ON_DEMAND)

    // ====== IDs ======
    private lateinit var deviceId: String

    // ====== Coroutines ======
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 메인스레드 핸들러(Dispatchers.Main 안 써도 됨)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ====== Samsung Health Tracking ======
    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null

    // ====== Throttling ======
    private val lastHeartRateSentAt = AtomicLong(0L)
    private val lastSkinTempTriggeredAt = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        createNotificationChannel()

        try {
            udpSocket = DatagramSocket()
            Log.d(tag, "✅ UDP 소켓 생성 완료")
        } catch (e: Exception) {
            Log.e(tag, "❌ UDP 소켓 생성 실패: ${e.message}", e)
        }

        connectSamsungHealthTrackingService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle("DS 실시간 수집 중")
            .setContentText("심박/피부온도 수집 및 Jetson 전송")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

        return START_STICKY
    }

    // =========================================================
    // 1) Samsung HealthTrackingService 연결
    // =========================================================
    private fun connectSamsungHealthTrackingService() {
        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(tag, "✅ HealthTrackingService 연결 성공")

                // 지원 트래커 목록 확인 (이게 제일 중요)
                val supportedTypes: List<HealthTrackerType> =
                    try {
                        // SDK에 따라 getTrackingCapability() / trackingCapability 둘 다 케이스가 있는데
                        // Kotlin에선 보통 아래처럼 property로 접근 가능(실패하면 catch로 로그)
                        healthTrackingService?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(tag, "❌ trackingCapability 조회 실패: ${e.message}", e)
                        emptyList()
                    }

                Log.d(tag, "📌 지원 트래커 목록: $supportedTypes")

                // Tracker 생성
                initTrackers(supportedTypes)

                // 심박 리스닝 시작(continuous)
                startHeartRateListening()

                // 피부온도는 1분마다 ON_DEMAND 트리거
                startSkinTempOnDemandLoop()
            }

            override fun onConnectionEnded() {
                Log.w(tag, "⚠️ HealthTrackingService 연결 종료")
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                Log.e(tag, "❌ HealthTrackingService 연결 실패: ${e.message}", e)
                if (e.hasResolution()) {
                    // Service에서 바로 UI 띄워 해결하기 어려움 (보통 Activity에서 처리)
                    Log.e(tag, "➡️ resolution 필요: Activity에서 ResolutionIntent 처리 권장")
                }
            }
        }

        try {
            healthTrackingService = HealthTrackingService(connectionListener, applicationContext)
            healthTrackingService?.connectService()
            Log.d(tag, "🚀 connectService() 호출")
        } catch (e: Exception) {
            Log.e(tag, "❌ HealthTrackingService 생성/연결 실패: ${e.message}", e)
        }
    }

    // =========================================================
    // 2) Tracker 생성 (지원 여부 기반)
    // =========================================================
    private fun initTrackers(supported: List<HealthTrackerType>) {
        // --- Heart Rate ---
        if (supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            try {
                heartRateTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                Log.d(tag, "✅ HeartRate tracker 생성 완료 (HEART_RATE_CONTINUOUS)")
            } catch (e: Exception) {
                Log.e(tag, "❌ HeartRate tracker 생성 실패: ${e.message}", e)
            }
        } else {
            Log.e(tag, "❌ HEART_RATE_CONTINUOUS 미지원 (권한/환경/SDK 확인)")
        }

        // --- Skin Temperature (ON_DEMAND) ---
        if (supported.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            try {
                skinTempTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
                Log.d(tag, "✅ SkinTemp tracker 생성 완료 (SKIN_TEMPERATURE_ON_DEMAND)")
            } catch (e: Exception) {
                Log.e(tag, "❌ SkinTemp tracker 생성 실패: ${e.message}", e)
            }
        } else {
            Log.e(tag, "❌ SKIN_TEMPERATURE_ON_DEMAND 미지원 (권한/환경/SDK 확인)")
        }
    }

    // =========================================================
    // 3) Heart Rate listener (5초마다 UDP 송신)
    // =========================================================
    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val hrValue: Float? = try {
                    // ✅ 삼성 SDK 심박 키
                    dp.getValue(ValueKey.HeartRateSet.HEART_RATE).toFloat()
                } catch (e: Exception) {
                    null
                }

                if (hrValue == null) {
                    Log.w(tag, "⚠️ 심박 파싱 실패(ValueKey 확인 필요)")
                    continue
                }

                val now = System.currentTimeMillis()
                val last = lastHeartRateSentAt.get()
                if (now - last >= heartRateSendPeriodMs && lastHeartRateSentAt.compareAndSet(last, now)) {
                    Log.d(tag, "❤️ 심박 수신: $hrValue (전송)")
                    sendToJetsonUDP("HEART_RATE", hrValue)
                } else {
                    // 너무 자주 들어올 수 있어서 5초 스킵
                    Log.d(tag, "❤️ 심박 수신: $hrValue (스킵: 5초 제한)")
                }
            }
        }

        override fun onFlushCompleted() {
            Log.d(tag, "HeartRate flush completed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(tag, "❌ 심박 트래커 에러: $error")
        }
    }

    private fun startHeartRateListening() {
        val tracker = heartRateTracker
        if (tracker == null) {
            Log.w(tag, "⚠️ heartRateTracker=null, 심박 리스닝 시작 불가")
            return
        }

        // listener 등록은 메인에서 처리(안전)
        mainHandler.post {
            try {
                tracker.setEventListener(heartRateListener)
                Log.d(tag, "🚀 심박 트래커 리스닝 시작")
            } catch (e: Exception) {
                Log.e(tag, "❌ 심박 리스닝 시작 실패: ${e.message}", e)
            }
        }
    }

    // =========================================================
    // 4) Skin Temperature ON_DEMAND (1분마다 트리거)
    // =========================================================
    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                // ✅ 삼성 예제 스타일 키
                val status: Int? = try { dp.getValue(ValueKey.SkinTemperatureSet.STATUS) } catch (_: Exception) { null }
                val wrist: Float? = try { dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE) } catch (_: Exception) { null }
                val ambient: Float? = try { dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE) } catch (_: Exception) { null }

                Log.d(tag, "🌡️ 피부온도 수신: status=$status wrist=$wrist ambient=$ambient")

                if (wrist != null) {
                    sendToJetsonUDP("SKIN_TEMP", wrist)
                } else {
                    Log.w(tag, "⚠️ OBJECT_TEMPERATURE=null (착용상태/권한/지원/센서조건 가능성)")
                }
            }

            // ON_DEMAND는 1회 받으면 listener 내려주는 게 깔끔 (다음 1분에 다시 트리거)
            mainHandler.post {
                try { skinTempTracker?.unsetEventListener() } catch (_: Exception) {}
            }
        }

        override fun onFlushCompleted() {
            Log.d(tag, "SkinTemp flush completed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(tag, "❌ 피부온도 트래커 에러: $error")
        }
    }

    private fun startSkinTempOnDemandLoop() {
        val tracker = skinTempTracker
        if (tracker == null) {
            Log.w(tag, "⚠️ skinTempTracker=null, 피부온도 ON_DEMAND 루프 시작 불가")
            return
        }

        serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val last = lastSkinTempTriggeredAt.get()

                if (now - last >= skinTempTriggerPeriodMs && lastSkinTempTriggeredAt.compareAndSet(last, now)) {
                    // 트리거(=리스너 등록)는 메인에서
                    mainHandler.post {
                        try {
                            tracker.setEventListener(skinTempListener)
                            Log.d(tag, "🚀 피부온도 ON_DEMAND 트리거(리스너 등록)")
                        } catch (e: Exception) {
                            Log.e(tag, "❌ 피부온도 리스너 등록 실패: ${e.message}", e)
                        }
                    }
                }

                delay(1_000L)
            }
        }
    }

    // =========================================================
    // 5) UDP 전송
    // =========================================================
    private fun sendToJetsonUDP(type: String, value: Float) {
        serviceScope.launch {
            try {
                val timeFormat = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.KOREAN)
                val formattedDate = timeFormat.format(Date())

                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("type", type)
                    put("value", value)
                    put("ts", formattedDate)
                }

                val message = json.toString().toByteArray()
                val address = InetAddress.getByName(jetsonIp)
                val packet = DatagramPacket(message, message.size, address, jetsonPort)

                udpSocket?.send(packet)
                Log.d(tag, "[$formattedDate] [$type] $value 전송 완료")
            } catch (e: Exception) {
                Log.e(tag, "❌ UDP 전송 에러: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 코루틴 종료
        serviceScope.cancel()

        // 리스너 해제
        mainHandler.post {
            try { heartRateTracker?.unsetEventListener() } catch (_: Exception) {}
            try { skinTempTracker?.unsetEventListener() } catch (_: Exception) {}
        }

        // 서비스 연결 해제
        try { healthTrackingService?.disconnectService() } catch (_: Exception) {}

        // UDP 소켓 종료
        try { udpSocket?.close() } catch (_: Exception) {}

        Log.d(tag, "🧹 SensorDataService 종료 완료")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(channelID, "DS", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }
}