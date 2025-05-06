package com.example.spybridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DetectionActivity : AppCompatActivity() {
    private val TAG = "DetectionActivity"

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detectionManager: DetectionManager

    // Camera variables
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Detection state
    private var isDetectionActive = false
    private var currentZoom = 1.0f

    // Permissions
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val REQUEST_CODE_PERMISSIONS = 10

    // UI update handler
    private val handler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (isDetectionActive) {
                updateDetectionUI()
                handler.postDelayed(this, 500) // Update UI every 500ms
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize detection manager
        detectionManager = DetectionManager(this)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up UI elements
        setupUI()

        // Initialize executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize detection manager
        val initSuccess = detectionManager.initialize()
        if (initSuccess) {
            binding.tvStatus.text = "Status: Model loaded successfully"
        } else {
            binding.tvStatus.text = "Status: Failed to load model"
        }
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            stopDetection()
            finish()
        }

        // Refresh button
        binding.btnRefresh.setOnClickListener {
            resetDetection()
        }

        // Zoom controls
        binding.btnZoomIn.setOnClickListener {
            adjustZoom(0.1f)
        }

        binding.btnZoomOut.setOnClickListener {
            adjustZoom(-0.1f)
        }

        // Add a dedicated camera switch button or use a long press on the preview
        // Option 1: Add a Switch Camera button
        val switchCameraButton = findViewById<View>(R.id.btnSwitchCamera)
        if (switchCameraButton != null) {
            switchCameraButton.setOnClickListener {
                switchCamera()
            }
        } else {
            // Option 2: If no dedicated button, add a long press listener to the preview
            binding.ivPreview.setOnLongClickListener {
                switchCamera()
                true
            }

            // Also add a popup message to inform the user about the long press feature
            Toast.makeText(
                this,
                "Long press on preview to switch cameras",
                Toast.LENGTH_LONG
            ).show()
        }

        // Start/Stop detection button
        binding.btnStartDetection.setOnClickListener {
            if (isDetectionActive) {
                stopDetection()
            } else {
                startDetection()
            }
        }
    }

    /**
     * Switch between front and back camera
     */
    private fun switchCamera() {
        if (isDetectionActive) {
            // First stop detection to avoid issues
            stopDetection()
        }

        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Restart camera with new selector
        startCamera()

        // Show toast indicating camera switch
        val cameraName = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        Toast.makeText(
            this,
            "Switched to $cameraName camera",
            Toast.LENGTH_SHORT
        ).show()

        Log.d(TAG, "Switched to $cameraName camera")

        // If detection was active, restart it
        if (isDetectionActive) {
            startDetection()
        }
    }

    /**
     * Start the detection process - corresponds to "Tekan tombol start detection" in flowchart
     */
    private fun startDetection() {
        isDetectionActive = true
        binding.btnStartDetection.text = "Stop Detection"
        binding.btnStartDetection.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
        binding.btnStartDetection.setIconResource(R.drawable.stop_circle_24px)

        // Step 1: Reset hasil - clear previous results
        resetDetection()

        // Step 2: Start detection in the manager
        detectionManager.startDetection()

        // Start UI updates
        handler.post(uiUpdateRunnable)

        binding.tvStatus.text = "Status: Detection active"
        Log.d(TAG, "Detection started")
    }

    /**
     * Stop the detection process - corresponds to "Stop Deteksi" in flowchart
     */
    private fun stopDetection() {
        isDetectionActive = false
        binding.btnStartDetection.text = "Start Detection"
        binding.btnStartDetection.backgroundTintList = ContextCompat.getColorStateList(this, R.color.green)
        binding.btnStartDetection.setIconResource(R.drawable.play_arrow_24px)

        // Stop detection in manager but preserve detection status
        val finalStatus = detectionManager.stopDetection()

        // Stop UI updates
        handler.removeCallbacks(uiUpdateRunnable)

        // Display final result
        displayFinalResult(finalStatus)

        Log.d(TAG, "Detection stopped with status: $finalStatus")
    }

    /**
     * Reset the detection state - corresponds to "reset hasil" in flowchart
     */
    private fun resetDetection() {
        if (isDetectionActive) {
            // Restart detection to reset state
            detectionManager.stopDetection()
            detectionManager.startDetection()

            // Reset UI
            binding.tvBlinkPatternData.text = "No blink data yet"
            binding.ivEyeStatus.setImageResource(R.drawable.visibility_24px)

            binding.tvStatus.text = "Status: Detection reset"
            Log.d(TAG, "Detection reset")
        }
    }

    /**
     * Update detection UI with current results
     */
    private fun updateDetectionUI() {
        val result = detectionManager.getDetectionStatus()
        val blinkCount = detectionManager.getBlinkCount()

        // Update blink count
        binding.tvBlinkPatternData.text = "Blinks detected: $blinkCount"

        // Update status based on detection result
        if (result == "Curang terdeteksi") {
            // "Curang terdeteksi" matches flowchart output
            binding.tvBlinkPatternData.text = "Anomalous blink pattern detected!"
            binding.tvBlinkPatternData.setTextColor(ContextCompat.getColor(this, R.color.red))
            binding.ivEyeStatus.setImageResource(R.drawable.warning_24px)

            // If cheating detected, auto-stop detection after delay
            // This matches the automatic flow in the flowchart
            handler.postDelayed({
                if (isDetectionActive) {
                    stopDetection()
                }
            }, 3000) // Wait 3 seconds before stopping

        } else {
            binding.tvBlinkPatternData.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.ivEyeStatus.setImageResource(R.drawable.visibility_24px)
        }
    }

    /**
     * Display final detection result - corresponds to "Tampil hasil" in flowchart
     */
    private fun displayFinalResult(finalStatus: String) {
        // Show the final result
        if (finalStatus == "Curang terdeteksi") {
            binding.tvStatus.text = "Result: Cheating Detected!"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
        } else {
            binding.tvStatus.text = "Result: No Cheating Detected"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        }

        Log.d(TAG, "Final result displayed: $finalStatus")
    }

    /**
     * Set up camera and image analysis
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // Unbind any bound use cases before rebinding
                cameraProvider.unbindAll()

                // Set up preview
                preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.ivPreview.surfaceProvider)
                    }

                // Set up image capture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Set up image analyzer with correct rotation
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(binding.ivPreview.display.rotation)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                            processImage(imageProxy)
                        })
                    }

                try {
                    // Bind use cases to camera
                    camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, imageAnalyzer
                    )

                    // Reset zoom display
                    currentZoom = 1.0f
                    binding.tvZoom.text = "1.0x"

                } catch (e: Exception) {
                    Log.e(TAG, "Use case binding failed", e)
                    Toast.makeText(this, "Camera binding failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
                Toast.makeText(this, "Camera provider failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Process camera image for detection
     * This implements the image analysis part that feeds into the detection flow
     */
    private fun processImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null && isDetectionActive) {
                // Process frame with detection manager
                detectionManager.processFrame(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}")
        } finally {
            // Always close the image proxy
            imageProxy.close()
        }
    }

    /**
     * Convert ImageProxy to Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap")
                return null
            }

            // Rotate bitmap if needed based on image rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())

            // Handle mirroring for front camera
            if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                matrix.postScale(-1f, 1f)
            }

            return Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image proxy to bitmap: ${e.message}")
            return null
        }
    }

    /**
     * Adjust camera zoom level
     */
    private fun adjustZoom(delta: Float) {
        val camera = camera ?: return

        try {
            val currentZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            val newZoomRatio = (currentZoomRatio + delta).coerceIn(1f, 5f)

            camera.cameraControl.setZoomRatio(newZoomRatio)
            currentZoom = newZoomRatio
            binding.tvZoom.text = String.format("%.1fx", newZoomRatio)
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting zoom: ${e.message}")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detectionManager.release()
        handler.removeCallbacks(uiUpdateRunnable)
        Log.d(TAG, "Activity destroyed and resources released")
    }
}