package com.example.albacontrol.learning

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject

object OcrEngine {

    fun runOcr(context: Context, bitmap: Bitmap, lang: String = "spa"): JSONObject {
        val tess = TessBaseAPI()
        val dataPath = context.filesDir.absolutePath

        // Copiar modelos si no existen
        TessdataInstaller.ensureTrainedData(context, lang)

        tess.init(dataPath, lang)
        tess.setImage(bitmap)

        val words = tess.words
        val arr = JSONArray()

        for (w in words) {
            val obj = JSONObject()
            obj.put("text", w.text)
            obj.put("x", w.boundingBox.left)
            obj.put("y", w.boundingBox.top)
            obj.put("w", w.boundingBox.width())
            obj.put("h", w.boundingBox.height())
            arr.put(obj)
        }

        tess.end()

        val root = JSONObject()
        root.put("words", arr)
        return root
    }
}
