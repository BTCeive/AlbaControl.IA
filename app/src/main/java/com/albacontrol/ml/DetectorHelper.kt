package com.albacontrol.ml

import android.content.Context
import android.graphics.Bitmap

/** Minimal DetectorHelper stub to satisfy compile-time references. */
object DetectorHelper {
    // Create a detector placeholder; returning null is acceptable for compilation.
    fun createDetector(context: Context, modelPath: String): Any? {
        // Real implementation uses TFLite Task Vision; stub returns null
        return null
    }

    // Detect bitmap and return empty list of detections (placeholder)
    fun detectBitmap(detector: Any?, bitmap: Bitmap, context: Context): List<Map<String, Any>> {
        return emptyList()
    }
}
