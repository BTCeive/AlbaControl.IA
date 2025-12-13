package com.example.albacontrol.learning

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object TessdataInstaller {

    fun ensureTrainedData(context: Context, lang: String) {
        val tessDir = File(context.filesDir, "tessdata")
        if (!tessDir.exists()) tessDir.mkdirs()

        val trainedData = File(tessDir, "$lang.traineddata")
        if (!trainedData.exists()) {
            context.assets.open("tessdata/$lang.traineddata").use { input ->
                FileOutputStream(trainedData).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
