package com.albacontrol.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simple Tesseract-based OCR wrapper that replaces ML Kit usage.
 * Requires tessdata (*.traineddata) files to be present under
 * `context.filesDir/tess/tessdata/` (or copied there at first run).
 */
object TessOcrProcessor {

    suspend fun processBitmap(context: Context, bitmap: Bitmap, lang: String = "spa") : com.albacontrol.ml.OCRResult = withContext(Dispatchers.IO) {
        com.albacontrol.util.DebugLogger.init(context)
        com.albacontrol.util.DebugLogger.log("TessOcrProcessor", "processBitmap: start (w=${bitmap.width}, h=${bitmap.height})")
        val dataPath = context.filesDir.absolutePath + "/tess/"
        val tessdata = java.io.File(dataPath + "tessdata")
        if (!tessdata.exists() || tessdata.listFiles().isNullOrEmpty()) {
            // Try to initialize/copy traineddata via TesseractOcrProcessor helper
            val ok = try {
                TesseractOcrProcessor.init(context)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                com.albacontrol.util.DebugLogger.log("TessOcrProcessor", "tessdata init failed at ${tessdata.absolutePath}")
                throw RuntimeException("Tessdata not found under ${tessdata.absolutePath}. Please place traineddata files there (e.g. spa.traineddata)")
            }
        }

        val api = TessBaseAPI()
            try {
                val ok = api.init(dataPath, lang)
                if (!ok) {
                    com.albacontrol.util.DebugLogger.log("TessOcrProcessor", "Tesseract api.init failed for datapath=$dataPath lang=$lang")
                    throw RuntimeException("Tesseract init failed for datapath=$dataPath lang=$lang")
                }
            api.setImage(bitmap)
            val text = api.utF8Text ?: ""

            // Build a simple lines list
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

            // Helper to run a focused tess pass with optional psm and whitelist
            fun runTessWithConfig(img: Bitmap, psm: Int? = null, whitelist: String? = null): String {
                val api2 = TessBaseAPI()
                try {
                    val ok2 = api2.init(dataPath, lang)
                    if (!ok2) return ""
                    if (psm != null) api2.setPageSegMode(psm)
                    if (!whitelist.isNullOrBlank()) {
                        try { api2.setVariable("tessedit_char_whitelist", whitelist) } catch (_: Exception) {}
                    }
                    api2.setImage(img)
                    return api2.utF8Text ?: ""
                } catch (_: Exception) {
                    return ""
                } finally {
                    try { api2.clear() } catch (_: Exception) {}
                    try { api2.end() } catch (_: Exception) {}
                }
            }

            // Simple heuristics: provider, nif, date, number
            var proveedor: String? = null
            var nif: String? = null
            var numero: String? = null
            var fecha: String? = null

            val dateRegex = Regex("\\b(\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4})\\b")
            val nifRegex1 = Regex("\\b\\d{8}[A-Z]\\b")
            val nifRegex2 = Regex("\\b[A-Z0-9]{1}\\d{7}[A-Z0-9]{1}\\b")
            val numberRegex = Regex("\\d+")

            for (l in lines) {
                if (proveedor == null) {
                    val low = l.lowercase()
                    if (!dateRegex.containsMatchIn(l) && !numberRegex.matches(l.replace(" ", "")) && !low.contains("total") && !low.contains("factura") && !low.contains("iva")) {
                        proveedor = l
                    }
                }
                if (nif == null && (nifRegex1.containsMatchIn(l) || nifRegex2.containsMatchIn(l))) {
                    nif = (nifRegex1.find(l) ?: nifRegex2.find(l))?.value
                }
                if (fecha == null && dateRegex.containsMatchIn(l)) {
                    fecha = dateRegex.find(l)?.value
                }
                if (numero == null && l.lowercase().contains("albar")) {
                    val m = numberRegex.find(l)
                    if (m != null) numero = m.value
                }
            }

            // If NIF wasn't found reliably, try a focused pass with whitelist + single-line PSM
            if (nif.isNullOrBlank()) {
                try {
                    val refined = runTessWithConfig(bitmap, 7, "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                    val m1 = nifRegex1.find(refined) ?: nifRegex2.find(refined)
                    if (m1 != null) nif = m1.value
                    com.albacontrol.util.DebugLogger.log("TessOcrProcessor", "refined NIF pass length=${refined.length} nif=$nif")
                } catch (_: Exception) {}
            }

            // If numero (albaran number) not found, try single-line pass and search for first integer-looking token
            if (numero.isNullOrBlank()) {
                try {
                    val refinedNum = runTessWithConfig(bitmap, 7, "0123456789")
                    val m = numberRegex.find(refinedNum)
                    if (m != null) numero = m.value
                    com.albacontrol.util.DebugLogger.log("TessOcrProcessor", "refined numero pass length=${refinedNum.length} numero=$numero")
                } catch (_: Exception) {}
            }

            val products = mutableListOf<com.albacontrol.ml.OCRProduct>()
            for (l in lines) {
                val hasLetter = l.any { it.isLetter() }
                val hasDigit = l.any { it.isDigit() }
                if (hasLetter && hasDigit) {
                    products.add(com.albacontrol.ml.OCRProduct(descripcion = l, unidades = null, precio = null, importe = null, incidencia = false, bbox = null))
                }
            }

            return@withContext com.albacontrol.ml.OCRResult(proveedor, null, nif, null, numero, null, fecha, null, products, lines.map { it to Rect(0,0,bitmap.width, bitmap.height) })
            } finally {
                api.clear()
                api.end()
            }
    }

    // Focused OCR passes helper (exposed as suspend function)
    suspend fun ocrCrop(bitmap: Bitmap, fieldType: FieldType = FieldType.DEFAULT): String = withContext(Dispatchers.IO) {
        try {
            val api = TessBaseAPI()
            try {
                try { api.init("", "spa") } catch (_: Exception) {}

                when (fieldType) {
                    FieldType.NIF -> {
                        try { api.setVariable("tessedit_char_whitelist", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ") } catch (_: Exception) {}
                        api.setPageSegMode(7)
                    }
                    FieldType.NUMBER -> {
                        try { api.setVariable("tessedit_char_whitelist", "0123456789") } catch (_: Exception) {}
                        api.setPageSegMode(7)
                    }
                    FieldType.DATE -> {
                        try { api.setVariable("tessedit_char_whitelist", "0123456789/.-") } catch (_: Exception) {}
                        api.setPageSegMode(7)
                    }
                    else -> {
                        try { api.setVariable("tessedit_char_whitelist", "") } catch (_: Exception) {}
                        api.setPageSegMode(3)
                    }
                }

                api.setImage(bitmap)
                val txt = try { api.utF8Text ?: "" } catch (_: Exception) { "" }
                return@withContext txt
            } finally {
                try { api.clear() } catch (_: Exception) {}
                try { api.end() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            return@withContext ""
        }
    }

}

enum class FieldType { DEFAULT, NIF, NUMBER, DATE }
