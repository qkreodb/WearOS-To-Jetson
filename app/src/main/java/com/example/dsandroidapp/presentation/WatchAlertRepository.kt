package com.example.dsandroidapp.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 워치 경고 화면 상태.
 * MutableStateFlow 대신 Compose의 mutableStateOf 사용
 * → kotlinx.coroutines.flow 의존성 불필요, 별도 CoroutineScope 불필요
 */
object WatchAlertRepository {

    /** null이면 정상 화면, non-null이면 해당 color로 경고 화면 표시 */
    var alertColor: String? by mutableStateOf(null)
        private set

    fun showAlert(color: String) {
        alertColor = color
    }

    fun dismiss() {
        alertColor = null
    }
}
