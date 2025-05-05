package com.example.spybridge.models

data class EyeDetection(
    val xMin: Int,
    val yMin: Int,
    val width: Int,
    val height: Int,
    val confidence: Float,
    val classId: Int
)