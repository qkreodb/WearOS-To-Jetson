package com.example.dsandroidapp.presentation

// 현재 미사용 — 추후 type=hazard_alert 형식 확장 시 사용
data class HazardAlertCommand(
    val type: String? = null,
    val eventId: Long? = null,
    val level: String? = null,
    val title: String? = null,
    val message: String? = null,
    val color: String? = null,
    val vibration: Boolean? = null,
    val durationMs: Long? = null,
    val resetAfterMs: Long? = null,
)
