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

import android.hardware.Sensor
import android.hardware.SensorManager
import android.content.Context
import android.os.PowerManager

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

    // WakeLock: CPU 안꺼지게
    private var wakeLock : PowerManager.WakeLock? = null

    // ====== Jetson UDP ======
    private val jetsonIp = "192.168.0.10"
    private val jetsonPort = 5005
    private var udpSocket: DatagramSocket? = null

    // NULL 방지
    private var currentHR: Float? = null
    private var currentST: Float? = null

    // ====== Periods ======
    private val sendLoopPeriodMs = 5_000L        // 5초 주기 통합 루프에서 사용함
    private val skinTempTriggerPeriodMs = 60_000L     // 피부온도 측정 트리거 주기(ON_DEMAND) (1분 주기)

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

    override fun onCreate() {
        super.onCreate()

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        createNotificationChannel()

        // WakeLock으로 화면이 꺼져도 CPU와 센서 깨워둠
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DS:SensorWakeLock")
        wakeLock?.acquire()

        try {
            udpSocket = DatagramSocket()
        } catch (e: Exception) {
            Log.e(tag, "UDP 소켓 에러", e)
        }

        connectSamsungHealthTrackingService()

        // 통합 전송 루프
        startUnifiedSendLoop()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle("DS 실시간 수집 중")
            .setContentText("심박/피부온도 수집 및 Jetson 전송")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    // 통합 전송: NULL이 아닐 때 5초마다 전송
    private fun startUnifiedSendLoop(){
        serviceScope.launch{
            while(isActive){
                val hr = currentHR
                val st = currentST

                if (hr!=null && st!=null){
                    sendToJetsonUDP(hr, st)
                } else {
                    Log.d(tag, "데이터 대기 중 (심박수: $hr, 피부 온도: $st")
                }
                delay(sendLoopPeriodMs)
            }
        }
    }

    // =========================================================
    // 5) UDP 전송
    // =========================================================
    private fun sendToJetsonUDP(hr: Float, st: Float) {
        serviceScope.launch {
            try {
                val timeFormat = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.KOREAN)
                val formattedDate = timeFormat.format(Date())

                val json = JSONObject().apply {
                    put("sen_id", deviceId)
                    put("wp_id", "1번 작업장")
                    put("sk_temp", st)
                    put("hr", hr)
                    put("time", formattedDate)
                }

                val message = json.toString().toByteArray()
                val packet = DatagramPacket(message, message.size, InetAddress.getByName(jetsonIp), jetsonPort)
                udpSocket?.send(packet)
                Log.d(tag, "[전송 성공] $formattedDate | 심박수: $hr, 피부 온도: $st")
            } catch (e: Exception) {
                Log.e(tag, "[전송 실패]", e)
            }
        }
    }

    // =========================================================
    // 3) Heart Rate listener (5초마다 UDP 송신)
    // =========================================================
    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                try {
                    currentHR = dp.getValue(ValueKey.HeartRateSet.HEART_RATE).toFloat()
                } catch (e: Exception) {Log.e(tag, "심박수 파싱 실패", e)}
            }
        }
        override fun onError(error: HealthTracker.TrackerError){Log.e(tag, "HR 에러: $error")}
        override fun onFlushCompleted() { Log.d(tag, "HR Flush 완료") }

    }

    // =========================================================
    // 4) Skin Temperature ON_DEMAND (1분마다 트리거)
    // =========================================================
    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                try {
                    val wrist = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    if (wrist != null && wrist > 0) {
                        currentST = wrist
                        Log.d(tag, "🌡️ 피부온도 갱신: $wrist")
                    }
                } catch (e: Exception) { Log.e(tag, "ST 파싱 실패", e) }
            }
            mainHandler.post { try {skinTempTracker?.unsetEventListener()} catch(e: Exception) {} }
        }
        override fun onError(error: HealthTracker.TrackerError) { Log.e(tag, "ST 에러: $error") }
        override fun onFlushCompleted() { Log.d(tag, "ST Flush 완료") }
    }


    private fun startHeartRateListening() {
        heartRateTracker?.let { tracker ->
            mainHandler.post {
                try {
                    tracker.setEventListener(heartRateListener)
                    Log.d(tag, "심박 리스닝 시작")
                } catch (e: Exception) { Log.e(tag, "HR 시작 실패", e) }
            }
        }
    }

    private fun startSkinTempOnDemandLoop() {
        serviceScope.launch {
            while (isActive) {
                mainHandler.post {
                    try {
                        skinTempTracker?.setEventListener(skinTempListener)
                        Log.d(tag, "피부온도 측정 트리거")
                    } catch (e: Exception) { Log.e(tag, "ST 트리거 실패", e) }
                }
                delay(skinTempTriggerPeriodMs) // 1분 대기
            }
        }
    }

    // =========================================================
    // 서비스 연결 및 정리 로직
    // =========================================================
    private fun connectSamsungHealthTrackingService() {
        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(tag, "✅ HealthTrackingService 연결 성공")

                val supported = healthTrackingService?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()
                initTrackers(supported)

                startHeartRateListening()
                startSkinTempOnDemandLoop() // 초기 즉시 실행 포함
            }
            override fun onConnectionEnded() { Log.w(tag, "⚠️ 연결 종료") }
            override fun onConnectionFailed(e: HealthTrackerException) { Log.e(tag, "❌ 연결 실패: ${e.message}") }
        }
        healthTrackingService = HealthTrackingService(connectionListener, applicationContext).apply { connectService() }
    }

    private fun initTrackers(supported: List<HealthTrackerType>) {
        if (supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            heartRateTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        }
        if (supported.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            skinTempTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let{if (it.isHeld) it.release()} // wakeLock 해제

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