package com.albacontrol.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Procesador OCR mejorado que usa ML Kit para detectar estructura de tablas
 * y productos de forma más precisa.
 */
object EnhancedOcrProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Detecta estructura de tabla usando información de bloques de ML Kit.
     * Agrupa líneas que están alineadas verticalmente (misma columna).
     */
    fun detectTableStructure(text: Text): TableStructure {
        val blocks = mutableListOf<TextBlock>()
        val allBlocks = mutableListOf<Pair<String, Rect>>()
        
        // Recopilar todos los bloques de texto con sus bounding boxes
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                if (lineText.isNotEmpty()) {
                    val bbox = line.boundingBox ?: continue
                    blocks.add(TextBlock(lineText, bbox))
                    allBlocks.add(Pair(lineText, bbox))
                    
                    // También añadir elementos individuales
                    for (elem in line.elements) {
                        val elemText = elem.text.trim()
                        val elemBbox = elem.boundingBox
                        if (elemText.isNotEmpty() && elemBbox != null) {
                            allBlocks.add(Pair(elemText, elemBbox))
                        }
                    }
                }
            }
        }
        
        // Detectar columnas agrupando bloques por posición X
        val columns = detectColumns(blocks)
        
        // Detectar filas agrupando bloques por posición Y
        val rows = detectRows(blocks)
        
        return TableStructure(blocks, columns, rows, allBlocks)
    }

    /**
     * Detecta columnas agrupando bloques que están en posiciones X similares.
     */
    private fun detectColumns(blocks: List<TextBlock>): List<Column> {
        if (blocks.isEmpty()) return emptyList()
        
        // Ordenar por posición X
        val sorted = blocks.sortedBy { it.bbox.left }
        
        val columns = mutableListOf<Column>()
        var currentColumn = mutableListOf<TextBlock>()
        var currentX = sorted.first().bbox.left
        
        val COLUMN_THRESHOLD = 50 // píxeles de tolerancia para considerar misma columna
        
        for (block in sorted) {
            if (kotlin.math.abs(block.bbox.left - currentX) < COLUMN_THRESHOLD) {
                currentColumn.add(block)
            } else {
                if (currentColumn.isNotEmpty()) {
                    columns.add(Column(currentColumn.toList()))
                }
                currentColumn = mutableListOf(block)
                currentX = block.bbox.left
            }
        }
        
        if (currentColumn.isNotEmpty()) {
            columns.add(Column(currentColumn.toList()))
        }
        
        return columns
    }

    /**
     * Detecta filas agrupando bloques que están en posiciones Y similares.
     */
    private fun detectRows(blocks: List<TextBlock>): List<Row> {
        if (blocks.isEmpty()) return emptyList()
        
        // Ordenar por posición Y
        val sorted = blocks.sortedBy { it.bbox.top }
        
        val rows = mutableListOf<Row>()
        var currentRow = mutableListOf<TextBlock>()
        var currentY = sorted.first().bbox.top
        
        val ROW_THRESHOLD = 30 // píxeles de tolerancia para considerar misma fila
        
        for (block in sorted) {
            if (kotlin.math.abs(block.bbox.top - currentY) < ROW_THRESHOLD) {
                currentRow.add(block)
            } else {
                if (currentRow.isNotEmpty()) {
                    rows.add(Row(currentRow.toList()))
                }
                currentRow = mutableListOf(block)
                currentY = block.bbox.top
            }
        }
        
        if (currentRow.isNotEmpty()) {
            rows.add(Row(currentRow.toList()))
        }
        
        return rows
    }

    /**
     * Procesa bitmap y detecta productos usando estructura de tabla mejorada.
     */
    suspend fun processBitmapEnhanced(bitmap: Bitmap): OCRResult? = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                try {
                    val tableStructure = detectTableStructure(visionText)
                    val result = parseTextWithTableStructure(visionText, tableStructure)
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener {
                continuation.resume(null)
            }
    }

    /**
     * Parsea texto usando estructura de tabla detectada para mejor extracción de productos.
     */
    private fun parseTextWithTableStructure(text: Text, tableStructure: TableStructure): OCRResult {
        // Extraer campos principales (proveedor, NIF, número, fecha)
        var proveedor: String? = null
        var proveedorBBox: Rect? = null
        var nif: String? = null
        var nifBBox: Rect? = null
        var numero: String? = null
        var numeroBBox: Rect? = null
        var fecha: String? = null
        var fechaBBox: Rect? = null

        val dateRegex = Regex("\\b(\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4})\\b")
        val nifRegex1 = Regex("\\b\\d{8}[A-Z]\\b")
        val nifRegex2 = Regex("\\b[A-Z0-9]{1}\\d{7}[A-Z0-9]{1}\\b")
        val numberRegex = Regex("\\d+")

        // Buscar en las primeras filas (header del documento)
        val headerRows = tableStructure.rows.take(10)
        for (row in headerRows) {
            val rowText = row.blocks.joinToString(" ") { it.text }
            
            if (proveedor == null && !dateRegex.containsMatchIn(rowText) && !numberRegex.matches(rowText.replace(" ", ""))) {
                val low = rowText.lowercase()
                if (!low.contains("total") && !low.contains("factura") && !low.contains("iva") && 
                    !low.contains("albar") && rowText.length > 3) {
                    proveedor = rowText
                    proveedorBBox = row.blocks.firstOrNull()?.bbox
                }
            }

            if (nif == null && (nifRegex1.containsMatchIn(rowText) || nifRegex2.containsMatchIn(rowText))) {
                val m = nifRegex1.find(rowText) ?: nifRegex2.find(rowText)
                nif = m?.value
                nifBBox = row.blocks.firstOrNull()?.bbox
            }

            if (fecha == null && dateRegex.containsMatchIn(rowText)) {
                fecha = dateRegex.find(rowText)?.value
                fechaBBox = row.blocks.firstOrNull()?.bbox
            }

            if (numero == null && rowText.lowercase().contains("albar")) {
                val m = numberRegex.find(rowText)
                if (m != null) {
                    numero = m.value
                    numeroBBox = row.blocks.firstOrNull()?.bbox
                }
            }
        }

        // Extraer productos de filas que parecen ser de tabla (múltiples columnas)
        val products = mutableListOf<OCRProduct>()
        
        // Palabras clave que indican que NO es un producto (headers, direcciones, etc.)
        val nonProductKeywords = setOf(
            "codigo", "ean", "total", "totales", "cliente", "proveedor", "nif", "cif",
            "documento", "ticket", "albaran", "factura", "fecha", "entregado", "ruta",
            "cial", "punto", "venta", "mercantil", "tomo", "folio", "hoja", "barcelona",
            "madrid", "españa", "direccion", "dirección", "calle", "avenida", "plaza"
        )
        
        val productRows = tableStructure.rows.filter { row ->
            val rowText = row.blocks.joinToString(" ").lowercase()
            
            // Excluir filas que contienen palabras clave de headers
            if (nonProductKeywords.any { rowText.contains(it) }) {
                return@filter false
            }
            
            // Filas de productos típicamente tienen múltiples bloques (columnas)
            val hasMultipleBlocks = row.blocks.size >= 2
            val hasText = row.blocks.any { it.text.any { char -> char.isLetter() } }
            val hasNumbers = row.blocks.any { it.text.any { char -> char.isDigit() } }
            
            // La descripción debe tener al menos 3 caracteres de texto
            val firstBlock = row.blocks.firstOrNull()
            val descLength = firstBlock?.text?.filter { it.isLetter() }?.length ?: 0
            
            // Filas muy cortas probablemente no son productos
            val isTooShort = rowText.length < 5
            
            hasMultipleBlocks && hasText && hasNumbers && descLength >= 3 && !isTooShort
        }

        for (row in productRows) {
            // Ordenar bloques por posición X (de izquierda a derecha)
            val sortedBlocks = row.blocks.sortedBy { it.bbox.left }
            
            // Primera columna: descripción
            val descBlock = sortedBlocks.firstOrNull { 
                it.text.any { char -> char.isLetter() } && it.text.length > 3 
            }
            val descripcion = descBlock?.text?.trim() ?: ""
            
            if (descripcion.isEmpty()) continue
            
            // Resto de columnas: unidades, precio, importe
            val numericBlocks = sortedBlocks.filter { 
                it.text.any { char -> char.isDigit() } && 
                it.text.replace(Regex("[^0-9.,]"), "").matches(Regex("^[0-9]+([.,][0-9]{1,2})?$"))
            }
            
            val unidades = numericBlocks.getOrNull(0)?.text?.replace(Regex("[^0-9.,]"), "")?.takeIf { it.isNotEmpty() }
            val precio = numericBlocks.getOrNull(1)?.text?.replace(Regex("[^0-9.,]"), "")?.takeIf { it.isNotEmpty() }
            val importe = numericBlocks.getOrNull(2)?.text?.replace(Regex("[^0-9.,]"), "")?.takeIf { it.isNotEmpty() }
            
            // Bounding box de la fila completa
            val rowBbox = if (sortedBlocks.isNotEmpty()) {
                val left = sortedBlocks.minOfOrNull { it.bbox.left } ?: 0
                val top = sortedBlocks.minOfOrNull { it.bbox.top } ?: 0
                val right = sortedBlocks.maxOfOrNull { it.bbox.right } ?: 0
                val bottom = sortedBlocks.maxOfOrNull { it.bbox.bottom } ?: 0
                Rect(left, top, right, bottom)
            } else null
            
            products.add(
                OCRProduct(
                    descripcion = descripcion,
                    unidades = unidades,
                    precio = precio,
                    importe = importe,
                    bbox = rowBbox,
                    numericElementBBoxes = numericBlocks.mapNotNull { it.bbox }
                )
            )
        }

        // Fallback: si no se encontró proveedor, usar primera línea
        if (proveedor == null && tableStructure.rows.isNotEmpty()) {
            val firstRow = tableStructure.rows.first()
            proveedor = firstRow.blocks.joinToString(" ") { it.text }
            proveedorBBox = firstRow.blocks.firstOrNull()?.bbox
        }

        return OCRResult(
            proveedor, proveedorBBox, nif, nifBBox, numero, numeroBBox, 
            fecha, fechaBBox, products, tableStructure.allBlocks
        )
    }

    data class TextBlock(val text: String, val bbox: Rect)
    data class Column(val blocks: List<TextBlock>)
    data class Row(val blocks: List<TextBlock>)
    data class TableStructure(
        val blocks: List<TextBlock>,
        val columns: List<Column>,
        val rows: List<Row>,
        val allBlocks: List<Pair<String, Rect>>
    )
}
