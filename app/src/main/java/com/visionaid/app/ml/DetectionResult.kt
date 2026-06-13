package com.visionaid.app.ml

import android.graphics.RectF

/**
 * Represents a single object detected by the TFLite model.
 *
 * @param label       Human-readable class name (e.g. "person", "chair")
 * @param confidence  Detection confidence in [0.0, 1.0]
 * @param boundingBox Normalized bounding box [0,1] relative to image dimensions
 */
data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
) {
    /**
     * Confidence as a percentage string, e.g. "87%"
     */
    val confidencePercent: String
        get() = "${(confidence * 100).toInt()}%"

    /**
     * Rough proximity estimation based on bounding box area.
     * A larger bounding box implies the object is closer to the camera.
     *
     * This is a heuristic for the software simulation of proximity detection.
     * Future: replace with stereo depth or LiDAR data.
     */
    val isClose: Boolean
        get() {
            val area = boundingBox.width() * boundingBox.height()
            return area > CLOSE_THRESHOLD
        }

    companion object {
        // An object occupying >25% of the frame area is considered "close"
        private const val CLOSE_THRESHOLD = 0.25f
    }
}
