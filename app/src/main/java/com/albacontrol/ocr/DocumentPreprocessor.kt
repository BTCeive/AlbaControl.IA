package com.albacontrol.ocr

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Minimal DocumentPreprocessor stub used as a fallback in OpenCV preprocessing code. */
object DocumentPreprocessor {
    fun preprocessAndSavePdf(context: Context, bitmap: Bitmap, outPdf: File, maxDim: Int = 2000): Pair<Bitmap?, File?> {
        // conservative behaviour: return original bitmap and no PDF
        return Pair(bitmap, null)
    }
}
