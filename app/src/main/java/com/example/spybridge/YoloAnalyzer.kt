package com.example.spybridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YoloAnalyzer handles the YOLO model for eye detection
 * This implements the "Yolo 11 deteksi mata" step in the flowchart
 */
class YoloAnalyzer(private val context: Context) {
    private val TAG = "YoloAnalyzer"

    // TFLite model
    private var interpreter: Interpreter? = null

    // Model configuration
    private val INPUT_SIZE = 640 // YOLO input size
    private val MODEL_FILE = "yolo_model.tflite"
    private val DETECTION_THRESHOLD = 0.5f

    /**
     * Initialize the YOLO model
     */
    fun initialize(): Boolean {
        try {
            Log.d(TAG, "Loading YOLO model...")
            interpreter = Interpreter(loadModelFile())
            Log.d(TAG, "YOLO model loaded successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing YOLO model: ${e.message}")
            return false
        }
    }

    /**
     * Detect eyes in the input bitmap
     * This corresponds to "Yolo 11 deteksi mata" in the flowchart
     */
    fun detectEyes(bitmap: Bitmap): List<RectF> {
        if (interpreter == null) {
            Log.e(TAG, "Interpreter not initialized")
            return listOf()
        }

        try {
            // Preprocess image
            val inputBuffer = preprocessImage(bitmap)

            // Output buffer for detection results
            // Adjust the output shape based on your specific YOLO model
            val outputBuffer = Array(1) { FloatArray(25200) } // Typical YOLOv5 output shape

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Parse output to get eye bounding boxes
            return parseYoloOutput(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting eyes: ${e.message}")
            return listOf()
        }
    }

    /**
     * Preprocess image for YOLO model
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // Resize to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Create input buffer
        val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        // Convert bitmap to float values
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        resizedBitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val value = intValues[pixel++]

                // Normalize pixel values to 0-1
                inputBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f) // R
                inputBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)  // G
                inputBuffer.putFloat((value and 0xFF) / 255.0f)          // B
            }
        }

        return inputBuffer
    }

    /**
     * Parse YOLO output to get eye bounding boxes
     */
    private fun parseYoloOutput(output: FloatArray, imageWidth: Int, imageHeight: Int): List<RectF> {
        val boxes = mutableListOf<RectF>()

        // YOLO output is typically [boxes, confidence, classes]
        // This parsing logic may need adjustment based on your model
        val boxCount = output.size / 7 // Typically 7 values per box (x, y, w, h, conf, class1, class2)

        for (i in 0 until boxCount) {
            val baseIndex = i * 7
            val confidence = output[baseIndex + 4]

            // Check confidence threshold
            if (confidence < DETECTION_THRESHOLD) continue

            // Check if it's an eye (class index 0)
            // Adjust this based on your model's class mapping
            val classEyeProbability = output[baseIndex + 5]
            if (classEyeProbability < DETECTION_THRESHOLD) continue

            // Get box coordinates (normalized 0-1)
            val x = output[baseIndex]
            val y = output[baseIndex + 1]
            val w = output[baseIndex + 2]
            val h = output[baseIndex + 3]

            // Convert to pixel coordinates in original image
            val left = (x - w/2) * imageWidth
            val top = (y - h/2) * imageHeight
            val right = (x + w/2) * imageWidth
            val bottom = (y + h/2) * imageHeight

            // Create bounding box
            boxes.add(RectF(left, top, right, bottom))
        }

        Log.d(TAG, "Detected ${boxes.size} eyes")
        return boxes
    }

    /**
     * Load TFLite model from assets
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Release resources
     */
    fun release() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "YOLO analyzer resources released")
    }
}