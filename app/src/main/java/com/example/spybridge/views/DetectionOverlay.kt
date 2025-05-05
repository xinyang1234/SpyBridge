package com.example.spybridge.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.example.spybridge.R
import com.example.spybridge.models.EyeDetection

class DetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        color = context.getColor(R.color.green)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val earPaint = Paint().apply {
        color = context.getColor(R.color.light_blue)
        style = Paint.Style.FILL
        strokeWidth = 2f
    }

    private var detections: List<EyeDetection> = emptyList()
    private var earValue: Float = 0f
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var scaleFactor: Float = 1f

    // Set current detections to be displayed
    fun setDetections(
        detections: List<EyeDetection>,
        earValue: Float,
        previewWidth: Int,
        previewHeight: Int
    ) {
        this.detections = detections
        this.earValue = earValue
        this.previewWidth = previewWidth
        this.previewHeight = previewHeight

        // Calculate scale factor between preview size and overlay size
        scaleFactor = minOf(
            width.toFloat() / previewWidth.toFloat(),
            height.toFloat() / previewHeight.toFloat()
        )

        invalidate() // Trigger redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty() || previewWidth <= 0 || previewHeight <= 0) {
            return
        }

        // Calculate padding to center the preview
        val paddingX = (width - previewWidth * scaleFactor) / 2
        val paddingY = (height - previewHeight * scaleFactor) / 2

        // Draw bounding boxes for each detection
        for (detection in detections) {
            // Scale detection coordinates to view
            val left = detection.xMin * scaleFactor + paddingX
            val top = detection.yMin * scaleFactor + paddingY
            val right = (detection.xMin + detection.width) * scaleFactor + paddingX
            val bottom = (detection.yMin + detection.height) * scaleFactor + paddingY

            // Adjust box color based on confidence
            val alpha = (detection.confidence * 255).toInt().coerceIn(100, 255)
            boxPaint.alpha = alpha

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw label
            val label = String.format("%.0f%%", detection.confidence * 100)
            val textBounds = Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            // Draw background for text
            canvas.drawRect(
                left,
                top - textBounds.height() - 8,
                left + textBounds.width() + 8,
                top,
                bgPaint
            )

            // Draw text
            canvas.drawText(label, left + 4, top - 4, textPaint)

            // Draw EAR indicator
            val earLabel = String.format("EAR: %.2f", earValue)
            val earBounds = Rect()
            textPaint.getTextBounds(earLabel, 0, earLabel.length, earBounds)

            // Position at bottom right of screen
            val earX = width - earBounds.width() - 20f
            val earY = height - 20f

            // Draw background
            canvas.drawRect(
                earX - 8,
                earY - earBounds.height() - 8,
                earX + earBounds.width() + 8,
                earY + 8,
                bgPaint
            )

            // Draw text
            canvas.drawText(earLabel, earX, earY, textPaint)
        }
    }
}