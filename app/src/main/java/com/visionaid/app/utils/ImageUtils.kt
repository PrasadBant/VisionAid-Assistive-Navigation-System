package com.visionaid.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * ImageUtils
 *
 * Utility functions for converting CameraX [ImageProxy] frames to [Bitmap]
 * suitable for TFLite inference.
 *
 * CameraX delivers frames in YUV_420_888 format.
 * TFLite expects RGB Bitmap.
 * This class handles the YUV → JPEG → RGB conversion efficiently.
 */
object ImageUtils {

    /**
     * Convert a CameraX [ImageProxy] (YUV_420_888) to a [Bitmap].
     *
     * The returned Bitmap is in ARGB_8888 format, rotation-corrected
     * according to the image's rotation degrees metadata.
     *
     * @param imageProxy The image from CameraX ImageAnalysis use case
     * @return RGB Bitmap ready for TFLite preprocessing, or null on failure
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yuvBitmap = yuv420ToBitmap(imageProxy) ?: return null

            // Apply rotation correction so the image is upright
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            rotateBitmap(yuvBitmap, rotationDegrees.toFloat())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert YUV_420_888 ImageProxy to ARGB Bitmap via JPEG intermediate.
     *
     * The YUV → JPEG → Bitmap path is slightly lossy but fast and well-supported.
     * For maximum quality, use a RenderScript or Vulkan-based conversion.
     */
    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap? {
        val nv21 = yuv420ToNV21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)

        val outputStream = ByteArrayOutputStream()
        // Quality 85 is a good balance between speed and accuracy
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 85, outputStream)

        val jpegBytes = outputStream.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * Convert YUV_420_888 planes to NV21 byte array.
     *
     * NV21 layout: [Y plane full][VU interleaved plane]
     * CameraX YUV_420_888 layout: [Y][U][V] as separate planes with pixel strides.
     */
    private fun yuv420ToNV21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val ySize = width * height
        val uvSize = width * height / 2

        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val yRowStride = imageProxy.planes[0].rowStride
        val uvRowStride = imageProxy.planes[1].rowStride
        val uvPixelStride = imageProxy.planes[1].pixelStride

        var pos = 0

        // Copy Y plane
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        // Interleave U and V into NV21 (V first, then U)
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer[uvIndex]  // V
                nv21[pos++] = uBuffer[uvIndex]  // U
            }
        }

        return nv21
    }

    /**
     * Rotate a bitmap by [degrees] degrees.
     * Recycles the input bitmap and returns a new rotated one.
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotated
    }
}
