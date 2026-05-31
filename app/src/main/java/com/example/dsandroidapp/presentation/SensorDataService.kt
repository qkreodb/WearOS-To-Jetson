package com.example.dsandroidapp.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.math.abs

class SensorDataService : Service() {

    private val tag = "DS_SERVICE"
    private val channelID = "SensorServiceChannel"

    // =========================================================
    // Jetson MQTT Broker 설정
    // =========================================================
    // TODO: Jetson IP로 변경하세요.
    // 예: "192.168.0.64"
    private val mqttBrokerHost = "192.168.0.66"
    private val mqttBrokerPort = 1883

    private var mqttClient: MqttAsyncClient? = null

    @Volatile
    private var mqttConnected = false

    @Volatile
    private var isRegistered = false

    // =========================================================
    // mDNS 설정
    // =========================================================
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    // Jetson zeroconf 쪽: _onsafe-sensor._tcp.local.
    // Android NSD 쪽: .local. 없이 _onsafe-sensor._tcp.
    private val mdnsServiceType = "_onsafe-sensor._tcp."

    // Android NSD는 port 값이 필요함.
    // 실제 서버를 열지 않아도 식별용 포트로 사용 가능.
    private val mdnsServicePort = 5006

    // =========================================================
    // MQTT Topic
    // Jetson MqttSensorService 기준:
    // - sensors/+/status 구독
    // - sensors/+/telemetry 구독
    // - sensors/{sensor_id}/cmd 로 명령 publish
    // - sensors/{sensor_id}/alert 로 알림 publish
    // =========================================================
    private lateinit var sensorPublicId: String
    private lateinit var mqttBase: String
    private lateinit var statusTopic: String
    private lateinit var telemetryTopic: String
    private lateinit var cmdTopic: String
    private lateinit var alertTopic: String

    // =========================================================
    // WakeLock
    // =========================================================
    private var wakeLock: PowerManager.WakeLock? = null

    // =========================================================
    // Latest HR
    // =========================================================
    @Volatile
    private var currentHR: Float? = null

    // =========================================================
    // Periods
    // =========================================================
    private var sendLoopPeriodMs = 5_000L
    private val statusLoopPeriodMs = 10_000L

    // =========================================================
    // IDs
    // =========================================================
    private lateinit var deviceId: String
    private var sensorNumericId: Int = 0

    // =========================================================
    // Runtime
    // =========================================================
    private val started = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // =========================================================
    // Rest / Alert
    // =========================================================
    @Volatile
    private var isResting = false

    private var restJob: Job? = null

    // =========================================================
    // Samsung Health Tracking
    // =========================================================
    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null

    // =========================================================
    // Lifecycle
    // =========================================================
    override fun onCreate() {
        super.onCreate()

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        val crc = CRC32()
        val maxSenId = 20000
        crc.update(deviceId.toByteArray())
        sensorNumericId = (abs(crc.value.toLong()) % (maxSenId + 1)).toInt()

        sensorPublicId = "watch-$sensorNumericId"

        mqttBase = "sensors/$sensorPublicId"
        statusTopic = "$mqttBase/status"
        telemetryTopic = "$mqttBase/telemetry"
        cmdTopic = "$mqttBase/cmd"
        alertTopic = "$mqttBase/alert"

        createNotificationChannel()
        acquireWakeLockSafely()

        Log.d(tag, "sensorPublicId=$sensorPublicId")
        Log.d(tag, "mqttBase=$mqttBase")
        Log.d(tag, "statusTopic=$statusTopic")
        Log.d(tag, "telemetryTopic=$telemetryTopic")
        Log.d(tag, "cmdTopic=$cmdTopic")
        Log.d(tag, "alertTopic=$alertTopic")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        if (started.compareAndSet(false, true)) {
            Log.d(tag, "Service started first time")

            startMdnsAdvertising()
            connectMqtt()
            connectSamsungHealthTrackingService()
            startStatusLoop()
            startTelemetryLoop()
        } else {
            Log.d(tag, "onStartCommand called again")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        serviceScope.cancel()

        mainHandler.post {
            try {
                heartRateTracker?.unsetEventListener()
            } catch (_: Exception) {
            }
        }

        try {
            healthTrackingService?.disconnectService()
        } catch (_: Exception) {
        }

        disconnectMqtt()
        stopMdnsAdvertising()
        releaseWakeLockSafely()

        Log.d(tag, "SensorDataService 종료 완료")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================
    // Foreground / Notification / WakeLock
    // =========================================================
    private fun startForegroundSafely() {
        val notification: Notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle("OnSafe 심박 밴드")
            .setContentText("mDNS 광고 및 MQTT 연결 준비 중")
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

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    private fun acquireWakeLockSafely() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DS:HeartBandWakeLock")
            wakeLock?.acquire()
            Log.d(tag, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(tag, "WakeLock acquire 실패", e)
        }
    }

    private fun releaseWakeLockSafely() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            Log.d(tag, "WakeLock released")
        } catch (_: Exception) {
        }

        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelID,
                "OnSafe Heart Band",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    // =========================================================
    // mDNS Advertising
    // =========================================================
    private fun startMdnsAdvertising() {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "OnSafe-HR-$sensorPublicId"
                serviceType = mdnsServiceType
                port = mdnsServicePort

                setAttribute("sensor_id", sensorPublicId)
                setAttribute("sensor_type", "heart_band")
                setAttribute("sen_name", "Galaxy Watch Heart Band")
                setAttribute("sen_locate", "worker_wrist")
                setAttribute("model", "Galaxy Watch")
                setAttribute("mqtt_base", mqttBase)
            }

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "mDNS 등록 성공: ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "mDNS 등록 실패: errorCode=$errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "mDNS 해제 성공: ${serviceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "mDNS 해제 실패: errorCode=$errorCode")
                }
            }

            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                nsdRegistrationListener
            )

            Log.d(tag, "mDNS 광고 시작 요청: type=$mdnsServiceType, sensor_id=$sensorPublicId")

        } catch (e: Exception) {
            Log.e(tag, "mDNS 광고 시작 실패", e)
        }
    }

    private fun stopMdnsAdvertising() {
        try {
            val manager = nsdManager
            val listener = nsdRegistrationListener

            if (manager != null && listener != null) {
                manager.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.e(tag, "mDNS 해제 실패", e)
        } finally {
            nsdRegistrationListener = null
            nsdManager = null
        }
    }

    // =========================================================
    // MQTT
    // =========================================================
    private fun connectMqtt() {
        serviceScope.launch {
            try {
                val serverUri = "tcp://$mqttBrokerHost:$mqttBrokerPort"
                val clientId = "onsafe-$sensorPublicId"

                mqttClient = MqttAsyncClient(serverUri, clientId, null)

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        mqttConnected = false
                        Log.e(tag, "MQTT 연결 끊김", cause)
                        updateNotification("MQTT 연결 끊김", "Jetson MQTT Broker와 연결이 끊겼습니다.")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
                        Log.d(tag, "MQTT 수신 topic=$topic payload=$payload")

                        when (topic) {
                            cmdTopic -> handleCmdMessage(payload)
                            alertTopic -> handleAlertMessage(payload)
                            else -> Log.d(tag, "처리하지 않는 topic=$topic")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    }
                })

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }

                mqttClient?.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        mqttConnected = true
                        Log.d(tag, "MQTT 연결 성공: $serverUri")

                        subscribeCmdTopic()
                        subscribeAlertTopic()

                        publishStatus("online")

                        updateNotification(
                            "OnSafe 심박 밴드 대기 중",
                            "등록 명령 수신 후 심박 전송을 시작합니다."
                        )
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        mqttConnected = false
                        Log.e(tag, "MQTT 연결 실패", exception)
                        updateNotification("MQTT 연결 실패", "Jetson MQTT Broker에 연결하지 못했습니다.")
                    }
                })

            } catch (e: Exception) {
                mqttConnected = false
                Log.e(tag, "MQTT 초기화 실패", e)
            }
        }
    }

    private fun subscribeCmdTopic() {
        try {
            mqttClient?.subscribe(cmdTopic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(tag, "cmd topic 구독 성공: $cmdTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(tag, "cmd topic 구독 실패", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "cmd topic 구독 예외", e)
        }
    }

    private fun subscribeAlertTopic() {
        try {
            mqttClient?.subscribe(alertTopic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(tag, "alert topic 구독 성공: $alertTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(tag, "alert topic 구독 실패", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "alert topic 구독 예외", e)
        }
    }

    private fun disconnectMqtt() {
        try {
            if (mqttConnected) {
                publishStatus("offline")
            }

            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {
        } finally {
            mqttConnected = false
            mqttClient = null
        }
    }

    // =========================================================
    // MQTT Command 처리
    // Jetson MqttSensorService 기준:
    // publish_register:
    // {
    //   "cmd": "register",
    //   "site_id": "...",
    //   "interval_ms": 5000
    // }
    //
    // publish_unregister:
    // {
    //   "cmd": "unregister"
    // }
    //
    // publish_set_interval:
    // {
    //   "cmd": "set_interval",
    //   "interval_ms": 5000
    // }
    // =========================================================
    private fun handleCmdMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val cmd = json.optString("cmd", "")

            when (cmd) {
                "register" -> {
                    isRegistered = true

                    val intervalMs = json.optLong("interval_ms", sendLoopPeriodMs)
                    if (intervalMs >= 1000L) {
                        sendLoopPeriodMs = intervalMs
                    }

                    publishStatus("registered")

                    updateNotification(
                        "센서 등록 완료",
                        "심박 전송 시작, 주기=${sendLoopPeriodMs}ms"
                    )

                    Log.d(tag, "register 명령 처리 완료: interval_ms=$sendLoopPeriodMs")
                }

                "unregister" -> {
                    isRegistered = false
                    publishStatus("unregistered")

                    updateNotification(
                        "센서 등록 해제",
                        "심박 전송을 중단합니다."
                    )

                    Log.d(tag, "unregister 명령 처리 완료")
                }

                "set_interval" -> {
                    val intervalMs = json.optLong("interval_ms", sendLoopPeriodMs)
                    if (intervalMs >= 1000L) {
                        sendLoopPeriodMs = intervalMs
                        publishStatus("interval_updated")
                        Log.d(tag, "set_interval 처리 완료: interval_ms=$sendLoopPeriodMs")
                    } else {
                        Log.w(tag, "set_interval 무시: interval_ms=$intervalMs")
                    }
                }

                "REST_START" -> {
                    val durationMs = json.optLong("duration_ms", 60_000L)
                    executeRestMode(durationMs)
                }

                "EMERGENCY_START" -> {
                    val durationMs = json.optLong("duration_ms", 120_000L)
                    executeRestMode(durationMs)
                }

                else -> {
                    Log.w(tag, "알 수 없는 cmd=$cmd, payload=$payload")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "cmd 메시지 파싱 실패: $payload", e)
        }
    }

    // =========================================================
    // MQTT Alert 처리 (원본 유지 + 화면 표시 추가)
    // =========================================================
    private fun handleAlertMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val command = json.optString("command", "")

            if (command == "alert_on") {
                val vibration = json.optBoolean("vibration", true)
                val durationMs = json.optLong("duration_ms", 5000L)
                val resetAfterMs = json.optLong("reset_after_ms", 10000L)
                val color = json.optString("color", "red")

                Log.d(
                    tag,
                    "alert_on 수신: color=$color, vibration=$vibration, durationMs=$durationMs, resetAfterMs=$resetAfterMs"
                )

                if (vibration) {
                    vibrateWatchByDuration(durationMs)
                }

                // 화면 경고 표시 (mainHandler로 Compose state 업데이트)
                mainHandler.post { WatchAlertRepository.showAlert(color) }
                mainHandler.postDelayed({ WatchAlertRepository.dismiss() }, resetAfterMs)

                updateNotification("안전 알림", "위험 신호 수신: $color")
            } else {
                Log.w(tag, "알 수 없는 alert command=$command")
            }

        } catch (e: Exception) {
            Log.e(tag, "alert 메시지 파싱 실패: $payload", e)
        }
    }

    // =========================================================
    // Status / Telemetry Publish
    // =========================================================
    private fun startStatusLoop() {
        serviceScope.launch {
            while (isActive) {
                publishStatus(
                    if (isRegistered) {
                        "registered"
                    } else {
                        "online"
                    }
                )

                delay(statusLoopPeriodMs)
            }
        }
    }

    private fun startTelemetryLoop() {
        serviceScope.launch {
            while (isActive) {
                val hr = currentHR

                if (hr != null) {
                    publishHeartRate(hr)
                } else {
                    Log.d(tag, "아직 심박값 없음")
                }

                delay(sendLoopPeriodMs)
            }
        }
    }

    private fun publishStatus(status: String) {
        if (!mqttConnected) {
            return
        }

        try {
            val json = JSONObject().apply {
                put("sensor_id", sensorPublicId)
                put("sensor_type", "heart_band")
                put("status", status)
                put("registered", isRegistered)
                put("is_resting", isResting)
                put("time", nowString())
            }

            val message = MqttMessage(json.toString().toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(statusTopic, message)
            Log.d(tag, "status publish topic=$statusTopic payload=$json")

        } catch (e: Exception) {
            Log.e(tag, "status publish 실패", e)
        }
    }

    private fun publishHeartRate(hr: Float) {
        if (!mqttConnected) {
            Log.w(tag, "MQTT 미연결 상태라 telemetry 생략")
            return
        }

        if (!isRegistered) {
            Log.d(tag, "미등록 상태라 telemetry 생략")
            return
        }

        if (isResting) {
            Log.d(tag, "휴식 중이라 telemetry 생략")
            return
        }

        if (hr <= 0) {
            Log.d(tag, "유효하지 않은 HR=$hr")
            return
        }

        try {
            val json = JSONObject().apply {
                put("sensor_id", sensorPublicId)
                put("sensor_type", "heart_band")
                put("hr", hr)
                put("time", nowString())
            }

            val message = MqttMessage(json.toString().toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(telemetryTopic, message)

            Log.d(tag, "heart telemetry publish topic=$telemetryTopic payload=$json")

        } catch (e: Exception) {
            Log.e(tag, "heart telemetry publish 실패", e)
        }
    }

    private fun nowString(): String {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        return timeFormat.format(Date())
    }

    // =========================================================
    // Samsung HealthTrackingService
    // =========================================================
    private fun connectSamsungHealthTrackingService() {
        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(tag, "HealthTrackingService 연결 성공")

                val supported = try {
                    healthTrackingService?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()
                } catch (e: Exception) {
                    Log.e(tag, "trackingCapability 조회 실패", e)
                    emptyList()
                }

                initTrackers(supported)
                startHeartRateListening()
            }

            override fun onConnectionEnded() {
                Log.w(tag, "HealthTrackingService 연결 종료")
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                Log.e(tag, "HealthTrackingService 연결 실패: ${e.message}", e)
            }
        }

        try {
            healthTrackingService = HealthTrackingService(connectionListener, applicationContext)
            healthTrackingService?.connectService()
            Log.d(tag, "connectService() 호출")
        } catch (e: Exception) {
            Log.e(tag, "HealthTrackingService 생성/연결 실패", e)
        }
    }

    private fun initTrackers(supported: List<HealthTrackerType>) {
        if (supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
            try {
                heartRateTracker =
                    healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)

                Log.d(tag, "HEART_RATE_CONTINUOUS tracker 생성 완료")
            } catch (e: Exception) {
                Log.e(tag, "HeartRate tracker 생성 실패", e)
            }
        } else {
            Log.e(tag, "HEART_RATE_CONTINUOUS 미지원")
        }
    }

    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                try {
                    val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE).toFloat()

                    if (hr > 0) {
                        currentHR = hr
                        Log.d(tag, "심박 갱신: $hr")
                    }
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
            Log.w(tag, "heartRateTracker=null, HR 리스닝 시작 불가")
            return
        }

        mainHandler.post {
            try {
                tracker.setEventListener(heartRateListener)
                Log.d(tag, "심박 리스닝 시작")
            } catch (e: Exception) {
                Log.e(tag, "HR 리스닝 시작 실패", e)
            }
        }
    }

    private fun stopHeartRateListening() {
        mainHandler.post {
            try {
                heartRateTracker?.unsetEventListener()
                Log.d(tag, "심박 리스닝 중단")
            } catch (e: Exception) {
                Log.e(tag, "심박 리스닝 중단 실패", e)
            }
        }
    }

    // =========================================================
    // Rest / Vibration
    // =========================================================
    private fun executeRestMode(durationMs: Long) {
        if (isResting) {
            return
        }

        restJob?.cancel()
        restJob = serviceScope.launch {
            isResting = true

            vibrateWatch(3)
            publishStatus("resting")

            updateNotification(
                "휴식 명령 수신",
                "${durationMs / 1000}초 동안 휴식을 취하세요. 심박 전송을 중단합니다."
            )

            stopHeartRateListening()

            Log.d(tag, "휴식 모드 진입: durationMs=$durationMs")

            delay(durationMs)

            isResting = false
            startHeartRateListening()

            vibrateWatch(5)
            publishStatus("registered")

            updateNotification(
                "휴식 종료",
                "심박 측정 및 전송을 재개합니다."
            )

            Log.d(tag, "휴식 종료")
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun vibrateWatch(times: Int) {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = LongArray(times * 2) {
                    if (it % 2 == 0) 200L else 500L
                }
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                vibrator.vibrate(500)
            }
        } catch (e: Exception) {
            Log.e(tag, "진동 실패", e)
        }
    }

    private fun vibrateWatchByDuration(durationMs: Long) {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val safeDuration = durationMs.coerceIn(200L, 10_000L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        safeDuration,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                vibrator.vibrate(safeDuration)
            }
        } catch (e: Exception) {
            Log.e(tag, "알림 진동 실패", e)
        }
    }
}