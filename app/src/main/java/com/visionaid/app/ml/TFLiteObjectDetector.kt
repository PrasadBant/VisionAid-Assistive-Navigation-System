package com.visionaid.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class TFLiteObjectDetector(
    private val context: Context,
    private val modelFileName: String = MODEL_FILE,
    private val labelsFileName: String = LABELS_FILE
) {

    companion object {
        private const val TAG = "VISIONAID"
        const val MODEL_FILE = "yolov8n.tflite"
        const val LABELS_FILE = "coco_labels.txt"
        const val INPUT_SIZE = 640
        const val MAX_DETECTIONS = 8400
        const val CONFIDENCE_THRESHOLD = 0.45f
        const val IOU_THRESHOLD = 0.45f

        // Official YOLOv8 COCO label order — verified from ultralytics source
        val COCO_LABELS = listOf(
            "person",           // 0
            "bicycle",          // 1
            "car",              // 2
            "motorcycle",       // 3
            "airplane",         // 4
            "bus",              // 5
            "train",            // 6
            "truck",            // 7
            "boat",             // 8
            "traffic light",    // 9
            "fire hydrant",     // 10
            "stop sign",        // 11
            "parking meter",    // 12
            "bench",            // 13
            "bird",             // 14
            "cat",              // 15
            "dog",              // 16
            "horse",            // 17
            "sheep",            // 18
            "cow",              // 19
            "elephant",         // 20
            "bear",             // 21
            "zebra",            // 22
            "giraffe",          // 23
            "backpack",         // 24
            "umbrella",         // 25
            "handbag",          // 26
            "tie",              // 27
            "suitcase",         // 28
            "frisbee",          // 29
            "skis",             // 30
            "snowboard",        // 31
            "sports ball",      // 32
            "kite",             // 33
            "baseball bat",     // 34
            "baseball glove",   // 35
            "skateboard",       // 36
            "surfboard",        // 37
            "tennis racket",    // 38
            "bottle",           // 39
            "wine glass",       // 40
            "cup",              // 41
            "fork",             // 42
            "knife",            // 43
            "spoon",            // 44
            "bowl",             // 45
            "banana",           // 46
            "apple",            // 47
            "sandwich",         // 48
            "orange",           // 49
            "broccoli",         // 50
            "carrot",           // 51
            "hot dog",          // 52
            "pizza",            // 53
            "donut",            // 54
            "cake",             // 55
            "chair",            // 56
            "couch",            // 57
            "potted plant",     // 58
            "bed",              // 59
            "dining table",     // 60
            "toilet",           // 61
            "tv",               // 62
            "laptop",           // 63
            "mouse",            // 64
            "remote",           // 65
            "keyboard",         // 66
            "cell phone",       // 67
            "microwave",        // 68
            "oven",             // 69
            "toaster",          // 70
            "sink",             // 71
            "refrigerator",     // 72
            "book",             // 73
            "clock",            // 74
            "vase",             // 75
            "scissors",         // 76
            "teddy bear",       // 77
            "hair drier",       // 78
            "toothbrush"        // 79
        )
    }

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        try {
            loadModel()
            isInitialized = true
            Log.e(TAG, "✅ YOLOv8n initialized — ${COCO_LABELS.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Init failed: ${e.message}", e)
            throw IllegalStateException("Failed to initialize: ${e.message}", e)
        }
    }

    private fun loadModel() {
        val modelBuffer = FileUtil.loadMappedFile(context, modelFileName)
        val options = Interpreter.Options().apply {
            numThreads = max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(modelBuffer, options)

        val interp = interpreter!!
        Log.i(TAG, "Input : ${interp.getInputTensor(0).shape().contentToString()} ${interp.getInputTensor(0).dataType()}")
        for (i in 0 until interp.outputTensorCount) {
            Log.i(TAG, "Output[$i]: ${interp.getOutputTensor(i).shape().contentToString()} ${interp.getOutputTensor(i).dataType()}")
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (!isInitialized) return emptyList()
        val interpreter = this.interpreter ?: return emptyList()

        return try {
            val inputBuffer = preprocessBitmap(bitmap)

            // YOLOv8n output shape: [1, 84, 8400]
            // 84 = 4 box coords + 80 class scores
            val output = Array(1) { Array(84) { FloatArray(MAX_DETECTIONS) } }

            interpreter.run(inputBuffer, output)
            postProcess(output)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inference error: ${e.message}", e)
            emptyList()
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // YOLOv8 float32 — normalize pixels to [0.0, 1.0]
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaledBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat((pixel shr 16 and 0xFF) / 255.0f) // R
            buffer.putFloat((pixel shr 8  and 0xFF) / 255.0f) // G
            buffer.putFloat((pixel        and 0xFF) / 255.0f) // B
        }

        buffer.rewind()
        scaledBitmap.recycle()
        return buffer
    }

    private fun postProcess(output: Array<Array<FloatArray>>): List<DetectionResult> {
        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until MAX_DETECTIONS) {
            // Find best class score for this anchor
            var maxScore = 0f
            var bestClass = -1

            for (c in 0 until COCO_LABELS.size) {
                val score = output[0][4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (maxScore < CONFIDENCE_THRESHOLD) continue
            if (bestClass < 0) continue

            val label = COCO_LABELS.getOrNull(bestClass) ?: continue

            // YOLOv8 box format: cx, cy, w, h (all normalized 0-1)
            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w  = output[0][2][i]
            val h  = output[0][3][i]

            // Convert to left, top, right, bottom
            val left   = (cx - w / 2f).coerceIn(0f, 1f)
            val top    = (cy - h / 2f).coerceIn(0f, 1f)
            val right  = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            if (right <= left || bottom <= top) continue

            candidates.add(
                DetectionResult(
                    label = label,
                    confidence = maxScore,
                    boundingBox = RectF(left, top, right, bottom)
                )
            )
        }

        // Apply NMS per class to remove duplicate boxes
        val finalResults = applyNMS(candidates)

        return finalResults
    }

    // Non-Maximum Suppression
    // Removes overlapping boxes keeping only highest confidence per object
    private fun applyNMS(
        detections: List<DetectionResult>,
        iouThreshold: Float = IOU_THRESHOLD
    ): List<DetectionResult> {
        // Group by label — apply NMS per class
        val byClass = detections.groupBy { it.label }
        val kept = mutableListOf<DetectionResult>()

        for ((_, classDetections) in byClass) {
            val sorted = classDetections.sortedByDescending { it.confidence }.toMutableList()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { other ->
                    calculateIoU(best.boundingBox, other.boundingBox) > iouThreshold
                }
            }
        }

        return kept.sortedByDescending { it.confidence }
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left   = maxOf(a.left,   b.left)
        val top    = maxOf(a.top,    b.top)
        val right  = minOf(a.right,  b.right)
        val bottom = minOf(a.bottom, b.bottom)

        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection

        return if (union == 0f) 0f else intersection / union
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
        }
    }
}
