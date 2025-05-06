package com.example.spybridge

import android.util.Log

/**
 * BlinkAnalyzer implements the blink detection and pattern analysis
 * This implements the "Anomaly Detection" step in the flowchart
 */
class BlinkAnalyzer {
    private val TAG = "BlinkAnalyzer"

    // Blink detection parameters
    private val EAR_THRESHOLD = 0.2f
    private val CONSECUTIVE_FRAMES_THRESHOLD = 3

    // Blink state
    private var isEyeClosed = false
    private var framesWithEyesClosed = 0
    private var framesWithEyesOpen = 0

    // Blink history
    private var blinkCount = 0
    private val blinkTimestamps = mutableListOf<Long>()
    private var lastBlinkTime: Long = 0

    // Suspicious pattern detection
    private var suspiciousPatternDetected = false

    /**
     * Detect a blink based on EAR value
     * This is part of the "Cek Eye Aspect Ratio (EAR)" in the flowchart
     */
    fun detectBlink(earValue: Float): Boolean {
        // Current eye state
        val currentlyEyeClosed = earValue < EAR_THRESHOLD

        // Track eye state
        if (currentlyEyeClosed) {
            framesWithEyesClosed++
            framesWithEyesOpen = 0

            // Just closed eyes
            if (!isEyeClosed && framesWithEyesClosed >= CONSECUTIVE_FRAMES_THRESHOLD) {
                isEyeClosed = true
                Log.d(TAG, "Eyes closed (EAR: $earValue)")
            }
        } else {
            framesWithEyesOpen++

            // Just opened eyes after being closed
            if (isEyeClosed && framesWithEyesOpen >= 1) {
                isEyeClosed = false

                // Record blink
                val currentTime = System.currentTimeMillis()
                if (lastBlinkTime > 0) {
                    val timeSinceLastBlink = currentTime - lastBlinkTime
                    blinkTimestamps.add(timeSinceLastBlink)
                    Log.d(TAG, "Time since last blink: $timeSinceLastBlink ms")
                }

                lastBlinkTime = currentTime
                blinkCount++

                Log.d(TAG, "Blink detected! Count: $blinkCount")

                // Clean up old blinks (older than 5 seconds)
                cleanupOldBlinks(currentTime)

                // Analyze blink pattern
                analyzePattern()

                return true
            }

            framesWithEyesClosed = 0
        }

        return false
    }

    /**
     * Clean up old blinks from history
     */
    private fun cleanupOldBlinks(currentTime: Long) {
        // Keep only blinks from the last 5 seconds
        val cutoffTime = currentTime - 5000

        // Filter out timestamps older than cutoff time
        // We can't modify blinkTimestamps directly here, so create a new list
        val recentBlinks = blinkTimestamps.filter {
            lastBlinkTime - it > cutoffTime
        }

        blinkTimestamps.clear()
        blinkTimestamps.addAll(recentBlinks)
    }

    /**
     * Analyze blink pattern for suspicious behavior
     * This implements the "dalam 1 detik lebih dari 1 kedipan" decision in the flowchart
     */
    fun analyzePattern() {
        // We need at least 2 blinks to detect a pattern
        if (blinkTimestamps.size < 1) {
            suspiciousPatternDetected = false
            return
        }

        // Check if there's a blink interval less than 1 second
        // This directly implements the flowchart condition "dalam 1 detik lebih dari 1 kedipan"
        for (interval in blinkTimestamps) {
            if (interval < 1000) { // Less than 1 second between blinks
                Log.d(TAG, "Suspicious blink pattern detected: interval = $interval ms")
                suspiciousPatternDetected = true
                return
            }
        }

        suspiciousPatternDetected = false
    }

    /**
     * Check if the current pattern is suspicious
     */
    fun isSuspiciousPattern(): Boolean {
        return suspiciousPatternDetected
    }

    /**
     * Reset analyzer state
     */
    fun reset() {
        isEyeClosed = false
        framesWithEyesClosed = 0
        framesWithEyesOpen = 0
        blinkCount = 0
        blinkTimestamps.clear()
        lastBlinkTime = 0
        suspiciousPatternDetected = false

        Log.d(TAG, "Blink analyzer reset")
    }

    /**
     * Get current blink count
     */
    fun getBlinkCount(): Int {
        return blinkCount
    }

    /**
     * Get debug info
     */
    fun getDebugInfo(): String {
        return "Blinks: $blinkCount, " +
                "Suspicious: $suspiciousPatternDetected, " +
                "Recent intervals: ${blinkTimestamps.joinToString()}"
    }
}