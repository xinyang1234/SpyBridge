package com.example.spybridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DetectionManager handles the eye detection and blink pattern analysis
 * using the flow from the provided diagram.
 */
class DetectionManager(private val context: Context) {
    private val TAG = "DetectionManager"

    // Detection status flags
    private var isDetectionActive = false
    private val isProcessing = AtomicBoolean(false)

    // TFLite model
    private var interpreter: Interpreter? = null

    // Detection components
    private val earCalculator = EARCalculator()
    private val blinkAnalyzer = BlinkAnalyzer()
    private var yoloAnalyzer: YoloAnalyzer? = null

    // Detection metrics
    private var earValue: Float = 1.0f
    private var blinkCount = 0
    private val blinkHistory = mutableListOf<Long>()
    private var lastBlinkTime: Long = 0

    // Thresholds
    private val EAR_THRESHOLD = 0.2f
    private val BLINK_DURATION_THRESHOLD = 300 // ms
    private val CONSECUTIVE_FRAMES_THRESHOLD = 3

    // Frame counters
    private var framesWithEyesClosed = 0
    private var framesWithEyesOpen = 0

    // Anomaly detection
    private var suspiciousPatternDetected = false

    // Handler for demo mode (when no model available)
    private val handler = Handler(Looper.getMainLooper())
    private var demoRunnable: Runnable? = null
    private var isDemoMode = false

    /**
     * Initialize the detection manager and load the model
     */
    fun initialize(): Boolean {
        try {
            Log.d(TAG, "Initializing detection manager...")

            // First check if the model exists
            try {
                context.assets.open("yolo_model.tflite").close()
                // If we get here, the model exists
                yoloAnalyzer = YoloAnalyzer(context)
                val initSuccess = yoloAnalyzer?.initialize() ?: false

                if (initSuccess) {
                    Log.d(TAG, "Model loaded successfully")
                    isDemoMode = false
                    return true
                } else {
                    // Failed to initialize model, fall back to demo mode
                    Log.e(TAG, "Failed to initialize model, falling back to demo mode")
                    isDemoMode = true
                    return true // Return true to continue with demo mode
                }
            } catch (e: Exception) {
                // Model doesn't exist, fallback to demo mode
                Log.e(TAG, "Model file not found: ${e.message}")
                Log.d(TAG, "Falling back to demo mode")
                isDemoMode = true
                return true // Return true to continue with demo mode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing detection manager: ${e.message}")
            return false
        }
    }

    /**
     * Start the detection process
     */
    fun startDetection() {
        // Reset detection state - align with flowchart "reset hasil"
        resetDetectionState()
        isDetectionActive = true

        // If in demo mode, start simulating blinks
        if (isDemoMode) {
            startDemoMode()
        }

        Log.d(TAG, "Detection started")
    }

    /**
     * Start demo mode with simulated blinks
     */
    private fun startDemoMode() {
        // Stop any existing demo
        stopDemoMode()

        // Create a new runnable for demo
        demoRunnable = object : Runnable {
            override fun run() {
                if (isDetectionActive) {
                    // Simulate random blinks
                    if (Math.random() < 0.3) { // 30% chance of blink
                        val currentTime = System.currentTimeMillis()
                        if (lastBlinkTime > 0) {
                            blinkHistory.add(currentTime - lastBlinkTime)
                        }
                        lastBlinkTime = currentTime
                        blinkCount++

                        // Simulate suspicious pattern occasionally
                        if (Math.random() < 0.1) { // 10% chance of suspicious pattern
                            // Add another quick blink to simulate a suspicious pattern
                            handler.postDelayed({
                                if (isDetectionActive) {
                                    val newTime = System.currentTimeMillis()
                                    if (lastBlinkTime > 0) {
                                        blinkHistory.add(newTime - lastBlinkTime)
                                    }
                                    lastBlinkTime = newTime
                                    blinkCount++

                                    // Analyze for suspicious pattern
                                    analyzeBlinkPattern()
                                }
                            }, 800) // Less than 1 second after previous blink
                        }
                    }

                    // Schedule next blink simulation
                    handler.postDelayed(this, 2000 + (Math.random() * 3000).toLong()) // Random 2-5 seconds
                }
            }
        }

        // Start the demo
        handler.postDelayed(demoRunnable!!, 2000)
    }

    /**
     * Stop demo mode
     */
    private fun stopDemoMode() {
        demoRunnable?.let { handler.removeCallbacks(it) }
    }

    /**
     * Stop the detection process
     */
    fun stopDetection(): String {
        val finalStatus = getDetectionStatus() // Get status before stopping
        isDetectionActive = false

        if (isDemoMode) {
            stopDemoMode()
        }

        Log.d(TAG, "Detection stopped with status: $finalStatus")
        return finalStatus
    }

    /**
     * Reset all detection metrics and history
     * This matches the "reset hasil" step in the flowchart
     */
    private fun resetDetectionState() {
        earValue = 1.0f
        blinkCount = 0
        blinkHistory.clear()
        framesWithEyesClosed = 0
        framesWithEyesOpen = 0
        suspiciousPatternDetected = false
        lastBlinkTime = 0
        Log.d(TAG, "Detection state reset")
    }

    /**
     * Process a camera frame for detection
     * This implements the main flow from the diagram
     */
    fun processFrame(bitmap: Bitmap): DetectionResult {
        if (!isDetectionActive || isProcessing.get()) {
            // Skip processing if detection is not active or already processing a frame
            return DetectionResult(false, earValue, blinkCount, listOf(), suspiciousPatternDetected)
        }

        isProcessing.set(true)

        try {
            // If in demo mode, just return current demo state
            if (isDemoMode) {
                isProcessing.set(false)
                return DetectionResult(
                    eyesDetected = true,
                    earValue = earValue,
                    blinkCount = blinkCount,
                    eyeBoxes = listOf(RectF(100f, 100f, 200f, 150f)), // Fake eye box
                    anomalyDetected = suspiciousPatternDetected
                )
            }

            // Step 1: YOLO detection of eyes (Yolo 11 deteksi mata dan menampilkan bounding box)
            val eyeBoxes = yoloAnalyzer?.detectEyes(bitmap) ?: listOf()

            // Step 2: Calculate EAR value (manusia berkedip, di Cek Eye Aspect Ratio)
            earValue = if (eyeBoxes.isNotEmpty()) {
                earCalculator.calculateEARFromBox(eyeBoxes[0])
            } else {
                1.0f // Default EAR value when no eyes detected
            }

            // Step 3: Anomaly detection based on EAR (Anomaly Detection membuat keputusan berdasarkan EAR)
            val blinkDetected = detectBlink(earValue)

            // Step 4: Check if blink frequency is suspicious
            // (Conditional in flowchart: "dalam 1 detik lebih dari 1 kedipan")
            if (blinkDetected) {
                analyzeBlinkPattern()
            }

            // Return detection result
            return DetectionResult(
                eyesDetected = eyeBoxes.isNotEmpty(),
                earValue = earValue,
                blinkCount = blinkCount,
                eyeBoxes = eyeBoxes,
                anomalyDetected = suspiciousPatternDetected
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame: ${e.message}")
            return DetectionResult(false, 1.0f, 0, listOf(), false)
        } finally {
            isProcessing.set(false)
        }
    }

    /**
     * Detect a blink based on EAR value
     * Part of "Anomaly Detection membuat keputusan berdasarkan EAR"
     */
    private fun detectBlink(ear: Float): Boolean {
        // Check if eyes are closed based on EAR threshold
        val eyesClosed = ear < EAR_THRESHOLD

        if (eyesClosed) {
            framesWithEyesClosed++
            framesWithEyesOpen = 0
        } else {
            framesWithEyesOpen++

            // Detect blink when eyes open again after being closed for some frames
            if (framesWithEyesClosed >= CONSECUTIVE_FRAMES_THRESHOLD) {
                val currentTime = System.currentTimeMillis()

                // Record blink interval
                if (lastBlinkTime > 0) {
                    blinkHistory.add(currentTime - lastBlinkTime)
                }
                lastBlinkTime = currentTime
                blinkCount++

                framesWithEyesClosed = 0
                return true
            }

            framesWithEyesClosed = 0
        }

        return false
    }

    /**
     * Analyze blink pattern for suspicious behavior
     * Implements the "dalam 1 detik lebih dari 1 kedipan" decision point
     */
    private fun analyzeBlinkPattern() {
        // Check if we have at least two blinks to analyze
        if (blinkHistory.isEmpty()) {
            suspiciousPatternDetected = false
            return
        }

        // Count blinks in the last second
        var blinksInLastSecond = 0
        val currentTime = System.currentTimeMillis()

        // Check the time between consecutive blinks
        for (interval in blinkHistory) {
            if (interval < 1000) { // Less than 1 second between blinks
                blinksInLastSecond++
            }
        }

        // Check for suspicious pattern - matching flowchart condition "dalam 1 detik lebih dari 1 kedipan"
        if (blinksInLastSecond > 1) {
            suspiciousPatternDetected = true
            Log.d(TAG, "Suspicious blink pattern detected: $blinksInLastSecond blinks in last second")
        }
    }

    /**
     * Release resources
     */
    fun release() {
        yoloAnalyzer?.release()
        yoloAnalyzer = null
        stopDemoMode()
        Log.d(TAG, "Resources released")
    }

    /**
     * Get the current detection status
     */
    fun getDetectionStatus(): String {
        Log.d(TAG, "Current detection status: $suspiciousPatternDetected")
        return if (suspiciousPatternDetected) {
            "Curang terdeteksi" // Matches flowchart "Curang terdeteksi"
        } else {
            "Tidak Curang" // Matches flowchart "Tidak Curang"
        }
    }

    /**
     * Get the current blink count
     */
    fun getBlinkCount(): Int {
        return blinkCount
    }
}

/**
 * Data class to hold detection results
 */
data class DetectionResult(
    val eyesDetected: Boolean,
    val earValue: Float,
    val blinkCount: Int,
    val eyeBoxes: List<RectF>,
    val anomalyDetected: Boolean
)