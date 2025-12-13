package com.albacontrol.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Cliente para enviar correcciones al backend API de aprendizaje.
 * 
 * El backend espera correcciones en formato JSON con los siguientes campos:
 * - debug_id (obligatorio)
 * - template_id o nif (obligatorio)
 * - proveedor, nif, numero_albaran, fecha_albaran
 * - productos (lista)
 * - field_id, text_corrected, confidence_before, bbox, crop_path, notes (opcionales)
 */
object CorrectionsApiClient {
    private const val TAG = "CorrectionsAPI"
    
    // URL del backend (por defecto localhost, configurable)
    private const val DEFAULT_API_URL = "http://127.0.0.1:5001/api/v1/corrections"
    private const val API_KEY_HEADER = "X-API-Key"
    private const val DEFAULT_API_KEY = "dev-key"
    
    // Timeout en milisegundos
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 10000
    
    /**
     * Envía una corrección al backend API de forma asíncrona.
     * 
     * @param correctionPayload JSONObject con los datos de la corrección
     * @param apiUrl URL del backend (opcional, usa DEFAULT_API_URL si es null)
     * @param apiKey Clave API (opcional, usa DEFAULT_API_KEY si es null)
     * @return true si se envió correctamente, false en caso contrario
     */
    suspend fun sendCorrection(
        correctionPayload: JSONObject,
        apiUrl: String? = null,
        apiKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(apiUrl ?: DEFAULT_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty(API_KEY_HEADER, apiKey ?: DEFAULT_API_KEY)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            
            // Escribir el payload JSON
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(correctionPayload.toString())
                writer.flush()
            }
            
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            
            if (responseCode in 200..299) {
                Log.d(TAG, "Corrección enviada exitosamente: $responseCode")
                true
            } else {
                // Leer mensaje de error si está disponible
                val errorStream = connection.errorStream
                val errorBody = errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Error al enviar corrección: $responseCode $responseMessage - $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al enviar corrección al backend: ${e.message}", e)
            false
        }
    }
    
    /**
     * Construye un payload de corrección estándar a partir de los datos del formulario.
     * 
     * @param debugId ID único para esta corrección (normalmente timestamp)
     * @param nif NIF del proveedor
     * @param proveedor Nombre del proveedor
     * @param numeroAlbaran Número de albarán
     * @param fechaAlbaran Fecha del albarán (formato ISO YYYY-MM-DD)
     * @param productos Lista de productos (JSONArray)
     * @param templateId ID de plantilla si existe (opcional)
     * @param fieldCorrections Mapa de correcciones por campo (opcional)
     * @return JSONObject listo para enviar
     */
    fun buildCorrectionPayload(
        debugId: String,
        nif: String?,
        proveedor: String?,
        numeroAlbaran: String? = null,
        fechaAlbaran: String? = null,
        productos: org.json.JSONArray? = null,
        templateId: String? = null,
        fieldCorrections: Map<String, CorrectionFieldData>? = null
    ): JSONObject {
        val payload = JSONObject()
        
        // Campos obligatorios
        payload.put("debug_id", debugId)
        if (templateId != null) {
            payload.put("template_id", templateId)
        } else if (!nif.isNullOrBlank()) {
            payload.put("nif", normalizeNif(nif))
        }
        
        // Campos opcionales
        if (!proveedor.isNullOrBlank()) {
            payload.put("proveedor", proveedor.trim())
        }
        if (!nif.isNullOrBlank()) {
            payload.put("nif", normalizeNif(nif))
        }
        if (!numeroAlbaran.isNullOrBlank()) {
            payload.put("numero_albaran", numeroAlbaran.trim())
        }
        if (!fechaAlbaran.isNullOrBlank()) {
            payload.put("fecha_albaran", normalizeDate(fechaAlbaran))
        }
        if (productos != null) {
            payload.put("productos", productos)
        }
        
        // Metadatos adicionales
        payload.put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date()))
        payload.put("user_id", "android_user") // TODO: obtener de preferencias si está disponible
        
        // Correcciones de campos individuales (si se proporcionan)
        if (fieldCorrections != null && fieldCorrections.isNotEmpty()) {
            val correctionsArray = org.json.JSONArray()
            for ((fieldId, data) in fieldCorrections) {
                val correction = JSONObject()
                correction.put("field_id", fieldId)
                correction.put("text_corrected", data.correctedText)
                if (data.confidenceBefore != null) {
                    correction.put("confidence_before", data.confidenceBefore)
                }
                if (data.bbox != null) {
                    correction.put("bbox", org.json.JSONArray(data.bbox))
                }
                if (data.cropPath != null) {
                    correction.put("crop_path", data.cropPath)
                }
                if (data.notes != null) {
                    correction.put("notes", data.notes)
                }
                correctionsArray.put(correction)
            }
            payload.put("field_corrections", correctionsArray)
        }
        
        return payload
    }
    
    /**
     * Normaliza un NIF eliminando espacios y símbolos, convirtiendo a mayúsculas.
     */
    private fun normalizeNif(nif: String): String {
        return nif.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
    }
    
    /**
     * Normaliza una fecha a formato ISO YYYY-MM-DD.
     * Intenta parsear varios formatos comunes.
     */
    private fun normalizeDate(dateStr: String): String {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) return trimmed
        
        // Intentar formato ISO primero
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            isoFormat.parse(trimmed)
            return trimmed
        } catch (_: Exception) {}
        
        // Intentar formato dd/MM/yyyy
        try {
            val format = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            val date = format.parse(trimmed)
            val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return isoFormat.format(date)
        } catch (_: Exception) {}
        
        // Intentar formato dd-MM-yyyy
        try {
            val format = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val date = format.parse(trimmed)
            val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return isoFormat.format(date)
        } catch (_: Exception) {}
        
        // Si no se puede parsear, devolver tal cual
        return trimmed
    }
    
    /**
     * Datos de corrección de un campo individual.
     */
    data class CorrectionFieldData(
        val correctedText: String,
        val confidenceBefore: Double? = null,
        val bbox: List<Double>? = null, // [x_min, y_min, x_max, y_max] normalizado 0..1
        val cropPath: String? = null,
        val notes: String? = null
    )
}
