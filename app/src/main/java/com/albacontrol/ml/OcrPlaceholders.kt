package com.albacontrol.ml

import android.graphics.Rect

data class OCRBlock(val text: String, val bbox: Rect)
data class OCRResultPlaceholder(val allBlocks: List<Pair<String, Rect>> = emptyList())
