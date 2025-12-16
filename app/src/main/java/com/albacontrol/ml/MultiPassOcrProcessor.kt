package com.albacontrol.ml

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Procesador OCR con múltiples pasadas para mejorar precisión.
 * Realiza 2-3 pasadas con diferentes preprocesamientos y combina resultados.
 */
object MultiPassOcrProcessor {

    /**
     * Procesa bitmap con múltiples pasadas de OCR.
     * Combina resultados de diferentes preprocesamientos para mayor precisión.
     */
    suspend fun processBitmapMultiPass(bitmap: Bitmap): OCRResult? = withContext(Dispatchers.IO) {
        val results = mutableListOf<OCRResult>()
        
        // Pasada 1: OCR estándar (sin preprocesamiento adicional)
        try {
            val result1 = suspendCancellableCoroutine<OCRResult?> { cont ->
                OcrProcessor.processBitmap(bitmap) { res, err ->
                    if (err != null) {
                        cont.resume(null)
                    } else {
                        cont.resume(res)
                    }
                }
            }
            if (result1 != null) {
                results.add(result1)
                android.util.Log.d("AlbaTpl", "MultiPass OCR: Pass 1 (standard) - ${result1.products.size} products")
            }
        } catch (e: Exception) {
            android.util.Log.e("AlbaTpl", "MultiPass OCR: Pass 1 error: ${e.message}")
        }
        
        // Pasada 2: Enhanced OCR (con detección de estructura de tabla)
        try {
            val result2 = EnhancedOcrProcessor.processBitmapEnhanced(bitmap)
            if (result2 != null) {
                // Filtrar productos válidos (no headers/direcciones)
                val validProducts = result2.products.filter { product ->
                    val desc = product.descripcion.lowercase()
                    val isHeader = desc.length < 5 || 
                                  desc.contains("codigo") || 
                                  desc.contains("ean") ||
                                  desc.contains("total") ||
                                  desc.contains("cliente") ||
                                  desc.contains("documento") ||
                                  desc.contains("ticket") ||
                                  desc.contains("albaran") ||
                                  desc.contains("barcelona") ||
                                  desc.contains("madrid") ||
                                  desc.contains("entregado") ||
                                  desc.contains("ruta") ||
                                  desc.matches(Regex("^[a-z]{1,2}\\d+"))
                    !isHeader
                }
                
                if (validProducts.size >= result2.products.size * 0.5) {
                    val filteredResult = OCRResult(
                        proveedor = result2.proveedor,
                        proveedorBBox = result2.proveedorBBox,
                        nif = result2.nif,
                        nifBBox = result2.nifBBox,
                        numeroAlbaran = result2.numeroAlbaran,
                        numeroBBox = result2.numeroBBox,
                        fechaAlbaran = result2.fechaAlbaran,
                        fechaBBox = result2.fechaBBox,
                        products = validProducts,
                        allBlocks = result2.allBlocks
                    )
                    results.add(filteredResult)
                    android.util.Log.d("AlbaTpl", "MultiPass OCR: Pass 2 (enhanced) - ${result2.products.size} products (${validProducts.size} valid)")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AlbaTpl", "MultiPass OCR: Pass 2 error: ${e.message}")
        }
        
        // Pasada 3: OCR con imagen mejorada (si hay preprocesamiento disponible)
        try {
            val enhancedBitmap = enhanceImageForOcr(bitmap)
            if (enhancedBitmap != null && enhancedBitmap != bitmap) {
                val result3 = suspendCancellableCoroutine<OCRResult?> { cont ->
                    OcrProcessor.processBitmap(enhancedBitmap) { res, err ->
                        if (err != null) {
                            cont.resume(null)
                        } else {
                            cont.resume(res)
                        }
                    }
                }
                if (result3 != null) {
                    results.add(result3)
                    android.util.Log.d("AlbaTpl", "MultiPass OCR: Pass 3 (enhanced image) - ${result3.products.size} products")
                }
                // Liberar bitmap mejorado si fue creado
                if (enhancedBitmap != bitmap) {
                    enhancedBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AlbaTpl", "MultiPass OCR: Pass 3 error: ${e.message}")
        }
        
        if (results.isEmpty()) {
            android.util.Log.w("AlbaTpl", "MultiPass OCR: No valid results from any pass")
            return@withContext null
        }
        
        // Combinar resultados: usar el mejor resultado o combinar productos únicos
        val combinedResult = combineOcrResults(results)
        android.util.Log.d("AlbaTpl", "MultiPass OCR: Combined ${results.size} passes - ${combinedResult.products.size} unique products")
        
        combinedResult
    }

    /**
     * Mejora imagen para OCR (contraste, nitidez, etc.)
     */
    private fun enhanceImageForOcr(bitmap: Bitmap): Bitmap? {
        return try {
            // Crear copia mutable
            val enhanced = bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
            
            // Aplicar mejoras básicas usando Canvas
            val canvas = android.graphics.Canvas(enhanced)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            
            // Aplicar matriz de color para mejorar contraste
            val colorMatrix = android.graphics.ColorMatrix().apply {
                setSaturation(0f) // Escala de grises
                setScale(1.2f, 1.2f, 1.2f, 1f) // Aumentar contraste
            }
            val colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            paint.colorFilter = colorFilter
            
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            enhanced
        } catch (e: Exception) {
            android.util.Log.e("AlbaTpl", "Image enhancement error: ${e.message}")
            null
        }
    }

    /**
     * Combina múltiples resultados de OCR.
     * Prioriza resultados con más productos válidos y combina productos únicos.
     */
    private fun combineOcrResults(results: List<OCRResult>): OCRResult {
        if (results.size == 1) return results.first()
        
        // Usar el resultado con más productos válidos como base
        val baseResult = results.maxByOrNull { it.products.size } ?: results.first()
        
        // Combinar productos únicos de todos los resultados
        val combinedProducts = mutableMapOf<String, OCRProduct>()
        
        for (result in results) {
            for (product in result.products) {
                val desc = product.descripcion.trim().lowercase()
                if (desc.isNotEmpty() && desc.length >= 3) {
                    // Usar descripción normalizada como clave
                    val key = desc.replace(Regex("[^a-z0-9áéíóúñü]"), "_")
                    
                    // Si no existe o este producto tiene más información, usarlo
                    if (!combinedProducts.containsKey(key) || 
                        (product.unidades?.isNotEmpty() == true && combinedProducts[key]?.unidades.isNullOrEmpty())) {
                        combinedProducts[key] = product
                    }
                }
            }
        }
        
        // Combinar allBlocks de todos los resultados (evitar duplicados)
        val combinedBlocks = mutableMapOf<String, android.graphics.Rect>()
        for (result in results) {
            for ((text, rect) in result.allBlocks) {
                val key = text.trim().lowercase()
                if (key.isNotEmpty() && !combinedBlocks.containsKey(key)) {
                    combinedBlocks[key] = rect
                }
            }
        }
        
        return OCRResult(
            proveedor = baseResult.proveedor ?: results.firstOrNull { it.proveedor != null }?.proveedor,
            proveedorBBox = baseResult.proveedorBBox ?: results.firstOrNull { it.proveedorBBox != null }?.proveedorBBox,
            nif = baseResult.nif ?: results.firstOrNull { it.nif != null }?.nif,
            nifBBox = baseResult.nifBBox ?: results.firstOrNull { it.nifBBox != null }?.nifBBox,
            numeroAlbaran = baseResult.numeroAlbaran ?: results.firstOrNull { it.numeroAlbaran != null }?.numeroAlbaran,
            numeroBBox = baseResult.numeroBBox ?: results.firstOrNull { it.numeroBBox != null }?.numeroBBox,
            fechaAlbaran = baseResult.fechaAlbaran ?: results.firstOrNull { it.fechaAlbaran != null }?.fechaAlbaran,
            fechaBBox = baseResult.fechaBBox ?: results.firstOrNull { it.fechaBBox != null }?.fechaBBox,
            products = combinedProducts.values.toList(),
            allBlocks = combinedBlocks.map { (text, rect) -> text to rect }
        )
    }
}
