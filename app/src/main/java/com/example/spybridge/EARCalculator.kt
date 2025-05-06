package com.example.spybridge

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * EARCalculator calculates the Eye Aspect Ratio (EAR) that is used
 * for blink detection as shown in the flowchart.
 */
class EARCalculator {
    /**
     * Calculate Euclidean distance between two points
     */
    private fun distance(p1: PointF, p2: PointF): Float {
        return sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
    }

    /**
     * Calculate Eye Aspect Ratio (EAR) using the formula:
     * EAR = (||p2-p6|| + ||p3-p5||) / (2 * ||p1-p4||)
     *
     * Where p1-p6 are the eye landmark points:
     * p1: left corner
     * p2: top left
     * p3: top right
     * p4: right corner
     * p5: bottom right
     * p6: bottom left
     */
    fun calculateEAR(landmarks: List<PointF>): Float {
        if (landmarks.size < 6) {
            return 1.0f // Default EAR when not enough landmarks
        }

        // Extract landmarks
        val p1 = landmarks[0] // left corner
        val p2 = landmarks[1] // top left
        val p3 = landmarks[2] // top right
        val p4 = landmarks[3] // right corner
        val p5 = landmarks[4] // bottom right
        val p6 = landmarks[5] // bottom left

        // Calculate vertical distances
        val verticalDist1 = distance(p2, p6)
        val verticalDist2 = distance(p3, p5)

        // Calculate horizontal distance
        val horizontalDist = distance(p1, p4)

        // Avoid division by zero
        if (horizontalDist == 0f) {
            return 1.0f
        }

        // Calculate EAR
        return (verticalDist1 + verticalDist2) / (2.0f * horizontalDist)
    }

    /**
     * Simplified EAR calculation using just the bounding box
     * This is used when detailed landmarks are not available
     */
    fun calculateEARFromBox(eyeBox: RectF): Float {
        val height = eyeBox.height()
        val width = eyeBox.width()

        // Avoid division by zero
        if (width == 0f) {
            return 1.0f
        }

        // Simple ratio of height to width
        return height / width
    }

    /**
     * Detect a blink based on EAR value and threshold
     * This directly implements the check in "Cek Eye Aspect Ratio (EAR)"
     * step of the flowchart
     */
    fun isBlinking(ear: Float, threshold: Float = 0.2f): Boolean {
        return ear < threshold
    }
}