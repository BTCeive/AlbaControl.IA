package com.albacontrol.util

import android.content.Context
import android.graphics.Bitmap

object DebugLogger {
    fun init(context: Context) {}
    fun log(tag: String, msg: String) {}
    fun logException(tag: String, e: Exception) {}
    fun saveCrop(context: Context, bmp: Bitmap, name: String) {}
}
