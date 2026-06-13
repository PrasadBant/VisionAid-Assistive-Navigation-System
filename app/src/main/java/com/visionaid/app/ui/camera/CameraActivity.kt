package com.visionaid.app.ui.camera

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.visionaid.app.bluetooth.ConnectionStatus
import com.visionaid.app.bluetooth.ProximityLevel
import com.visionaid.app.bluetooth.SensorData
import com.visionaid.app.databinding.ActivityCameraBinding
import com.visionaid.app.ml.DetectionResult
import com.visionaid.app.utils.ImageUtils
import com.visionaid.app.utils.PermissionUtils
import com.visionaid.app.viewmodel.DetectionViewModel
import com.visionaid.app.viewmodel.ModelStatus
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val viewModel: DetectionViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private val frameInProgress = AtomicBoolean(false)
    private var latestSensorData: SensorData? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[android.Manifest.permission.CAMERA] == true

            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnToggleDetection.setOnClickListener {
            viewModel.toggleDetection()
        }

        observeViewModel()

        if (PermissionUtils.allPermissionsGranted(this)) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.CALL_PHONE
                )
            )
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.detections.collect { detections ->
                        binding.overlayView.updateDetections(detections)
                        binding.tvDetectionCount.text = formatDetectionText(detections)
                    }
                }

                launch {
                    viewModel.isDetectionActive.collect { active ->
                        binding.btnToggleDetection.text = if (active) "Stop" else "Start"

                        if (!active) {
                            binding.overlayView.clearDetections()
                            binding.tvDetectionCount.text = "Press Start for guidance"
                        }
                    }
                }

                launch {
                    viewModel.modelStatus.collect(::updateModelStatus)
                }

                launch {
                    viewModel.sensorData.collect { data ->
                        data ?: return@collect

                        latestSensorData = data
                        updateSensorData(data)

                        binding.tvDetectionCount.text =
                            formatDetectionText(viewModel.detections.value)
                    }
                }

                launch {
                    viewModel.connectionStatus.collect(::updateConnectionStatus)
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(640, 480))
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!frameInProgress.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        if (bitmap == null) {
                            frameInProgress.set(false)
                            return@setAnalyzer
                        }

                        lifecycleScope.launch {
                            try {
                                viewModel.processFrame(bitmap)
                            } finally {
                                bitmap.recycle()
                                frameInProgress.set(false)
                            }
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateModelStatus(status: ModelStatus) {
        binding.progressBar.visibility =
            if (status == ModelStatus.LOADING) View.VISIBLE else View.GONE

        binding.btnToggleDetection.isEnabled = status == ModelStatus.READY

        binding.tvStatus.text = when (status) {
            ModelStatus.LOADING -> "Loading AI model..."
            ModelStatus.READY -> "Ready"
            ModelStatus.ERROR -> "AI model error"
        }

        binding.tvStatus.setTextColor(
            if (status == ModelStatus.ERROR) {
                Color.parseColor("#FF1744")
            } else {
                Color.parseColor("#38D996")
            }
        )
    }

    private fun updateConnectionStatus(status: ConnectionStatus) {
        binding.tvConnectionStatus.text = when (status) {
            ConnectionStatus.CONNECTED -> "Sensor connected"
            ConnectionStatus.CONNECTING -> "Connecting..."
            ConnectionStatus.NOT_FOUND -> "ESP32 not found"
            ConnectionStatus.PERMISSION_DENIED -> "Bluetooth denied"
            ConnectionStatus.UNAVAILABLE -> "Bluetooth unavailable"
            ConnectionStatus.DISCONNECTED -> "Sensor offline"
        }

        binding.wifiDot.backgroundTintList = ColorStateList.valueOf(
            if (status == ConnectionStatus.CONNECTED) {
                Color.parseColor("#38D996")
            } else {
                Color.parseColor("#FF1744")
            }
        )
    }

    private fun updateSensorData(data: SensorData) {
        binding.tvDistance.text = data.distanceLabel

        binding.tvAccelX.text = "X ${"%.1f".format(data.ax)}g"
        binding.tvAccelY.text = "Y ${"%.1f".format(data.ay)}g"
        binding.tvAccelZ.text = "Z ${"%.1f".format(data.az)}g"

        binding.tvTilt.text = if (data.isAccelerometerAlert) {
            "SUDDEN MOVEMENT"
        } else {
            "STEADY"
        }

        binding.tvDistance.setTextColor(
            when (data.proximityLevel) {
                ProximityLevel.DANGER -> Color.parseColor("#FF1744")
                ProximityLevel.CAUTION -> Color.parseColor("#FFD54F")
                ProximityLevel.SAFE -> Color.parseColor("#38D996")
                ProximityLevel.UNKNOWN -> Color.WHITE
            }
        )

        val showAlert = data.distance in 1f..35f || data.isAccelerometerAlert

        binding.tvAlertBanner.visibility =
            if (showAlert) View.VISIBLE else View.GONE

        binding.tvAlertBanner.text = when {
            data.isAccelerometerAlert -> "EMERGENCY MOVEMENT DETECTED"
            data.distance in 1f..20f -> "WARNING - VERY CLOSE"
            data.distance in 1f..35f -> "CAREFUL - OBSTACLE AHEAD"
            else -> ""
        }
    }

    private fun formatDetectionText(detections: List<DetectionResult>): String {
        val topDetection = detections.firstOrNull() ?: return "Path clear ahead"
        val distance = latestSensorData?.distance

        val distanceText = if (distance != null && distance > 0f && distance <= 35f) {
            " - ${distance.toInt()} cm"
        } else {
            ""
        }

        return "${topDetection.label} ${topDetection.confidencePercent}$distanceText"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
