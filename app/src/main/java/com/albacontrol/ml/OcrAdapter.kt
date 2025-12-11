package com.albacontrol.ml

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OcrAdapter {
  // For non-Tess flows: callback style
  fun processBitmap(bitmap: Bitmap, callback: (OCRResult?, Exception?) -> Unit) {
    try {
      // call existing OcrProcessor if available
      com.albacontrol.ml.OcrProcessor.processBitmap(bitmap) { res, err ->
        callback(res, err)
      }
    } catch (e: Exception) {
      callback(null, e)
    }
  }

  // For Tess suspend signature: keep compatibility wrapper
  suspend fun processBitmapWithTess(context: Context, bitmap: Bitmap): OCRResult? {
    return withContext(Dispatchers.IO) {
      try {
        com.albacontrol.ml.TessOcrProcessor.processBitmap(context, bitmap)
      } catch (e: Exception) {
        null
      }
    }
  }
}
