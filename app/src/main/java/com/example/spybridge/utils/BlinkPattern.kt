package com.example.spybridge.utils

import kotlin.math.abs
import kotlin.math.pow

class BlinkPattern {
    // Data class to store blink events
    data class BlinkEvent(
        val timestamp: Long,
        val earValue: Float
    )

    companion object {
        // Analyze patterns in timestamps
        fun analyzeBlinkPattern(blinkEvents: List<BlinkEvent>): Any {
            if (blinkEvents.isEmpty()) {
                return mapOf(
                    "count" to 0,
                    "rate" to 0.0f,
                    "pattern" to "No blinks detected",
                    "suspicious" to false
                )
            }

            // Calculate time span
            val firstTimestamp = blinkEvents.first().timestamp
            val lastTimestamp = blinkEvents.last().timestamp
            val timeSpanMs = (lastTimestamp - firstTimestamp).toDouble()

            // Calculate blink rate (blinks per minute)
            val count = blinkEvents.size
            val blinkRate = if (timeSpanMs > 0) (count / (timeSpanMs / 60000)) else 0.0

            // Analyze intervals between blinks
            val intervals = mutableListOf<Long>()
            for (i in 1 until blinkEvents.size) {
                val interval = blinkEvents[i].timestamp - blinkEvents[i-1].timestamp
                intervals.add(interval)
            }

            // Calculate statistics for intervals
            val stats = calculateStatistics(intervals)

            // Determine pattern type
            var pattern = "Normal"
            var suspicious = false

            // Check for suspicious patterns
            if (count > 0) {
                if (blinkRate > 50) {
                    pattern = "Rapid blinking"
                    suspicious = true
                } else if (blinkRate < 5 && timeSpanMs > 30000) { // Less than 5 blinks per minute over 30+ seconds
                    pattern = "Very infrequent blinking"
                    suspicious = true
                } else if (stats["cv"]!! < 0.2 && intervals.size >= 3) { // Low coefficient of variation = rhythmic pattern
                    pattern = "Rhythmic blinking"
                    suspicious = true
                } else if (stats["cv"]!! > 1.0) { // High coefficient of variation = erratic pattern
                    pattern = "Erratic blinking"
                    suspicious = false // This is actually common in normal behavior
                }

                // Check for rapid succession blinks (more than 3 blinks within 0.4 seconds)
                var rapidBlinks = 0
                val maxBlinkDuration = 400 // 0.4 seconds in milliseconds

                if (intervals.size >= 2) {
                    for (i in 0 until intervals.size - 1) {
                        if (intervals[i] + intervals[i+1] < maxBlinkDuration) {
                            rapidBlinks++
                        }
                    }

                    if (rapidBlinks > 0) {
                        pattern = "Coded blinking"
                        suspicious = true
                    }
                }
            }

            return mapOf(
                "count" to count,
                "rate" to blinkRate,
                "avgInterval" to stats["mean"],
                "stdDevInterval" to stats["stdDev"],
                "pattern" to pattern,
                "suspicious" to suspicious
            )
        }

        // Calculate statistics for a list of values
        private fun calculateStatistics(values: List<Long>): Map<String, Double> {
            if (values.isEmpty()) {
                return mapOf("mean" to 0.0, "stdDev" to 0.0, "cv" to 0.0)
            }

            // Calculate mean
            val sum = values.sum().toDouble()
            val mean = sum / values.size

            // Calculate standard deviation
            var sumSquaredDiff = 0.0
            for (value in values) {
                sumSquaredDiff += (value - mean).pow(2)
            }
            val variance = sumSquaredDiff / values.size
            val stdDev = variance.pow(0.5)

            // Calculate coefficient of variation (CV)
            val cv = if (mean > 0) stdDev / mean else 0.0

            return mapOf("mean" to mean, "stdDev" to stdDev, "cv" to cv)
        }

        // Simple power function
        private fun Double.pow(exponent: Double): Double {
            return Math.pow(this, exponent)
        }

        // Detect rhythmic patterns in intervals
        fun detectRhythmicPattern(intervals: List<Long>): Boolean {
            if (intervals.size < 3) return false

            // Threshold for considering two intervals similar
            val similarityThreshold = 50L // milliseconds

            // Check for repeating patterns
            for (i in 0 until intervals.size - 2) {
                for (j in i + 1 until intervals.size - 1) {
                    // Check if two successive intervals are similar
                    if (abs(intervals[i] - intervals[j]) < similarityThreshold &&
                        abs(intervals[i+1] - intervals[j+1]) < similarityThreshold) {
                        return true // Found a repeating pattern
                    }
                }
            }

            return false
        }
    }
}