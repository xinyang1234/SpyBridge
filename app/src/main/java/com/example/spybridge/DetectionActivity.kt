package com.example.spybridge

import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.spybridge.databinding.ActivityDetectionBinding
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var tflite: Interpreter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load TFLite model
        val model = assets.open("yolo_model.tflite").use { it.readBytes() }
        tflite = Interpreter(ByteBuffer.wrap(model).order(ByteOrder.nativeOrder()))

        // Start detection
        startDetection()
    }

    private fun startDetection() {
        // Example: Capture a frame from the camera (replace with actual camera frame)
        val bitmap = captureFrame()

        // Preprocess the image for YOLO model
        val inputBuffer = preprocessImage(bitmap)

        // Run inference
        val outputBuffer = Array(1) { FloatArray(25200) } // Adjust based on YOLO output
        tflite.run(inputBuffer, outputBuffer)

        // Parse YOLO output and calculate EAR
        val eyeCoordinates = parseYOLOOutput(outputBuffer[0])
        val ear = calculateEAR(eyeCoordinates)

        // Perform anomaly detection
        if (isAnomalous(ear)) {
            binding.tvStatus.text = "Anomaly Detected!"
        } else {
            binding.tvStatus.text = "Normal"
        }
    }

    private fun captureFrame(): Bitmap {
        // Replace with actual camera frame capture logic
        return Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * 640 * 480 * 3 * 4).order(ByteOrder.nativeOrder())
        // Preprocess the bitmap (resize, normalize, etc.)
        // Add your preprocessing logic here
        return inputBuffer
    }

    private fun parseYOLOOutput(output: FloatArray): List<Pair<Float, Float>> {
        // Parse YOLO output to extract eye coordinates
        // Replace with actual parsing logic
        return listOf(Pair(100f, 200f), Pair(150f, 200f)) // Example coordinates
    }

    private fun calculateEAR(eyeCoordinates: List<Pair<Float, Float>>): Float {
        // Calculate EAR based on eye coordinates
        val (x1, y1) = eyeCoordinates[0]
        val (x2, y2) = eyeCoordinates[1]
        return (y2 - y1) / (x2 - x1)
    }

    private fun isAnomalous(ear: Float): Boolean {
        // Define anomaly detection logic
        return ear < 0.2 // Example threshold
    }
}