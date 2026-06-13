package com.visionaid.app.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticManager(context: Context) {

    enum class HazardStyle {
        NEARBY,
        VEHICLE,
        STAIRS,
        EMERGENCY
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var lastPulseAt = 0L
    private var lastBand = -1
    private var lastStyle = HazardStyle.NEARBY

    fun vibrateObstacle(distanceCm: Float, style: HazardStyle = HazardStyle.NEARBY) {
        if (distanceCm !in 1f..35f) return

        val band = when {
            distanceCm <= 10f -> 5
            distanceCm <= 20f -> 4
            distanceCm <= 30f -> 3
            else -> 1
        }

        val now = System.currentTimeMillis()

        val minGap = when (band) {
            5 -> 180L
            4 -> 260L
            3 -> 360L
            else -> 650L
        }

        if (band == lastBand && style == lastStyle && now - lastPulseAt < minGap) return

        lastPulseAt = now
        lastBand = band
        lastStyle = style

        if (band == 5) {
            vibrateOneShot(420, 255)
            return
        }

        val intensity = when (style) {
            HazardStyle.EMERGENCY -> 255
            HazardStyle.VEHICLE -> 240
            HazardStyle.STAIRS -> 230
            HazardStyle.NEARBY -> (90 + band * 30).coerceAtMost(220)
        }

        val pattern = when (style) {
            HazardStyle.VEHICLE -> longArrayOf(0, 90, 55, 90, 55, 90)
            HazardStyle.STAIRS -> longArrayOf(0, 180, 80, 180)
            HazardStyle.EMERGENCY -> longArrayOf(0, 350, 90, 350, 90, 350)
            HazardStyle.NEARBY -> pulsePatternForBand(band)
        }

        vibrate(pattern, intensity)
    }

    fun vibrateEmergency() {
        vibrate(longArrayOf(0, 450, 90, 450, 90, 650), 255)
    }

    fun vibrateClick() {
        vibrateOneShot(50, 160)
    }

    fun stopAll() {
        vibrator.cancel()
        lastPulseAt = 0L
        lastBand = -1
        lastStyle = HazardStyle.NEARBY
    }

    private fun pulsePatternForBand(band: Int): LongArray = when (band) {
        4 -> longArrayOf(0, 110, 70, 110, 70, 110)
        3 -> longArrayOf(0, 120, 120, 120)
        else -> longArrayOf(0, 160, 360)
    }

    private fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    durationMs,
                    amplitude.coerceIn(1, 255)
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun vibrate(pattern: LongArray, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = IntArray(pattern.size) { index ->
                if (index % 2 == 0) 0 else amplitude.coerceIn(1, 255)
            }

            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
