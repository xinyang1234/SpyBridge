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

    // Constants
    private val confidenceThreshold = 0.45f
    private val iouThreshold = 0.5f
    private val eyeClassIndices = listOf(0, 1) // Indices for eye classes

    init {
        // Print input and output tensor info for debugging
        val inputTensor = tflite.getInputTensor(0)
        val outputTensor = tflite.getOutputTensor(0)
        Log.d(TAG, "Input tensor shape: ${inputTensor.shape().contentToString()}")
        Log.d(TAG, "Output tensor shape: ${outputTensor.shape().contentToString()}")
    }

    // Detect eyes from bitmap
    fun detectEyes(bitmap: Bitmap): List<EyeDetection> {
        try {
            // Preprocess image
            val inputBuffer = preprocessImage(bitmap)

            // Get output shape from the model
            val outputShape = tflite.getOutputTensor(0).shape()

            // Create output buffer with correct shape based on the model
            // YOLOv8 output is [1, 84, 8400] for 4 classes
            val outputBuffer = Array(1) {
                Array(outputShape[1]) {
                    FloatArray(outputShape[2])
                }
            }

            // Run inference
            tflite.run(inputBuffer, outputBuffer)

            // Process detections
            return processDetections(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            e.printStackTrace()
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

        // Reset the buffer position
        byteBuffer.rewind()

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

        try {
            // YOLOv8 output format is [classes+box_params, num_boxes]
            // First 4 values are box coordinates, next values are class confidences
            for (i in 0 until numBoxes) {
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
                    // Extract bounding box coordinates (x, y, w, h)
                    val x = outputBuffer[0][i]
                    val y = outputBuffer[1][i]
                    val w = outputBuffer[2][i]
                    val h = outputBuffer[3][i]

                    // Convert normalized coordinates to image coordinates
                    val xMin = ((x - w / 2) * imageWidth).toInt().coerceIn(0, imageWidth)
                    val yMin = ((y - h / 2) * imageHeight).toInt().coerceIn(0, imageHeight)
                    val xMax = ((x + w / 2) * imageWidth).toInt().coerceIn(0, imageWidth)
                    val yMax = ((y + h / 2) * imageHeight).toInt().coerceIn(0, imageHeight)

                    val width = xMax - xMin
                    val height = yMax - yMin

                    // Only add valid detections
                    if (width > 0 && height > 0) {
                        // Create detection
                        val detection = EyeDetection(
                            xMin = xMin,
                            yMin = yMin,
                            width = width,
                            height = height,
                            confidence = maxConfidence,
                            classId = classId
                        )

                        detections.add(detection)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing detections: ${e.message}", e)
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