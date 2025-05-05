package com.example.spybridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.spybridge.databinding.ActivityDetectionBinding
import com.example.spybridge.ml.BlinkAnalyzer
import com.example.spybridge.ml.YoloAnalyzer
import com.example.spybridge.models.EyeDetection
import com.example.spybridge.utils.BlinkPattern
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class DetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isDetecting = false
    private val blinkAnalyzer = BlinkAnalyzer()
    private val blinkEvents = mutableListOf<BlinkPattern.BlinkEvent>()
    private var lastEyeDetections = listOf<EyeDetection>()
    private lateinit var yoloAnalyzer: YoloAnalyzer
    private var currentZoomRatio = 1.0f
    private var maxZoomRatio = 5.0f

    // Detection state
    private var earValue = 1.0f
    private var suspiciousPattern = false

    companion object {
        private const val TAG = "DetectionActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize YOLO analyzer
        initYoloAnalyzer()

        // Check permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up UI elements and listeners
        setupUI()

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initYoloAnalyzer() {
        try {
            // Use MappedByteBuffer approach to load the model
            val fileDescriptor = assets.openFd("models/best_saved_model/best_float32.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val mappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
            )

            // Configure the Interpreter with options if needed
            val options = Interpreter.Options()
            val tflite = Interpreter(mappedByteBuffer, options)

            yoloAnalyzer = YoloAnalyzer(tflite)
            updateStatus("Model loaded successfully")

            // Close resources
            fileDescriptor.close()
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading YOLO model: ${e.message}", e)
            updateStatus("Error loading model: ${e.message}")
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.tvStatus.text = message
        }
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Toggle detection button
        binding.btnStartDetection.setOnClickListener {
            toggleDetection()
        }

        // Zoom controls
        binding.btnZoomIn.setOnClickListener {
            zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            zoomOut()
        }

        // Refresh button
        binding.btnRefresh.setOnClickListener {
            resetAnalysis()
        }
    }

    private fun resetAnalysis() {
        blinkEvents.clear()
        suspiciousPattern = false
        updateBlinkPattern("No blink data yet")
        updateStatus("Analysis reset")
    }

    private fun toggleDetection() {
        isDetecting = !isDetecting

        if (isDetecting) {
            binding.btnStartDetection.text = "Stop Detection"
            binding.btnStartDetection.setBackgroundColor(
                ContextCompat.getColor(this, R.color.red)
            )
            updateStatus("Detection started")
        } else {
            binding.btnStartDetection.text = "Start Detection"
            binding.btnStartDetection.setBackgroundColor(
                ContextCompat.getColor(this, R.color.green)
            )
            updateStatus("Detection stopped")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.ivPreview.surfaceProvider)
                }

            // Image analysis for eye detection
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Image capture for saving evidence if needed
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()

            // Select front camera by default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

                // Get max zoom ratio
                camera?.let {
                    maxZoomRatio = it.cameraInfo.zoomState.value?.maxZoomRatio ?: 5.0f
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (!isDetecting) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Convert to bitmap
        val bitmap = imageProxyToBitmap(imageProxy)
        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())

        // Run YOLO detection
        val detections = yoloAnalyzer.detectEyes(rotatedBitmap)
        lastEyeDetections = detections

        // Calculate EAR value
        if (detections.isNotEmpty()) {
            earValue = calculateEAR(detections)

            // Check for blink
            val blinkDetected = blinkAnalyzer.detectBlink(earValue)
            if (blinkDetected) {
                val currentTime = System.currentTimeMillis()
                val blinkEvent = BlinkPattern.BlinkEvent(currentTime, earValue)
                blinkEvents.add(blinkEvent)

                // Check for suspicious patterns
                analyzeBlinkPattern()
            }

            // Update UI with detection info
            updateDetectionUI(detections, earValue)
        }

        imageProxy.close()
    }

    private fun calculateEAR(detections: List<EyeDetection>): Float {
        var sumEAR = 0f
        detections.forEach { detection ->
            val height = detection.height.toFloat()
            val width = detection.width.toFloat()
            val ear = height / width
            sumEAR += ear
        }
        return if (detections.isNotEmpty()) sumEAR / detections.size else 1.0f
    }

    private fun analyzeBlinkPattern() {
        // Only analyze if we have enough blink events
        if (blinkEvents.size < 3) return

        // Get recent events (last 30 seconds)
        val currentTime = System.currentTimeMillis()
        val recentEvents = blinkEvents.filter {
            currentTime - it.timestamp < 30000 // 30 seconds
        }

        // Calculate metrics
        val count = recentEvents.size
        val timeSpan = (recentEvents.lastOrNull()?.timestamp ?: currentTime) -
                (recentEvents.firstOrNull()?.timestamp ?: currentTime)
        val timeSpanMinutes = timeSpan / 60000.0

        // Blink rate (blinks per minute)
        val blinkRate = if (timeSpanMinutes > 0) count / timeSpanMinutes else 0.0

        // Check intervals between blinks
        val intervals = mutableListOf<Long>()
        for (i in 1 until recentEvents.size) {
            intervals.add(recentEvents[i].timestamp - recentEvents[i-1].timestamp)
        }

        // Check for rapid succession blinks (more than 3 blinks within 0.4 seconds)
        var rapidBlinks = 0
        val maxBlinkDuration = 400 // 0.4 seconds in milliseconds

        for (i in 0 until intervals.size - 2) {  // Check groups of 3 consecutive blinks
            if (intervals[i] + intervals[i+1] < maxBlinkDuration) {
                rapidBlinks++
            }
        }

        // Determine if pattern is suspicious
        suspiciousPattern = when {
            blinkRate > 50 -> true  // Unusually high blink rate
            rapidBlinks > 0 -> true // Rapid sequences detected
            areIntervalsRhythmic(intervals) -> true // Rhythmic pattern
            else -> false
        }

        // Update UI with analysis results
        runOnUiThread {
            if (suspiciousPattern) {
                binding.cvWaitingData.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.red)
                )
                updateBlinkPattern("SUSPICIOUS: ${count} blinks, rate: ${blinkRate.roundToInt()} blinks/min")
            } else {
                updateBlinkPattern("Normal: ${count} blinks, rate: ${blinkRate.roundToInt()} blinks/min")
            }
        }
    }

    private fun areIntervalsRhythmic(intervals: List<Long>): Boolean {
        if (intervals.size < 4) return false

        // Check for consistent intervals (rhythmic pattern)
        val threshold = 50L // Maximum deviation in milliseconds

        // Check subsequences for similar patterns
        for (i in 0..intervals.size - 3) {
            val pattern1 = intervals[i]

            // Look for repeating pattern
            for (j in i + 1..intervals.size - 2) {
                val pattern2 = intervals[j]

                if (Math.abs(pattern1 - pattern2) < threshold) {
                    // Found similar intervals - potential code
                    return true
                }
            }
        }

        return false
    }

    private fun updateBlinkPattern(message: String) {
        runOnUiThread {
            binding.tvBlinkPatternData.text = message

            // Update eye status icon
            binding.ivEyeStatus.setImageResource(
                if (suspiciousPattern) R.drawable.warning_24px else R.drawable.visibility_24px
            )
        }
    }

    private fun updateDetectionUI(detections: List<EyeDetection>, earValue: Float) {
        runOnUiThread {
            val confidenceText = if (detections.isNotEmpty()) {
                val avgConfidence = detections.map { it.confidence }.average()
                String.format("%.1f%%", avgConfidence * 100)
            } else {
                "0%"
            }

            val earText = String.format("EAR: %.2f", earValue)

            updateStatus("Detected: ${detections.size} eyes, Conf: $confidenceText, $earText")

            // TODO: Implement drawing of bounding boxes in overlay
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Convert YUV to RGB
        val yuvImage = android.graphics.YuvImage(
            bytes,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
            100,
            out
        )

        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotation)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun zoomIn() {
        // Increase zoom by 0.5x
        val newZoom = (currentZoomRatio + 0.5f).coerceAtMost(maxZoomRatio)
        updateZoom(newZoom)
    }

    private fun zoomOut() {
        // Decrease zoom by 0.5x
        val newZoom = (currentZoomRatio - 0.5f).coerceAtLeast(1.0f)
        updateZoom(newZoom)
    }

    private fun updateZoom(zoomRatio: Float) {
        camera?.let {
            val cameraControl = it.cameraControl
            cameraControl.setZoomRatio(zoomRatio)
            currentZoomRatio = zoomRatio

            // Update zoom UI
            binding.tvZoom.text = String.format("%.1fx", zoomRatio)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}