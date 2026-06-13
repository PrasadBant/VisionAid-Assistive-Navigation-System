package com.visionaid.app.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.visionaid.app.bluetooth.ConnectionStatus
import com.visionaid.app.bluetooth.SensorData
import com.visionaid.app.bluetooth.WiFiSensorManager
import com.visionaid.app.haptic.HapticManager
import com.visionaid.app.ml.DetectionResult
import com.visionaid.app.repository.DetectionRepository
import com.visionaid.app.tts.TextToSpeechManager
import com.visionaid.app.utils.AlertManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class DetectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DetectionViewModel"

        private const val SENSOR_OBSTACLE_CM = 35f
        private const val DANGER_DISTANCE_CM = 25f

        private const val FRAME_INTERVAL_MS = 220L
        private const val MIN_CONFIDENCE = 0.55f

        private const val CLEAR_PATH_COOLDOWN_MS = 60000L
        private const val NORMAL_OBJECT_COOLDOWN_MS = 25000L
        private const val SAME_OBJECT_COOLDOWN_MS = 45000L
        private const val IMPORTANT_OBJECT_COOLDOWN_MS = 7000L

        private val VEHICLES = setOf(
            "bicycle",
            "car",
            "motorcycle",
            "bus",
            "train",
            "truck"
        )

        private val PEOPLE = setOf("person")

        private val LARGE_OBSTACLES = setOf(
            "chair",
            "couch",
            "bed",
            "dining table",
            "bench",
            "potted plant",
            "suitcase",
            "backpack",
            "handbag",
            "umbrella"
        )

        private val FLOOR_OBJECTS = setOf(
            "bottle",
            "cup",
            "bowl",
            "book",
            "cell phone",
            "remote",
            "vase",
            "scissors"
        )

        private val IMPORTANT_OBJECTS = VEHICLES + PEOPLE + LARGE_OBSTACLES + FLOOR_OBJECTS + setOf(
            "traffic light",
            "fire hydrant",
            "stop sign",
            "dog",
            "cat",
            "toilet",
            "tv",
            "laptop",
            "keyboard",
            "microwave",
            "oven",
            "toaster",
            "sink",
            "refrigerator"
        )
    }

    private val repository = DetectionRepository(application)

    private val _detections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val detections: StateFlow<List<DetectionResult>> = _detections

    private val _isDetectionActive = MutableStateFlow(false)
    val isDetectionActive: StateFlow<Boolean> = _isDetectionActive

    private val _modelStatus = MutableStateFlow(ModelStatus.LOADING)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private var wifiSensor: WiFiSensorManager? = null
    private var sensorJob: Job? = null

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val haptic = HapticManager(application)
    private val tts = TextToSpeechManager(application)
    private val alertManager = AlertManager(application, haptic, tts)

    private var lastInferenceAt = 0L
    private var lastGuidanceAt = 0L
    private var lastClearPathAt = 0L
    private var lastGuidanceKey = ""
    private var lastDangerScore = 0

    private val lastSpokenByObject = mutableMapOf<String, SpokenMemory>()

    init {
        viewModelScope.launch {
            val ok = repository.initialize()
            _modelStatus.value = if (ok) ModelStatus.READY else ModelStatus.ERROR
            _isDetectionActive.value = false
            Log.i(TAG, "Model init: $ok")
        }
    }

    fun toggleDetection() {
        if (_modelStatus.value != ModelStatus.READY) {
            tts.speak("AI model is still loading.", TextToSpeechManager.Priority.NORMAL)
            return
        }

        if (_isDetectionActive.value) {
            stopGuidance()
        } else {
            startGuidance()
        }
    }

    private fun startGuidance() {
        _isDetectionActive.value = true
        _detections.value = emptyList()

        resetSpeechMemory()
        alertManager.reset()
        haptic.stopAll()

        startSensorPolling()

        tts.speak(
            "Guidance started. Hold the stick forward.",
            TextToSpeechManager.Priority.NORMAL
        )
    }

    private fun stopGuidance() {
        _isDetectionActive.value = false

        _detections.value = emptyList()
        _sensorData.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED

        sensorJob?.cancel()
        sensorJob = null

        wifiSensor?.stop()
        wifiSensor = null

        resetSpeechMemory()
        alertManager.reset()

        haptic.stopAll()
        tts.stop()

        tts.speak("Guidance stopped.", TextToSpeechManager.Priority.NORMAL)
    }

    private fun startSensorPolling() {
        sensorJob?.cancel()

        val sensor = WiFiSensorManager()
        wifiSensor = sensor

        sensorJob = viewModelScope.launch {
            launch {
                sensor.sensorData.collectLatest { data ->
                    if (!_isDetectionActive.value) return@collectLatest

                    _sensorData.value = data

                    if (data != null) {
                        val spokenLabel = getSpeakableLabel(_detections.value.firstOrNull())
                        alertManager.evaluate(data, spokenLabel)
                    }
                }
            }

            launch {
                sensor.connectionStatus.collectLatest { status ->
                    if (_isDetectionActive.value) {
                        _connectionStatus.value = status
                    }
                }
            }

            sensor.startPolling()
        }
    }

    suspend fun processFrame(bitmap: Bitmap) {
        if (!_isDetectionActive.value || _modelStatus.value != ModelStatus.READY) return

        val now = System.currentTimeMillis()

        if (now - lastInferenceAt < FRAME_INTERVAL_MS) return

        lastInferenceAt = now

        val results = repository.detectObjects(bitmap)
        _detections.value = results

        announceBestGuidance(results)
    }

    private fun announceBestGuidance(results: List<DetectionResult>) {
        if (!_isDetectionActive.value) return

        val sensor = _sensorData.value

        val candidates = results
            .asSequence()
            .filter { it.confidence >= MIN_CONFIDENCE }
            .mapNotNull { result ->
                val label = getSpeakableLabel(result) ?: return@mapNotNull null

                GuidanceCandidate(
                    result = result,
                    label = label,
                    direction = directionFor(result),
                    distanceCm = sensor?.distance?.takeIf { it > 0f && it < 300f },
                    dangerScore = dangerScore(result, label, sensor)
                )
            }
            .sortedWith(
                compareByDescending<GuidanceCandidate> { it.dangerScore }
                    .thenByDescending { it.result.confidence }
            )
            .toList()

        val best = candidates.firstOrNull()

        if (best == null) {
            maybeSpeakClearPath(sensor)
            return
        }

        val now = System.currentTimeMillis()
        val key = "${best.label}:${best.direction}"
        val memory = lastSpokenByObject[key]

        val important = best.dangerScore >= 75
        val critical = best.dangerScore >= 90

        val cooldown = when {
            critical -> IMPORTANT_OBJECT_COOLDOWN_MS
            important -> IMPORTANT_OBJECT_COOLDOWN_MS
            key == lastGuidanceKey -> SAME_OBJECT_COOLDOWN_MS
            else -> NORMAL_OBJECT_COOLDOWN_MS
        }

        val dangerIncreased = best.dangerScore >= lastDangerScore + 20

        val distanceChanged = memory?.distanceCm?.let { old ->
            val current = best.distanceCm
            current != null && abs(old - current) >= 15f
        } ?: true

        if (
            memory != null &&
            now - memory.spokenAt < cooldown &&
            !dangerIncreased &&
            !distanceChanged
        ) {
            return
        }

        if (now - lastGuidanceAt < 2500L && !critical) return

        val priority = when {
            critical -> TextToSpeechManager.Priority.IMPORTANT
            important -> TextToSpeechManager.Priority.IMPORTANT
            else -> TextToSpeechManager.Priority.LOW
        }

        tts.speak(buildGuidancePhrase(best), priority)

        lastGuidanceAt = now
        lastGuidanceKey = key
        lastDangerScore = best.dangerScore
        lastSpokenByObject[key] = SpokenMemory(now, best.distanceCm, best.dangerScore)
    }

    private fun maybeSpeakClearPath(sensor: SensorData?) {
        if (!_isDetectionActive.value) return

        val now = System.currentTimeMillis()
        val distance = sensor?.distance ?: 999f

        if (distance in 1f..120f) return
        if (now - lastClearPathAt < CLEAR_PATH_COOLDOWN_MS) return
        if (now - lastGuidanceAt < 8000L) return

        lastClearPathAt = now
        lastGuidanceAt = now

        tts.speak("Path clear.", TextToSpeechManager.Priority.LOW)
    }

    private fun buildGuidancePhrase(candidate: GuidanceCandidate): String {
        val label = displayLabel(candidate.label)
        val direction = candidate.direction
        val distance = candidate.distanceCm

        val critical = distance != null && distance <= 12f
        val veryClose = distance != null && distance <= 18f
        val close = distance != null && distance <= DANGER_DISTANCE_CM

        if (critical) {
            return when {
                candidate.label in VEHICLES -> "Stop. Vehicle very close."
                candidate.label == "person" -> "Stop. Person very close."
                direction == "ahead" -> "Stop. Obstacle very close."
                else -> "Stop. $label $direction."
            }
        }

        if (candidate.label in VEHICLES && close) {
            return when (direction) {
                "ahead" -> "Warning. Vehicle ahead."
                "on your left" -> "Warning. Vehicle on your left."
                "on your right" -> "Warning. Vehicle on your right."
                else -> "Warning. Vehicle $direction."
            }
        }

        if (candidate.label == "person" && close) {
            return when (direction) {
                "ahead" -> "Warning. Person ahead."
                "on your left" -> "Person on your left."
                "on your right" -> "Person on your right."
                else -> "Person $direction."
            }
        }

        if (veryClose) {
            return when (direction) {
                "ahead" -> "Warning. Obstacle ahead."
                "on your left" -> "$label on your left."
                "on your right" -> "$label on your right."
                else -> "$label $direction."
            }
        }

        if (candidate.label in LARGE_OBSTACLES && close) {
            return when (direction) {
                "ahead" -> "$label ahead."
                else -> "$label $direction."
            }
        }

        if (
            candidate.label in FLOOR_OBJECTS &&
            candidate.result.boundingBox.bottom > 0.72f &&
            close
        ) {
            return "$label near your feet."
        }

        if (candidate.label == "person") {
            return when (direction) {
                "ahead" -> "Person ahead."
                else -> "Person $direction."
            }
        }

        return when (direction) {
            "ahead" -> "$label ahead."
            else -> "$label $direction."
        }
    }

    private fun directionFor(result: DetectionResult): String {
        val centerX = result.boundingBox.centerX()

        return when {
            centerX < 0.25f -> "on your left"
            centerX < 0.42f -> "slightly left"
            centerX <= 0.58f -> "ahead"
            centerX <= 0.75f -> "slightly right"
            else -> "on your right"
        }
    }

    private fun dangerScore(result: DetectionResult, label: String, sensor: SensorData?): Int {
        val centered = result.boundingBox.centerX() in 0.36f..0.64f
        val bottomClose = result.boundingBox.bottom > 0.75f
        val area = result.boundingBox.width() * result.boundingBox.height()

        val distance = sensor?.distance?.takeIf { it > 0f && it < 300f }

        var score = when {
            label in VEHICLES -> 90
            label == "person" && centered -> 85
            label == "person" -> 70
            label in LARGE_OBSTACLES && centered -> 75
            label in LARGE_OBSTACLES -> 55
            label in FLOOR_OBJECTS && bottomClose -> 55
            else -> 35
        }

        if (centered) score += 10
        if (bottomClose) score += 8
        if (area > 0.22f) score += 10

        score += when {
            distance == null -> 0
            distance <= 15f -> 25
            distance <= 25f -> 15
            distance <= SENSOR_OBSTACLE_CM -> 8
            else -> 0
        }

        return score.coerceIn(0, 100)
    }

    private fun displayLabel(label: String): String {
        val cleanLabel = when (label) {
            "cell phone" -> "phone"
            "dining table" -> "table"
            "potted plant" -> "plant"
            else -> label
        }

        return cleanLabel.replaceFirstChar { it.uppercase() }
    }

    private fun getSpeakableLabel(result: DetectionResult?): String? {
        val label = result?.label?.lowercase() ?: return null
        return if (label in IMPORTANT_OBJECTS) label else null
    }

    private fun resetSpeechMemory() {
        lastInferenceAt = 0L
        lastGuidanceAt = 0L
        lastClearPathAt = 0L
        lastGuidanceKey = ""
        lastDangerScore = 0
        lastSpokenByObject.clear()
    }

    override fun onCleared() {
        super.onCleared()

        sensorJob?.cancel()
        wifiSensor?.stop()
        repository.release()
        haptic.stopAll()
        tts.release()

        Log.i(TAG, "ViewModel cleared")
    }
}

private data class GuidanceCandidate(
    val result: DetectionResult,
    val label: String,
    val direction: String,
    val distanceCm: Float?,
    val dangerScore: Int
)

private data class SpokenMemory(
    val spokenAt: Long,
    val distanceCm: Float?,
    val dangerScore: Int
)

enum class ModelStatus {
    LOADING,
    READY,
    ERROR
}
