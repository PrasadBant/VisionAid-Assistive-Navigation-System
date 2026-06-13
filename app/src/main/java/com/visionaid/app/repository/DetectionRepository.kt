package com.visionaid.app.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.visionaid.app.ml.DetectionResult
import com.visionaid.app.ml.TFLiteObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DetectionRepository
 *
 * Acts as the single source of truth for object detection operations.
 * Sits between the ViewModel and the TFLite inference module.
 *
 * Responsibilities:
 *  - Initializing and holding the TFLite detector lifecycle
 *  - Running inference on a background (IO) dispatcher
 *  - Abstracting the ML layer from the ViewModel
 *
 * In a larger app this could also:
 *  - Cache recent results
 *  - Switch between local and cloud inference
 *  - Persist detection history to a local database (Room)
 */
class DetectionRepository(private val context: Context) {

    companion object {
        private const val TAG = "DetectionRepository"
    }

    private val detector = TFLiteObjectDetector(context)

    // Tracks whether initialization has been attempted
    private var initializationError: String? = null

    /**
     * Initialize the underlying TFLite model.
     * Called once from ViewModel.init{}.
     * Runs on IO dispatcher to avoid blocking main thread.
     *
     * @return true if successful, false if model loading failed.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            detector.initialize()
            Log.i(TAG, "Detector initialized successfully")
            true
        } catch (e: Exception) {
            initializationError = e.message
            Log.e(TAG, "Detector initialization failed: ${e.message}")
            false
        }
    }

    /**
     * Run object detection on a single camera frame.
     *
     * Switches to [Dispatchers.Default] (compute thread pool) for inference,
     * keeping the camera callback thread free.
     *
     * @param bitmap Camera frame bitmap (will NOT be recycled here — caller owns it)
     * @return List of detected objects, empty on error.
     */
    suspend fun detectObjects(bitmap: Bitmap): List<DetectionResult> =
        withContext(Dispatchers.Default) {
            return@withContext try {
                detector.detect(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}")
                emptyList()
            }
        }

    /**
     * Get the last initialization error message, if any.
     */
    fun getInitializationError(): String? = initializationError

    /**
     * Release all resources. Must be called when done (ViewModel.onCleared).
     */
    fun release() {
        detector.close()
        Log.i(TAG, "DetectionRepository released")
    }
}
