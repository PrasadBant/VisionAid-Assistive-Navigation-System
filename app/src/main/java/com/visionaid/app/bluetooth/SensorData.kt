package com.visionaid.app.bluetooth

import kotlin.math.abs

data class SensorData(
    val distance: Float,
    val tilt: Boolean,
    val slopeDown: Boolean = false,
    val slopeUp: Boolean = false,
    val tiltLeft: Boolean = false,
    val tiltRight: Boolean = false,
    val shake: Boolean = false,
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f
) {
    val zone: DistanceZone
        get() = when {
            distance < 1f -> DistanceZone.UNKNOWN
            distance < 30f -> DistanceZone.CRITICAL
            distance < 80f -> DistanceZone.CLOSE
            distance < 150f -> DistanceZone.NEAR
            else -> DistanceZone.FAR
        }

    val isObstacleClose: Boolean
        get() = distance in 1f..80f

    // Very large absolute acceleration.
    val isAccelerometerAlert: Boolean
        get() = abs(ax) > 2.0f || abs(ay) > 2.0f || abs(az - 1f) > 2.0f

    // Strong stick direction/angle.
    val isStrongTiltAlert: Boolean
        get() =
            abs(ax) > 0.90f ||
                    abs(ay) > 0.90f ||
                    az < 0.20f ||
                    slopeDown ||
                    slopeUp ||
                    tiltLeft ||
                    tiltRight

    val accelerometerAlertAxis: String
        get() = buildString {
            if (abs(ax) > 2.0f) append("X:${"%.1f".format(ax)}g ")
            if (abs(ay) > 2.0f) append("Y:${"%.1f".format(ay)}g ")
            if (abs(az - 1f) > 2.0f) append("Z:${"%.1f".format(az)}g")
        }.trim()

    val distanceLabel: String
        get() = if (distance < 1f) "-- cm" else "${"%.1f".format(distance)} cm"

    val distanceText: String
        get() = when {
            distance < 1f -> "distance unknown"
            distance < 30f -> "very close, ${distance.toInt()} centimeters"
            distance < 80f -> "close, ${distance.toInt()} centimeters"
            distance < 150f -> "${distance.toInt()} centimeters away"
            else -> "clear"
        }

    val navigationContext: String
        get() = when {
            slopeDown -> "strong downward tilt"
            slopeUp -> "strong upward tilt"
            tiltLeft -> "strong left tilt"
            tiltRight -> "strong right tilt"
            shake -> "rough movement"
            tilt -> "tilt detected"
            else -> "stable"
        }

    val hasOrientationAlert: Boolean
        get() = tilt || slopeDown || slopeUp || tiltLeft || tiltRight || shake

    val proximityLevel: ProximityLevel
        get() = when (zone) {
            DistanceZone.UNKNOWN -> ProximityLevel.UNKNOWN
            DistanceZone.CRITICAL -> ProximityLevel.DANGER
            DistanceZone.CLOSE, DistanceZone.NEAR -> ProximityLevel.CAUTION
            DistanceZone.FAR -> ProximityLevel.SAFE
        }
}

enum class DistanceZone {
    UNKNOWN,
    CRITICAL,
    CLOSE,
    NEAR,
    FAR
}

enum class ProximityLevel {
    UNKNOWN,
    SAFE,
    CAUTION,
    DANGER
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NOT_FOUND,
    PERMISSION_DENIED,
    UNAVAILABLE
}
