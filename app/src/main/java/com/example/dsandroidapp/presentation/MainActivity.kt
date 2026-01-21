package com.example.dsandroidapp.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.example.dsandroidapp.presentation.theme.DSAndroidAppTheme

class MainActivity : ComponentActivity() {

    private val tag = "DS_MAIN"

    /**
     * ✅ 런타임 권한은 "실제로 런타임 권한으로 존재하는 것만" 요청해야 합니다.
     * - BODY_SENSORS: 심박
     * - ACTIVITY_RECOGNITION: 활동(필요 시)
     * - POST_NOTIFICATIONS: Android 13+만 런타임
     *
     * ❌ "android.permission.health.READ_SKIN_TEMPERATURE" 는 여기서 빼세요.
     *   (Samsung Health Sensor SDK는 별도 연결/동의(resolution) 흐름이 있습니다)
     */
    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)

            // Android 13(API 33)+에서만 런타임 알림 권한 존재
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val bodySensorsGranted = results[Manifest.permission.BODY_SENSORS] ?: false
            val activityGranted = results[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
            val notifGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    results[Manifest.permission.POST_NOTIFICATIONS] ?: false
                } else true

            val allGranted = bodySensorsGranted && activityGranted && notifGranted

            if (allGranted) {
                Log.d(tag, "✅ 필수 권한 허용됨 (심박/활동/알림)")
                startSensorService()
            } else {
                Log.e(tag, "❌ 권한 부족: BODY_SENSORS=$bodySensorsGranted, ACTIVITY=$activityGranted, NOTIF=$notifGranted")
                // 필요하면 여기서 UI로 안내 문구 표시
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) 권한 체크 후 서비스 시작
        if (allPermissionsGranted()) {
            Log.d(tag, "✅ 이미 권한 허용됨 → 서비스 시작")
            startSensorService()
        } else {
            Log.d(tag, "ℹ️ 권한 요청 시작")
            requestPermissionLauncher.launch(requiredPermissions)
        }

        // 2) UI
        setContent {
            DSAndroidAppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = "DS 작동 중"
                    )
                }
            }
        }
    }

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    private fun startSensorService() {
        val intent = Intent(this, SensorDataService::class.java)

        // minSdk=30이라 startForegroundService는 안전
        startForegroundService(intent)

        Log.d(tag, "🚀 SensorDataService 시작 요청")
    }
}