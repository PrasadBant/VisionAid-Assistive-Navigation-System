package com.visionaid.app.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.visionaid.app.ml.DetectionResult

/**
 * BoundingBoxOverlayView
 *
 * Transparent overlay drawn on top of the camera PreviewView.
 * Shows only the TOP 1 detected object — no clutter, no confusion.
 *
 * Box color:
 *  - Green  → normal object
 *  - Red    → close object (area > 25% of frame)
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Current detections to draw — always max 1 item
    private var detections: List<DetectionResult> = emptyList()

    // ── Paints ────────────────────────────────────────────

    private val normalBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val closeBoxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val confidenceTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
    }

    // ── Public API ────────────────────────────────────────

    /**
     * Update with new detection results.
     * Only the first (highest confidence) result is drawn.
     * Call from any thread — uses postInvalidate() internally.
     */
    fun updateDetections(results: List<DetectionResult>) {
        // Show only TOP 1 detection
        detections = if (results.isNotEmpty()) listOf(results.first()) else emptyList()
        postInvalidate()
    }

    /**
     * Clear all detections from the overlay.
     */
    fun clearDetections() {
        detections = emptyList()
        postInvalidate()
    }

    // ── Drawing ───────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty()) return

        val viewWidth  = width.toFloat()
        val viewHeight = height.toFloat()

        for (result in detections) {
            drawDetection(canvas, result, viewWidth, viewHeight)
        }
    }

    private fun drawDetection(
        canvas: Canvas,
        result: DetectionResult,
        viewWidth: Float,
        viewHeight: Float
    ) {
        // Scale normalized [0,1] box to actual pixel coordinates
        val box = RectF(
            result.boundingBox.left   * viewWidth,
            result.boundingBox.top    * viewHeight,
            result.boundingBox.right  * viewWidth,
            result.boundingBox.bottom * viewHeight
        )

        // Choose box color based on proximity
        val boxPaint = if (result.isClose) closeBoxPaint else normalBoxPaint
        canvas.drawRect(box, boxPaint)

        // ── Label badge ───────────────────────────────────
        val labelText      = result.label.uppercase()
        val confidenceText = result.confidencePercent

        val textPadding = 12f
        val badgeHeight = 90f
        val labelWidth  = labelTextPaint.measureText(labelText) + textPadding * 2

        // Badge background
        val badgeLeft   = box.left
        val badgeTop    = (box.top - badgeHeight).coerceAtLeast(0f)
        val badgeRight  = (box.left + labelWidth + 20f).coerceAtMost(viewWidth)
        val badgeBottom = box.top.coerceAtLeast(badgeHeight)

        labelBackgroundPaint.color = if (result.isClose) Color.RED else Color.parseColor("#CC2E7D32")
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeRight, badgeBottom),
            8f, 8f,
            labelBackgroundPaint
        )

        // Label text
        canvas.drawText(
            labelText,
            badgeLeft + textPadding,
            badgeBottom - 50f,
            labelTextPaint
        )

        // Confidence text
        canvas.drawText(
            confidenceText,
            badgeLeft + textPadding,
            badgeBottom - 18f,
            confidenceTextPaint
        )
    }
}