package com.googlecode.tesseract.android

import android.graphics.Bitmap

// Minimal stub for tess-two's TessBaseAPI to allow compilation in environments
// where tess-two is not available. Methods implemented are no-ops and return
// conservative defaults; this file is intended as a temporary compile-time stub.
class TessBaseAPI {
    var utF8Text: String? = null
    fun init(datapath: String, lang: String): Boolean { return false }
    fun setImage(bitmap: Bitmap) {}
    fun setPageSegMode(mode: Int) {}
    fun setVariable(name: String, value: String) {}
    fun clear() {}
    fun end() {}
}
