package com.example.spybridge.ml

import android.graphics.Bitmap
import android.util.Log
import com.example.spybridge.models.EyeDetection
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class YoloAnalyzer(private val tflite: Interpreter) {
    private val TAG = "YoloAnalyzer"

    // Model configuration
    private val inputWidth = 640
    private val inputHeight = 640
    private val numChannels = 3
    private val numClasses = 4 // eye, iris, pupil, etc.
    private val numBoxes = 8400 // Model dependent (for YOLOv8n)
    private val outputSize = 84 // This is model dependent

    // Constants
    private val confidenceThreshold = 0.45f
    private val iouThreshold = 0.5f
    private val eyeClassIndices = listOf(0, 1) // Indices for eye classes

    // Detect eyes from bitmap
    fun detectEyes(bitmap: Bitmap): List<EyeDetection> {
        try {
            // Preprocess image
            val inputBuffer = preprocessImage(bitmap)

            // Prepare output buffer
            val outputBuffer = Array(1) { Array(outputSize) { FloatArray(numBoxes) } }

            // Run inference
            tflite.run(inputBuffer, outputBuffer)

            // Process detections
            return processDetections(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
            return emptyList()
        }
    }

    // Preprocess bitmap to TensorFlow Lite input
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val byteBuffer = ByteBuffer.allocateDirect(
            1 * inputWidth * inputHeight * numChannels * 4 // 4 bytes per float
        ).apply {
            order(ByteOrder.nativeOrder())
        }

        val pixels = IntArray(inputWidth * inputHeight)
        scaledBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixelValue in pixels) {
            // Extract RGB values and normalize to [0,1]
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        return byteBuffer
    }

    // Process output from TensorFlow Lite
    private fun processDetections(
        outputBuffer: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<EyeDetection> {
        val detections = mutableListOf<EyeDetection>()

        for (i in 0 until numBoxes) {
            // Extract bounding box coordinates (x, y, w, h)
            val x = outputBuffer[0][i]
            val y = outputBuffer[1][i]
            val w = outputBuffer[2][i]
            val h = outputBuffer[3][i]

            // Find class with highest confidence
            var maxConfidence = 0f
            var classId = -1

            for (c in 0 until numClasses) {
                val confidence = outputBuffer[c + 4][i]
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    classId = c
                }
            }

            // Filter by confidence threshold and class
            if (maxConfidence > confidenceThreshold && eyeClassIndices.contains(classId)) {
                // Convert normalized coordinates to image coordinates
                val xMin = ((x - w / 2) * imageWidth).toInt().coerceIn(0, imageWidth)
                val yMin = ((y - h / 2) * imageHeight).toInt().coerceIn(0, imageHeight)
                val xMax = ((x + w / 2) * imageWidth).toInt().coerceIn(0, imageWidth)
                val yMax = ((y + h / 2) * imageHeight).toInt().coerceIn(0, imageHeight)

                // Create detection
                val detection = EyeDetection(
                    xMin = xMin,
                    yMin = yMin,
                    width = xMax - xMin,
                    height = yMax - yMin,
                    confidence = maxConfidence,
                    classId = classId
                )

                detections.add(detection)
            }
        }

        // Apply non-maximum suppression
        return applyNMS(detections)
    }

    // Apply Non-Maximum Suppression to remove overlapping boxes
    private fun applyNMS(detections: List<EyeDetection>): List<EyeDetection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<EyeDetection>()

        val visited = BooleanArray(sortedDetections.size) { false }

        for (i in sortedDetections.indices) {
            if (visited[i]) continue

            val detection1 = sortedDetections[i]
            selectedDetections.add(detection1)

            for (j in i + 1 until sortedDetections.size) {
                if (visited[j]) continue

                val detection2 = sortedDetections[j]
                if (calculateIoU(detection1, detection2) > iouThreshold) {
                    visited[j] = true
                }
            }
        }

        return selectedDetections
    }

    // Calculate Intersection over Union (IoU)
    private fun calculateIoU(box1: EyeDetection, box2: EyeDetection): Float {
        val x1min = box1.xMin
        val y1min = box1.yMin
        val x1max = box1.xMin + box1.width
        val y1max = box1.yMin + box1.height

        val x2min = box2.xMin
        val y2min = box2.yMin
        val x2max = box2.xMin + box2.width
        val y2max = box2.yMin + box2.height

        // Calculate intersection area
        val xOverlap = max(0, min(x1max, x2max) - max(x1min, x2min))
        val yOverlap = max(0, min(y1max, y2max) - max(y1min, y2min))
        val intersectionArea = xOverlap * yOverlap

        // Calculate union area
        val box1Area = box1.width * box1.height
        val box2Area = box2.width * box2.height
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea.toFloat() / unionArea else 0f
    }
}