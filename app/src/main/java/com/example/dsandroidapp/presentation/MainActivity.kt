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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.dsandroidapp.presentation.theme.DSAndroidAppTheme

class MainActivity : ComponentActivity() {

    private val tag = "DS_MAIN"

    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val bodySensorsGranted = results[Manifest.permission.BODY_SENSORS] ?: false
            val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                results[Manifest.permission.POST_NOTIFICATIONS] ?: false
            } else { true }

            if (bodySensorsGranted && notifGranted) {
                Log.d(tag, "필수 권한 허용됨")
                startSensorService()
            } else {
                Log.e(tag, "권한 부족: BODY_SENSORS=$bodySensorsGranted NOTIF=$notifGranted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (allPermissionsGranted()) {
            Log.d(tag, "이미 권한 허용됨 → 서비스 시작")
            startSensorService()
        } else {
            Log.d(tag, "권한 요청 시작")
            requestPermissionLauncher.launch(requiredPermissions)
        }

        setContent {
            DSAndroidAppTheme {
                // WatchAlertRepository.alertColor는 mutableStateOf → Compose가 자동 감지
                val alertColor = WatchAlertRepository.alertColor

                if (alertColor != null) {
                    HazardAlertScreen(
                        color = alertColor,
                        onDismiss = { WatchAlertRepository.dismiss() },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            text = "OnSafe 심박 밴드 작동 중",
                        )
                    }
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
        startForegroundService(intent)
        Log.d(tag, "SensorDataService 시작 요청")
    }
}
