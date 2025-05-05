package com.example.spybridge.ml

import android.util.Log

class BlinkAnalyzer {
    private val TAG = "BlinkAnalyzer"

    // EAR threshold for detecting blinks
    private val earThreshold = 0.2f

    // State variables to track blink state
    private var previousBlinkState = false
    private var consecutiveFramesBelow = 0

    // Number of consecutive frames required to confirm blink
    private val minBlinkFrames = 2

    // Reset after some time without a blink
    private var framesSinceLastBlink = 0
    private val resetFrames = 10

    // Detect a blink based on EAR value
    fun detectBlink(earValue: Float): Boolean {
        var blinkDetected = false

        // Increment frames since last blink counter
        framesSinceLastBlink++

        // Reset state if it's been too long since last blink event
        if (framesSinceLastBlink > resetFrames) {
            consecutiveFramesBelow = 0
            previousBlinkState = false
        }

        // Check if current EAR is below threshold
        val currentBlinkState = earValue < earThreshold

        // Count consecutive frames below threshold
        if (currentBlinkState) {
            consecutiveFramesBelow++
            Log.d(TAG, "Eye closed, consecutive frames: $consecutiveFramesBelow")
        } else {
            // If we had enough consecutive frames and now eyes are open again,
            // we can consider it a complete blink
            if (previousBlinkState && consecutiveFramesBelow >= minBlinkFrames) {
                blinkDetected = true
                framesSinceLastBlink = 0
                Log.d(TAG, "Blink detected! EAR: $earValue")
            }
            consecutiveFramesBelow = 0
        }

        previousBlinkState = currentBlinkState
        return blinkDetected
    }

    // Reset the detector state
    fun reset() {
        previousBlinkState = false
        consecutiveFramesBelow = 0
        framesSinceLastBlink = 0
    }
}