package com.example.albacontrol.learning

import android.app.Activity
import android.os.Bundle
import android.graphics.BitmapFactory
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class TestOcrActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Cargar imagen de assets/test_images/sample.jpg
            val am = assets
            val `is` = am.open("test_images/sample.jpg")
            val bmp = BitmapFactory.decodeStream(`is`)
            `is`.close()

            // Ejecutar OCR (usa OcrExtractor que ya implementaste)
            val ocrJson = OcrExtractor.extractWithCoordinates(this, bmp)

            // Guardar JSON en filesDir/ocr_result.json
            val outFile = File(filesDir, "ocr_result.json")
            FileOutputStream(outFile).use { fos ->
                fos.write(ocrJson.toString(2).toByteArray(Charsets.UTF_8))
            }

            Toast.makeText(this, "OCR guardado: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error OCR: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Cerrar actividad inmediatamente (no UI)
        finish()
    }
}
