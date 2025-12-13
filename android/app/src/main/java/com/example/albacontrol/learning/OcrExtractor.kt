package com.example.albacontrol.learning

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

object OcrExtractor {
    fun extractWithCoordinates(context: Context, bitmap: Bitmap): JSONObject {
        return OcrEngine.runOcr(context, bitmap, "spa")
    }
}
package com.example.albacontrol.learning

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

object OcrExtractor {
    fun extractWithCoordinates(bitmap: Bitmap): JSONObject {
        // Placeholder: integrar MLKit o Tesseract
        val result = JSONObject()
        val words = JSONArray()

        // TODO: implementar OCR real
        // Ejemplo de salida:
        // words.put(JSONObject().apply {
        //     put("text", "Proveedor")
        //     put("x", 120)
        //     put("y", 340)
        //     put("w", 80)
        //     put("h", 20)
        // })

        result.put("words", words)
        return result
    }
}
