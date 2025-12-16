package com.albacontrol.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

/**
 * Sistema de embeddings semánticos usando TensorFlow Lite.
 * Convierte texto a vectores de alta dimensión para matching semántico.
 * 
 * NOTA: El modelo USE Lite requiere SentencePiece para tokenización, que es complejo.
 * Por ahora, funciona en modo degradado con matching mejorado tradicional.
 * 
 * Para habilitar embeddings reales:
 * 1. Descargar modelo USE Lite de TensorFlow Hub
 * 2. Convertir a TFLite con SentencePiece
 * 3. Añadir modelo y vocabulario a assets/
 * 
 * Mientras tanto, usa matching mejorado tradicional con mejoras semánticas.
 */
object SemanticEmbeddings {
    private const val TAG = "SemanticEmbeddings"
    private const val MODEL_FILENAME = "universal_sentence_encoder_lite.tflite"
    private const val EMBEDDING_DIM = 512 // Dimensión del embedding para USE Lite
    
    private var interpreter: Interpreter? = null
    private var isInitialized = false
    
    // Cache de embeddings para mejorar rendimiento
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    private const val MAX_CACHE_SIZE = 1000 // Limitar tamaño del cache
    
    /**
     * Inicializa el modelo de embeddings.
     * Si el modelo no está disponible, funciona en modo degradado (sin embeddings).
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true
        
        // Por ahora, no inicializar modelo real (requiere SentencePiece)
        // Funciona en modo degradado con matching mejorado
        Log.d(TAG, "Semantic embeddings: using enhanced traditional matching (model not loaded)")
        isInitialized = false
        return false
        
        /* Código para cuando se añada el modelo:
        return try {
            val modelFile = File(context.filesDir, MODEL_FILENAME)
            if (!modelFile.exists()) {
                try {
                    val assetManager = context.assets
                    val inputStream = assetManager.open("models/$MODEL_FILENAME")
                    modelFile.parentFile?.mkdirs()
                    inputStream.use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Model copied from assets")
                } catch (e: Exception) {
                    Log.w(TAG, "Model not found, using fallback: ${e.message}")
                    return false
                }
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            }
            
            interpreter = Interpreter(modelFile, options)
            isInitialized = true
            Log.d(TAG, "Semantic embeddings initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            false
        }
        */
    }
    
    /**
     * Genera embedding semántico para un texto.
     * Usa cache para mejorar rendimiento.
     * Retorna null si el modelo no está disponible (modo degradado).
     */
    fun getEmbedding(text: String): FloatArray? {
        // Verificar cache primero
        val normalizedText = normalizeText(text)
        embeddingCache[normalizedText]?.let {
            return it
        }
        
        // Por ahora, retornar null (modo degradado)
        // Cuando se añada el modelo, generar embeddings reales aquí
        return null
        
        /* Código para cuando se añada el modelo:
        if (!isInitialized || interpreter == null) {
            return null
        }
        
        return try {
            // ... código de generación de embedding ...
            
            // Guardar en cache
            if (embeddingCache.size >= MAX_CACHE_SIZE) {
                // Eliminar entrada más antigua (FIFO simple)
                val firstKey = embeddingCache.keys.firstOrNull()
                if (firstKey != null) {
                    embeddingCache.remove(firstKey)
                }
            }
            embeddingCache[normalizedText] = embedding
            
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error generating embedding: ${e.message}", e)
            null
        }
        */
    }
    
    /**
     * Limpia el cache de embeddings.
     */
    fun clearCache() {
        embeddingCache.clear()
    }
    
    /**
     * Calcula similitud coseno entre dos embeddings.
     * Retorna valor entre 0.0 (sin similitud) y 1.0 (idéntico).
     */
    fun cosineSimilarity(embedding1: FloatArray, embedding2: FloatArray): Double {
        if (embedding1.size != embedding2.size) {
            return 0.0
        }
        
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }
        
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0.0) {
            dotProduct / denominator
        } else {
            0.0
        }
    }
    
    /**
     * Calcula similitud semántica entre dos textos.
     * Combina embeddings con fallback a matching tradicional si embeddings no disponibles.
     */
    fun semanticSimilarity(text1: String, text2: String): Double {
        val embedding1 = getEmbedding(text1)
        val embedding2 = getEmbedding(text2)
        
        if (embedding1 != null && embedding2 != null) {
            // Usar embeddings semánticos
            return cosineSimilarity(embedding1, embedding2)
        } else {
            // Fallback a matching tradicional (normalizado)
            return traditionalSimilarity(text1, text2)
        }
    }
    
    /**
     * Matching tradicional como fallback (cuando embeddings no disponibles).
     */
    private fun traditionalSimilarity(text1: String, text2: String): Double {
        val norm1 = normalizeText(text1).lowercase()
        val norm2 = normalizeText(text2).lowercase()
        
        if (norm1 == norm2) return 1.0
        
        // Levenshtein distance normalizada
        val distance = levenshteinDistance(norm1, norm2)
        val maxLen = maxOf(norm1.length, norm2.length)
        return if (maxLen > 0) {
            1.0 - (distance.toDouble() / maxLen)
        } else {
            0.0
        }
    }
    
    /**
     * Normaliza texto para procesamiento.
     */
    private fun normalizeText(text: String): String {
        return text.trim()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ") // Mantener letras, números y espacios
            .replace(Regex("\\s+"), " ") // Normalizar espacios
    }
    
    /**
     * Normaliza embedding (L2 normalization).
     */
    private fun normalizeEmbedding(embedding: FloatArray) {
        var norm = 0.0
        for (value in embedding) {
            norm += value * value
        }
        norm = sqrt(norm)
        if (norm > 0.0) {
            for (i in embedding.indices) {
                embedding[i] = (embedding[i] / norm).toFloat()
            }
        }
    }
    
    /**
     * Calcula distancia de Levenshtein entre dos strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[m][n]
    }
    
    /**
     * Libera recursos del modelo.
     */
    fun release() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
