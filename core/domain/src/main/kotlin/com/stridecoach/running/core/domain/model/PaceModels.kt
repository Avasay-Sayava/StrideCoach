package com.stridecoach.running.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PaceBounds(
    val fastSecPerKm: Int,
    val slowSecPerKm: Int
) {
    init {
        require(fastSecPerKm > 0) { "fastSecPerKm must be positive" }
        require(slowSecPerKm >= fastSecPerKm) { "slowSecPerKm must be >= fastSecPerKm" }
    }

    fun midpointSecPerKm(): Int = (fastSecPerKm + slowSecPerKm) / 2

    fun evaluate(actualSecPerKm: Int): PaceStatus = when {
        actualSecPerKm <= 0 -> PaceStatus.NO_SIGNAL
        actualSecPerKm < fastSecPerKm -> PaceStatus.TOO_FAST
        actualSecPerKm > slowSecPerKm -> PaceStatus.TOO_SLOW
        else -> PaceStatus.ON_TARGET
    }

    fun formatRange(): String = "${formatPace(fastSecPerKm)}–${formatPace(slowSecPerKm)} /km"
}

enum class PaceStatus {
    TOO_FAST,
    ON_TARGET,
    TOO_SLOW,
    NO_SIGNAL
}

fun formatPace(secPerKm: Int): String {
    if (secPerKm <= 0) return "--:--"
    val mins = secPerKm / 60
    val secs = secPerKm % 60
    return "%d:%02d".format(mins, secs)
}

fun formatDuration(millis: Long): String {
    val totalSec = (millis.coerceAtLeast(0L)) / 1000L
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) {
        "%d:%02d:%02d".format(hrs, mins, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}
