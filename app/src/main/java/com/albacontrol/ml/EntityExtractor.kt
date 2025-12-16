package com.albacontrol.ml

import android.util.Log
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Extractor de entidades usando ML Kit Entity Extraction API.
 * Extrae fechas, números, direcciones, emails, etc. del texto OCR.
 */
object EntityExtractor {
    private const val TAG = "EntityExtractor"
    
    private val extractor = EntityExtraction.getClient(
        EntityExtractorOptions.Builder(EntityExtractorOptions.SPANISH)
            .build()
    )
    
    /**
     * Extrae entidades de un texto usando ML Kit Entity Extraction.
     * Retorna mapa de tipo de entidad -> lista de valores encontrados.
     */
    suspend fun extractEntities(text: String): Map<String, List<String>> = suspendCancellableCoroutine { continuation ->
        val entities = mutableMapOf<String, MutableList<String>>()
        
        // Descargar modelo si es necesario
        extractor.downloadModelIfNeeded()
            .addOnSuccessListener {
                // Modelo disponible, proceder con extracción
                val params = com.google.mlkit.nl.entityextraction.EntityExtractionParams.Builder(text).build()
                
                extractor.annotate(params)
                    .addOnSuccessListener { entityAnnotations ->
                        try {
                            for (entityAnnotation in entityAnnotations) {
                                for (entity in entityAnnotation.entities) {
                                    val entityText = entityAnnotation.annotatedText
                                    val entityType = when (entity) {
                                        is com.google.mlkit.nl.entityextraction.DateTimeEntity -> "DATE_TIME"
                                        is com.google.mlkit.nl.entityextraction.MoneyEntity -> "MONEY"
                                        is com.google.mlkit.nl.entityextraction.FlightNumberEntity -> "FLIGHT"
                                        else -> "OTHER"
                                    }
                                    
                                    if (!entities.containsKey(entityType)) {
                                        entities[entityType] = mutableListOf<String>()
                                    }
                                    entities[entityType]?.add(entityText)
                                    
                                    Log.d(TAG, "Extracted entity: type=$entityType, text='$entityText'")
                                }
                            }
                            
                            continuation.resume(entities)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing entities: ${e.message}", e)
                            continuation.resume(emptyMap())
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Entity extraction failed: ${e.message}", e)
                        continuation.resume(emptyMap())
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed: ${e.message}", e)
                continuation.resume(emptyMap())
            }
    }
    
    /**
     * Extrae fecha del texto.
     * Retorna la primera fecha encontrada o null.
     */
    suspend fun extractDate(text: String): String? {
        val entities = extractEntities(text)
        return entities["DATE_TIME"]?.firstOrNull()
    }
    
    /**
     * Extrae número de albarán del texto.
     * Busca patrones numéricos que parezcan números de documento.
     */
    suspend fun extractDocumentNumber(text: String): String? {
        val entities = extractEntities(text)
        
        // También buscar patrones como "AR123456" o "ALB-123"
        val pattern = Regex("(?:ALB|AR|ALBARAN|ALBARÁN)[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
        val match = pattern.find(text)
        if (match != null) {
            return match.groupValues[1]
        }
        
        // Si no hay match, buscar números largos (probablemente números de documento)
        val numberPattern = Regex("\\b\\d{6,}\\b")
        val numberMatch = numberPattern.find(text)
        if (numberMatch != null) {
            return numberMatch.value
        }
        
        return null
    }
    
    /**
     * Extrae todas las fechas del texto.
     */
    suspend fun extractAllDates(text: String): List<String> {
        val entities = extractEntities(text)
        return entities["DATE_TIME"] ?: emptyList()
    }
    
    /**
     * Valida y normaliza una fecha extraída.
     */
    fun normalizeDate(dateText: String): String? {
        // Intentar normalizar formatos comunes de fecha
        val patterns = listOf(
            Regex("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})"), // DD/MM/YYYY
            Regex("(\\d{1,2})-(\\d{1,2})-(\\d{2,4})"), // DD-MM-YYYY
            Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})") // DD.MM.YYYY
        )
        
        for (pattern in patterns) {
            val match = pattern.find(dateText)
            if (match != null) {
                val day = match.groupValues[1].padStart(2, '0')
                val month = match.groupValues[2].padStart(2, '0')
                val year = match.groupValues[3].let {
                    if (it.length == 2) "20$it" else it
                }
                return "$day/$month/$year"
            }
        }
        
        return dateText // Retornar original si no se puede normalizar
    }
}
