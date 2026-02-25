package com.example.dsandroidapp.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.annotation.RequiresPermission

// Samsung Health Tracking
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
    private val watchListenPort = 5006
    // 휴식 상태 플래그
    @Volatile private var isResting = false
    private var restJob: Job? = null // 휴식 타이머 관리를 위한 Job
    private var udpSocket: DatagramSocket? = null

    // ====== WakeLock ======
    private var wakeLock: PowerManager.WakeLock? = null

    // ====== Latest Values (thread-safe-ish: 단순 volatile) ======
    @Volatile private var currentHR: Float? = null
    @Volatile private var currentST: Float? = null

    // ====== Periods ======
    private val sendLoopPeriodMs = 5_000L
    private val skinTempTriggerPeriodMs = 60_000L

    // ====== IDs ======
    private lateinit var deviceId: String

    // ====== Runtime ======
    private val started = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ====== Samsung Health Tracking ======
    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null

    // =========================================================
    // Lifecycle
    // =========================================================
    override fun onCreate() {
        super.onCreate()

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        createNotificationChannel()
        acquireWakeLockSafely()

        try {
            udpSocket = DatagramSocket()
            Log.d(tag, "✅ UDP 소켓 생성 완료")
        } catch (e: Exception) {
            Log.e(tag, "❌ UDP 소켓 생성 실패", e)
        }
    }

    // =========================================================
    // 1) 젯슨 피드백 수신 루프 (JSON 파싱 포함)
    // =========================================================
    private fun startUdpReceiver() {
        serviceScope.launch {
            try {
                val receiveSocket = DatagramSocket(watchListenPort)
                val buffer = ByteArray(1024)
                Log.d(tag, "👂 젯슨 state_code 수신 대기 중 (Port: $watchListenPort)")

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()

                    try {
                        // JSON에서 state_code(VARCHAR2) 추출
                        val json = JSONObject(msg)
                        val stateCode = json.optString("state_code", "")

                        if (stateCode.isNotEmpty()) {
                            handleStatusCode(stateCode)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "JSON 파싱 에러: $msg", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "수신 소켓 에러", e)
            }
        }
    }

    // =========================================================
    // 2) 상태 코드에 따른 휴식 로직 (1:일반, 2:긴급)
    // =========================================================
    private fun handleStatusCode(code: String) {
        if (isResting) return // 이미 휴식 중이면 중복 실행 방지

        val restTimeMinutes = when (code) {
            "1" -> 2L // 일반 휴식 (20분) 테스트를 위해 2분으로 줄여두었음
            "2" -> 15L // 긴급 휴식 (15분)
            else -> return
        }

        executeRestMode(restTimeMinutes)
    }

    private fun executeRestMode(minutes: Long) {
        restJob?.cancel() // 혹시 모를 기존 타이머 취소
        restJob = serviceScope.launch {
            isResting = true

            // [휴식 시작] 알림 및 중단
            vibrateWatch(3) // 진동 알림
            updateNotification("휴식 명령 수신", "${minutes}분간 휴식을 취하세요. 센서가 중단됩니다.")
            stopAllSensors() // 센서 리스너 해제
            Log.d(tag, "💤 휴식 모드 진입: ${minutes}분 (통신 및 센서 중단)")

            // 정해진 시간 동안 대기
            delay(minutes * 60 * 1000L)

            // [휴식 종료] 알림 및 재개
            isResting = false
            vibrateWatch(5) // 종료 알림
            updateNotification("휴식 종료", "다시 업무를 시작하세요. 센서가 가동됩니다.")
            startAllSensors() // 센서 리스너 재등록
            Log.d(tag, "🏃 휴식 종료: 센서 및 통신 재개")
        }
    }

    // 2. 알림 업데이트 메서드 추가
    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification) // startForeground에서 사용한 ID 1과 동일해야 함
    }

    // =========================================================
    // 3) 전송 루프 수정 (휴식 중에는 전송 안 함)
    // =========================================================
    private fun startUnifiedSendLoop() {
        serviceScope.launch {
            while (isActive) {
                if (!isResting) { // 휴식 중이 아닐 때만 동작
                    val hr = currentHR
                    val st = currentST
                    if (hr != null && st != null) {
                        sendToJetsonUDP(hr, st)
                    }
                }
                delay(sendLoopPeriodMs)
            }
        }
    }

    // =========================================================
    // 4) 진동 구현
    // =========================================================
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun vibrateWatch(times: Int) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = LongArray(times * 2) { if (it % 2 == 0) 200L else 500L }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(500)
        }
    }

    private fun startAllSensors() {
        startHeartRateListening()
        // SkinTemp는 OnDemand 루프가 돌고 있으므로 다음 주기에서 자동 재개됨
    }

    // 센서 중단/재개 헬퍼 함수
    private fun stopAllSensors() {
        mainHandler.post {
            try { heartRateTracker?.unsetEventListener() } catch (e: Exception) {}
            try { skinTempTracker?.unsetEventListener() } catch (e: Exception) {}
        }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) startForeground 먼저 (중요)
        startForegroundSafely()

        // 2) 루프/연결 중복 방지 (중요)
        if (started.compareAndSet(false, true)) {
            Log.d(tag, "🚀 Service started (first time)")

            connectSamsungHealthTrackingService()
            startUdpReceiver()
            startUnifiedSendLoop()
        } else {
            Log.d(tag, "ℹ️ onStartCommand called again (already started)")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // 코루틴 종료
        serviceScope.cancel()

        // 리스너 해제(메인)
        mainHandler.post {
            try { heartRateTracker?.unsetEventListener() } catch (_: Exception) {}
            try { skinTempTracker?.unsetEventListener() } catch (_: Exception) {}
        }

        // 서비스 연결 해제
        try { healthTrackingService?.disconnectService() } catch (_: Exception) {}

        // UDP 소켓 종료
        try { udpSocket?.close() } catch (_: Exception) {}

        // WakeLock 해제
        releaseWakeLockSafely()

        Log.d(tag, "🧹 SensorDataService 종료 완료")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================
    // 0) Foreground / WakeLock
    // =========================================================
    private fun startForegroundSafely() {
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
    }

    private fun acquireWakeLockSafely() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DS:SensorWakeLock")

            wakeLock?.acquire()
            Log.d(tag, "✅ WakeLock acquired (10min timeout)")
        } catch (e: Exception) {
            Log.e(tag, "❌ WakeLock acquire 실패", e)
        }
    }

    private fun releaseWakeLockSafely() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            Log.d(tag, "✅ WakeLock released")
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(channelID, "DS", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    // =========================================================
    // 1) Samsung HealthTrackingService 연결
    // =========================================================
    private fun connectSamsungHealthTrackingService() {
        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(tag, "✅ HealthTrackingService 연결 성공")

                val supported = try {
                    healthTrackingService?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()
                } catch (e: Exception) {
                    Log.e(tag, "❌ trackingCapability 조회 실패", e)
                    emptyList()
                }

                initTrackers(supported)
                startHeartRateListening()
                startSkinTempOnDemandLoop()
            }

            override fun onConnectionEnded() {
                Log.w(tag, "⚠️ HealthTrackingService 연결 종료")
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                Log.e(tag, "❌ HealthTrackingService 연결 실패: ${e.message}", e)
            }
        }

        try {
            healthTrackingService = HealthTrackingService(connectionListener, applicationContext)
            healthTrackingService?.connectService()
            Log.d(tag, "🚀 connectService() 호출")
        } catch (e: Exception) {
            Log.e(tag, "❌ HealthTrackingService 생성/연결 실패", e)
        }
    }

    private fun initTrackers(supported: List<HealthTrackerType>) {
        if (supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            try {
                heartRateTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                Log.d(tag, "✅ HEART_RATE_CONTINUOUS tracker 생성 완료")
            } catch (e: Exception) {
                Log.e(tag, "❌ HeartRate tracker 생성 실패", e)
            }
        } else {
            Log.e(tag, "❌ HEART_RATE_CONTINUOUS 미지원")
        }

        if (supported.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            try {
                skinTempTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
                Log.d(tag, "✅ SKIN_TEMPERATURE_ON_DEMAND tracker 생성 완료")
            } catch (e: Exception) {
                Log.e(tag, "❌ SkinTemp tracker 생성 실패", e)
            }
        } else {
            Log.e(tag, "❌ SKIN_TEMPERATURE_ON_DEMAND 미지원")
        }
    }

    // =========================================================
    // 2) Heart Rate listener
    // =========================================================
    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                try {
                    currentHR = dp.getValue(ValueKey.HeartRateSet.HEART_RATE).toFloat()
                } catch (e: Exception) {
                    Log.e(tag, "심박수 파싱 실패", e)
                }
            }
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(tag, "HR 에러: $error")
        }

        override fun onFlushCompleted() {
            Log.d(tag, "HR Flush 완료")
        }
    }

    private fun startHeartRateListening() {
        val tracker = heartRateTracker ?: run {
            Log.w(tag, "⚠️ heartRateTracker=null, HR 리스닝 시작 불가")
            return
        }

        mainHandler.post {
            try {
                tracker.setEventListener(heartRateListener)
                Log.d(tag, "🚀 심박 리스닝 시작")
            } catch (e: Exception) {
                Log.e(tag, "❌ HR 리스닝 시작 실패", e)
            }
        }
    }

    // =========================================================
    // 3) Skin Temperature ON_DEMAND loop
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
                } catch (e: Exception) {
                    Log.e(tag, "ST 파싱 실패", e)
                }
            }

            // ON_DEMAND는 1회 수신 후 리스너 내림 (다음 주기에서 다시 트리거)
            mainHandler.post {
                try { skinTempTracker?.unsetEventListener() } catch (_: Exception) {}
            }
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(tag, "ST 에러: $error")
        }

        override fun onFlushCompleted() {
            Log.d(tag, "ST Flush 완료")
        }
    }

    private fun startSkinTempOnDemandLoop() {
        val tracker = skinTempTracker ?: return
        serviceScope.launch {
            while (isActive) {
                // ✅ isResting이 false(업무 중)일 때만 센서를 작동시킴
                if (!isResting) {
                    mainHandler.post {
                        try {
                            tracker.setEventListener(skinTempListener)
                            Log.d(tag, "🚀 피부온도 ON_DEMAND 트리거")
                        } catch (e: Exception) {
                            Log.e(tag, "❌ ST 트리거 실패", e)
                        }
                    }
                } else {
                    // 휴식 중일 때는 로그만 남기거나 아무것도 안 함
                    Log.d(tag, "휴식 중")
                }
                delay(skinTempTriggerPeriodMs)
            }
        }
    }

    // =========================================================
    // 5) UDP Send (동기 처리: 루프 코루틴에서 호출)
    // =========================================================
    private fun sendToJetsonUDP(hr: Float, st: Float?) {
        val socket = udpSocket ?: return

        val timeFormat = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.KOREAN)
        val formattedDate = timeFormat.format(Date())

        val json = JSONObject().apply {
            put("sen_id", deviceId)
            put("wp_id", "1번 작업장")
            put("hr", hr)
            put("time", formattedDate)

            // ST 정책: null이면 아예 키를 안 넣어서 "NULL 전송 방지"
            if (st != null) put("sk_temp", st)
        }

        val message = json.toString().toByteArray()
        val packet = DatagramPacket(
            message,
            message.size,
            InetAddress.getByName(jetsonIp),
            jetsonPort
        )

        socket.send(packet)
        Log.d(tag, "✅ [전송 성공] $formattedDate | HR=$hr, ST=$st")
    }
}

