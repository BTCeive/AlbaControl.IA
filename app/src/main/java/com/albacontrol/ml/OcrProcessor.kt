package com.albacontrol.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OCRProduct(
    val descripcion: String,
    val unidades: String?,
    val precio: String?,
    val importe: String?,
    val incidencia: Boolean = false,
    val bbox: Rect?,
    val numericElementBBoxes: List<Rect> = emptyList()
)

data class OCRResult(
    val proveedor: String?,
    val proveedorBBox: Rect?,
    val nif: String?,
    val nifBBox: Rect?,
    val numeroAlbaran: String?,
    val numeroBBox: Rect?,
    val fechaAlbaran: String?,
    val fechaBBox: Rect?,
    val products: List<OCRProduct>
    ,
    val allBlocks: List<Pair<String, Rect>> = emptyList()
)

object OcrProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun processBitmap(bitmap: Bitmap, callback: (OCRResult?, Exception?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                try {
                    val result = parseText(visionText)
                    callback(result, null)
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
            .addOnFailureListener { e ->
                callback(null, e)
            }
    }

    private fun parseText(text: Text): OCRResult {
        val lines = mutableListOf<String>()
        data class LineInfo(val text: String, val bbox: Rect?)
        val lineInfos = mutableListOf<LineInfo>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val s = line.text.trim()
                if (s.isNotEmpty()) lines.add(s)
                // collect element boxes for tokens inside the line
                val elemBoxes = mutableListOf<Pair<String, Rect?>>()
                for (elem in line.elements) {
                    elemBoxes.add(Pair(elem.text, elem.boundingBox))
                }
                lineInfos.add(LineInfo(s, line.boundingBox))
                // store element boxes map in an auxiliary list by reusing a hidden structure (we'll use a map later)
                // For now, we will temporarily attach numeric element boxes when detecting products below
            }
        }

        var proveedor: String? = null
        var proveedorBBox: Rect? = null
        var nif: String? = null
        var nifBBox: Rect? = null
        var numero: String? = null
        var numeroBBox: Rect? = null
        var fecha: String? = null
        var fechaBBox: Rect? = null

        // Heurísticas simples
        val dateRegex = Regex("\\b(\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4})\\b")
        val nifRegex1 = Regex("\\b\\d{8}[A-Z]\\b")
        val nifRegex2 = Regex("\\b[A-Z0-9]{1}\\d{7}[A-Z0-9]{1}\\b")
        val numberRegex = Regex("\\d+")

        // Find provider as first line that doesn't look like date or number-only
        for (i in lines.indices) {
            val l = lines[i]
            val bbox = lineInfos.getOrNull(i)?.bbox
            if (proveedor == null) {
                if (!dateRegex.containsMatchIn(l) && !numberRegex.matches(l.replace(" ", ""))) {
                    // avoid lines that include words like "Factura" or "Total"
                    val low = l.lowercase()
                    if (!low.contains("total") && !low.contains("factura") && !low.contains("iva")) {
                        proveedor = l
                        proveedorBBox = bbox
                    }
                }
            }

            if (nif == null && (nifRegex1.containsMatchIn(l) || nifRegex2.containsMatchIn(l))) {
                val m = nifRegex1.find(l) ?: nifRegex2.find(l)
                nif = m?.value
                nifBBox = bbox
            }

            if (fecha == null && dateRegex.containsMatchIn(l)) {
                fecha = dateRegex.find(l)?.value
                fechaBBox = bbox
            }

            if (numero == null && l.lowercase().contains("albar")) {
                // extracción simple de números en la línea
                val m = numberRegex.find(l)
                if (m != null) numero = m.value
                numeroBBox = bbox
            }
        }

        // Products: naive grouping — lines that contain numbers and text
        val products = mutableListOf<OCRProduct>()
        for ((index, l) in lines.withIndex()) {
            // if line contains both letters and numbers, consider product candidate
            val hasLetter = l.any { it.isLetter() }
            val hasDigit = l.any { it.isDigit() }
            if (hasLetter && hasDigit) {
                // try to extract price-like tokens
                // Use ML Kit elements to detect numeric element bounding boxes when available
                val lineElements = mutableListOf<Pair<String, Rect?>>()
                // find corresponding block/line to extract elements again
                // (iterate blocks/lines to match index)
                var elemBoxes: List<Pair<String, Rect?>> = emptyList()
                var cur = 0
                outer@ for (block in text.textBlocks) {
                    for (line in block.lines) {
                        if (cur == index) {
                            val tmp = mutableListOf<Pair<String, Rect?>>()
                            for (elem in line.elements) tmp.add(Pair(elem.text, elem.boundingBox))
                            elemBoxes = tmp
                            break@outer
                        }
                        cur++
                    }
                }

                val tokens = l.split(Regex("\\s+"))
                var unidades: String? = null
                var precio: String? = null
                var importe: String? = null
                val descParts = mutableListOf<String>()
                val numericBBoxes = mutableListOf<Rect>()
                var elemPos = 0
                for (t in tokens) {
                    val clean = t.replace(Regex("[^0-9.,]"), "")
                    if (clean.matches(Regex("^[0-9]+([.,][0-9]{1,2})?$"))) {
                        // associate with next available element bbox if exists
                        val bbox = elemBoxes.getOrNull(elemPos)?.second
                        if (bbox != null) numericBBoxes.add(bbox)
                        if (unidades == null) unidades = clean
                        else if (precio == null) precio = clean
                        else if (importe == null) importe = clean
                    } else {
                        descParts.add(t)
                    }
                    elemPos++
                }
                val descripcion = descParts.joinToString(" ")
                // capture line bbox
                val lineBbox = lineInfos.getOrNull(index)?.bbox
                products.add(
                    OCRProduct(
                        descripcion = descripcion,
                        unidades = unidades,
                        precio = precio,
                        importe = importe,
                        bbox = lineBbox,
                        numericElementBBoxes = numericBBoxes
                    )
                )
            }
        }

        // If no provider found, try first line as fallback
        if (proveedor == null && lines.isNotEmpty()) {
            proveedor = lines.first()
            proveedorBBox = lineInfos.firstOrNull()?.bbox
        }

        return OCRResult(proveedor, proveedorBBox, nif, nifBBox, numero, numeroBBox, fecha, fechaBBox, products)
    }
}
