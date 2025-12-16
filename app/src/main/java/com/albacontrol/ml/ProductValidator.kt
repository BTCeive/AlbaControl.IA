package com.albacontrol.ml

import android.util.Log

/**
 * Validador mejorado de productos para filtrar falsos positivos del OCR.
 * Usa múltiples heurísticas para determinar si un texto detectado es realmente un producto.
 */
object ProductValidator {
    private const val TAG = "ProductValidator"
    
    // Palabras clave que indican que NO es un producto (headers, direcciones, etc.)
    private val nonProductKeywords = setOf(
        // Headers de tabla
        "codigo", "ean", "total", "totales", "cliente", "proveedor", "nif", "cif",
        "documento", "ticket", "albaran", "albarán", "factura", "fecha", "entregado", "ruta",
        // Direcciones y ubicaciones
        "barcelona", "madrid", "españa", "direccion", "dirección", "calle", "avenida", "plaza",
        "cl.", "c/", "carrer", "avenida", "plaza", "paseo", "via", "vía",
        // Información de entrega
        "rolls", "pallets", "envios", "envíos", "bultos", "entregado", "entregado por",
        "entregado en", "p.v.", "punto de venta", "punto venta",
        // Información legal
        "mercantil", "tomo", "folio", "hoja", "registro", "reg.", "reg mercantil",
        // Servicios y contacto
        "customer service", "servicio cliente", "ext.", "extension", "teléfono", "telefono",
        "email", "correo", "contacto",
        // Otros
        "version", "versión", "datos", "suministro", "datos suministro", "kgrs", "kg", "kilos"
    )
    
    // Patrones que NO son productos
    private val nonProductPatterns = listOf(
        Regex("^[a-z]{1,3}\\d+([a-z]{1,3})?$"), // Códigos cortos como "AR644458"
        Regex("^\\d{8}[A-Z]$"), // NIFs
        Regex("^[A-Z]\\d{7}[A-Z]$"), // CIFs
        Regex("^\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4}$"), // Fechas
        Regex("^CL\\s+[A-Z]"), // Direcciones que empiezan con "CL"
        Regex("^C/"), // Direcciones que empiezan con "C/"
        Regex("^DNI\\s*-\\s*NIF"), // "DNI - NIF"
        Regex("^ENTREGADO\\s+POR", RegexOption.IGNORE_CASE), // "ENTREGADO POR"
        Regex("^ENTREGADO\\s+EN", RegexOption.IGNORE_CASE), // "ENTREGADO EN"
        Regex("^REG\\.?\\s+MERCANTIL", RegexOption.IGNORE_CASE), // "Reg. Mercantil"
        Regex("^CUSTOMER\\s+SERVICE", RegexOption.IGNORE_CASE), // "CUSTOMER SERVICE"
    )
    
    /**
     * Valida si un texto detectado es realmente un producto.
     * Retorna true si parece ser un producto válido, false si es un falso positivo.
     */
    fun isValidProduct(description: String, hasNumericValues: Boolean = false): Boolean {
        val desc = description.trim()
        
        // Validaciones básicas
        if (desc.isEmpty()) return false
        if (desc.length < 3) return false // Muy corto para ser producto
        
        val descLower = desc.lowercase()
        
        // 1. Verificar palabras clave no-producto
        for (keyword in nonProductKeywords) {
            if (descLower.contains(keyword)) {
                Log.d(TAG, "Product rejected (keyword '$keyword'): '$desc'")
                return false
            }
        }
        
        // 2. Verificar patrones no-producto
        for (pattern in nonProductPatterns) {
            if (pattern.matches(desc)) {
                Log.d(TAG, "Product rejected (pattern): '$desc'")
                return false
            }
        }
        
        // 3. Validar que tiene suficiente contenido de texto (no solo números/símbolos)
        val letterCount = desc.count { it.isLetter() }
        val totalLength = desc.length
        val letterRatio = if (totalLength > 0) letterCount.toDouble() / totalLength else 0.0
        
        // Debe tener al menos 30% de letras para ser un producto
        if (letterRatio < 0.3) {
            Log.d(TAG, "Product rejected (low letter ratio ${"%.2f".format(letterRatio)}): '$desc'")
            return false
        }
        
        // 4. Debe tener al menos 3 letras
        if (letterCount < 3) {
            Log.d(TAG, "Product rejected (too few letters: $letterCount): '$desc'")
            return false
        }
        
        // 5. Si tiene valores numéricos, es más probable que sea un producto
        // (pero no es obligatorio, algunos productos pueden no tener cantidades)
        
        // 6. Validar que no es solo un número o código
        if (desc.matches(Regex("^[A-Z0-9\\s-]+$")) && desc.length < 10) {
            // Solo letras mayúsculas, números, espacios y guiones, y es corto
            // Probablemente es un código, no un producto
            if (!desc.contains(" ")) { // Si no tiene espacios, definitivamente es un código
                Log.d(TAG, "Product rejected (looks like code): '$desc'")
                return false
            }
        }
        
        // 7. Validar que no es una dirección completa (muchas palabras con números)
        val words = desc.split(Regex("\\s+"))
        val hasAddressPattern = words.size >= 4 && desc.any { it.isDigit() } && 
                               (descLower.contains("calle") || descLower.contains("avenida") || 
                                descLower.contains("plaza") || descLower.contains("cl.") ||
                                descLower.contains("c/"))
        if (hasAddressPattern) {
            Log.d(TAG, "Product rejected (looks like address): '$desc'")
            return false
        }
        
        // Si pasa todas las validaciones, es probablemente un producto válido
        Log.d(TAG, "Product validated: '$desc' (letters=$letterCount, ratio=${"%.2f".format(letterRatio)})")
        return true
    }
    
    /**
     * Filtra una lista de productos, eliminando falsos positivos.
     */
    fun filterValidProducts(products: List<OCRProduct>): List<OCRProduct> {
        return products.filter { product ->
            val hasNumeric = product.unidades != null || product.precio != null || product.importe != null
            isValidProduct(product.descripcion, hasNumeric)
        }
    }
    
    /**
     * Calcula el porcentaje de productos válidos en una lista.
     * Útil para decidir si usar Enhanced OCR o fallback a estándar.
     */
    fun calculateValidProductRatio(products: List<OCRProduct>): Double {
        if (products.isEmpty()) return 0.0
        val validCount = filterValidProducts(products).size
        return validCount.toDouble() / products.size
    }
}
