package com.visionaid.app.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.visionaid.app.bluetooth.SensorData
import com.visionaid.app.haptic.HapticManager
import com.visionaid.app.tts.TextToSpeechManager
import kotlin.math.sqrt

class AlertManager(
    private val context: Context,
    private val haptic: HapticManager,
    private val tts: TextToSpeechManager
) {
    companion object {
        private const val TAG = "AlertManager"
        private const val GUARDIAN_PHONE_NUMBER = "9731724732"

        private const val OBSTACLE_VIBRATION_THRESHOLD_CM = 35f
        private const val OBSTACLE_SPEECH_THRESHOLD_CM = 22f
        private const val OBSTACLE_ALERT_COOLDOWN_MS = 6000L

        private const val GUARDIAN_CALL_COOLDOWN_MS = 60000L
        private const val SUDDEN_JERK_THRESHOLD_G = 1.25f
        private const val MAX_JERK_TIME_GAP_MS = 800L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastObstacleAlertTime = 0L
    private var lastGuardianCallTime = 0L
    private var lastObstacleDistance = Float.MAX_VALUE
    private var callScheduled = false

    private var hasPreviousAccelerometer = false
    private var previousAx = 0f
    private var previousAy = 0f
    private var previousAz = 0f
    private var previousAccelerometerTime = 0L

    fun evaluate(data: SensorData, spokenObjectLabel: String?) {
        val now = System.currentTimeMillis()

        evaluateObstacle(data, spokenObjectLabel, now)
        evaluateSuddenStickMovement(data, now)
    }

    fun reset() {
        lastObstacleAlertTime = 0L
        lastObstacleDistance = Float.MAX_VALUE

        hasPreviousAccelerometer = false
        previousAx = 0f
        previousAy = 0f
        previousAz = 0f
        previousAccelerometerTime = 0L

        callScheduled = false
    }

    private fun evaluateObstacle(data: SensorData, spokenObjectLabel: String?, now: Long) {
        val obstacleNear = data.distance in 1f..OBSTACLE_VIBRATION_THRESHOLD_CM

        if (obstacleNear) {
            haptic.vibrateObstacle(data.distance, hazardStyleFor(spokenObjectLabel))
        }

        val dangerIncreased = data.distance < lastObstacleDistance - 10f

        val shouldSpeak = data.distance in 1f..OBSTACLE_SPEECH_THRESHOLD_CM &&
                (now - lastObstacleAlertTime > OBSTACLE_ALERT_COOLDOWN_MS || dangerIncreased)

        if (shouldSpeak) {
            lastObstacleAlertTime = now
            lastObstacleDistance = data.distance

            val label = spokenObjectLabel?.takeIf { it.isNotBlank() } ?: "obstacle"

            val message = when {
                data.distance <= 10f -> "Stop. $label very close."
                data.distance <= 18f -> "Warning. $label ahead."
                else -> "Careful. $label ahead."
            }

            tts.speak(message, TextToSpeechManager.Priority.IMPORTANT)
            Log.d(TAG, message)
        }

        if (!obstacleNear) {
            lastObstacleDistance = Float.MAX_VALUE
        }
    }

    private fun hazardStyleFor(label: String?): HapticManager.HazardStyle {
        val normalized = label?.lowercase().orEmpty()

        return when {
            normalized in setOf("car", "truck", "bus", "motorcycle", "bicycle", "train", "vehicle") ->
                HapticManager.HazardStyle.VEHICLE

            normalized.contains("stair") || normalized.contains("hole") ->
                HapticManager.HazardStyle.STAIRS

            else ->
                HapticManager.HazardStyle.NEARBY
        }
    }

    private fun evaluateSuddenStickMovement(data: SensorData, now: Long) {
        if (!hasPreviousAccelerometer) {
            savePreviousAccelerometer(data, now)
            return
        }

        val timeGap = now - previousAccelerometerTime

        if (timeGap > MAX_JERK_TIME_GAP_MS) {
            savePreviousAccelerometer(data, now)
            return
        }

        val dx = data.ax - previousAx
        val dy = data.ay - previousAy
        val dz = data.az - previousAz

        val jerkMagnitude = sqrt(dx * dx + dy * dy + dz * dz)
        val suddenJerk = jerkMagnitude >= SUDDEN_JERK_THRESHOLD_G || data.isAccelerometerAlert

        savePreviousAccelerometer(data, now)

        val cooldownFinished = now - lastGuardianCallTime >= GUARDIAN_CALL_COOLDOWN_MS

        if (!suddenJerk || !cooldownFinished || callScheduled) return

        callScheduled = true
        lastGuardianCallTime = now

        haptic.vibrateEmergency()

        tts.speak(
            "Emergency detected. Calling guardian now.",
            TextToSpeechManager.Priority.EMERGENCY
        )

        mainHandler.postDelayed({
            callGuardian()
            callScheduled = false
        }, 900L)
    }

    private fun savePreviousAccelerometer(data: SensorData, now: Long) {
        hasPreviousAccelerometer = true
        previousAx = data.ax
        previousAy = data.ay
        previousAz = data.az
        previousAccelerometerTime = now
    }

    private fun callGuardian() {
        val uri = Uri.parse("tel:$GUARDIAN_PHONE_NUMBER")

        val canDirectCall = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(
            if (canDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            uri
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            Log.d(TAG, "Guardian call started")
        } catch (e: Exception) {
            Log.e(TAG, "Guardian call failed: ${e.message}")
        }
    }
}
