package com.albacontrol.ml

import android.content.Context

/** Minimal TFLiteMobileNetWrapper stub to satisfy references. */
class TFLiteMobileNetWrapper(private val context: Context) {
    fun loadModel() {
        // no-op stub for compile-time
    }
    fun runInference(bitmap: android.graphics.Bitmap): FloatArray? {
        return null
    }
    fun close() {}
}
