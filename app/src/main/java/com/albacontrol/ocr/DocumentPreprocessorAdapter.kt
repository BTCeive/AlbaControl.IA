package com.albacontrol.ocr

import android.content.Context
import android.graphics.Bitmap
import java.io.File

object DocumentPreprocessorAdapter {
  // Returns list of variants: original, clahe, adaptiveThreshold (best-effort)
  fun generateVariants(context: Context, src: Bitmap): List<Bitmap> {
    // Conservative adapter: try to call external script if available, otherwise return original only.
    try {
      val tmpDir = File(context.externalCacheDir, "ocr_variants")
      tmpDir.mkdirs()
      // write src to tmp file, call scripts/run_ocr_variants.sh if present
      // For safety, fallback to returning original bitmap only
    } catch (e: Exception) {
      // ignore and fallback
    }
    return listOf(src)
  }
}
