package com.albacontrol.ml

import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sistema de clustering para agrupar productos similares.
 * Usa K-means simplificado con embeddings semánticos.
 */
object ProductClustering {
    private const val TAG = "ProductClustering"
    
    /**
     * Agrupa productos por similitud semántica.
     * Retorna mapa de cluster ID -> lista de productos.
     */
    fun clusterProducts(
        products: List<Pair<String, FloatArray?>>, // (nombre, embedding)
        threshold: Double = 0.75 // Umbral de similitud para agrupar
    ): Map<Int, List<String>> {
        if (products.isEmpty()) return emptyMap()
        
        val clusters = mutableMapOf<Int, MutableList<String>>()
        val assigned = BooleanArray(products.size) { false }
        var clusterId = 0
        
        for (i in products.indices) {
            if (assigned[i]) continue
            
            val (productName, embedding1) = products[i]
            val cluster = mutableListOf(productName)
            assigned[i] = true
            
            // Buscar productos similares
            for (j in (i + 1) until products.size) {
                if (assigned[j]) continue
                
                val (otherName, embedding2) = products[j]
                
                val similarity = if (embedding1 != null && embedding2 != null) {
                    SemanticEmbeddings.cosineSimilarity(embedding1, embedding2)
                } else {
                    // Fallback a similitud tradicional
                    SemanticEmbeddings.semanticSimilarity(productName, otherName)
                }
                
                if (similarity >= threshold) {
                    cluster.add(otherName)
                    assigned[j] = true
                }
            }
            
            if (cluster.isNotEmpty()) {
                clusters[clusterId++] = cluster
            }
        }
        
        Log.d(TAG, "Clustered ${products.size} products into ${clusters.size} clusters")
        return clusters
    }
    
    /**
     * Encuentra el cluster más similar para un producto nuevo.
     * Retorna el nombre del producto representativo del cluster o null.
     */
    fun findBestCluster(
        productName: String,
        productEmbedding: FloatArray?,
        clusters: Map<Int, List<String>>,
        existingEmbeddings: Map<String, FloatArray?>
    ): String? {
        var bestSimilarity = 0.0
        var bestRepresentative: String? = null
        
        for ((_, clusterProducts) in clusters) {
            // Usar el primer producto del cluster como representativo
            val representative = clusterProducts.firstOrNull() ?: continue
            val representativeEmbedding = existingEmbeddings[representative]
            
            val similarity = if (productEmbedding != null && representativeEmbedding != null) {
                SemanticEmbeddings.cosineSimilarity(productEmbedding, representativeEmbedding)
            } else {
                SemanticEmbeddings.semanticSimilarity(productName, representative)
            }
            
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestRepresentative = representative
            }
        }
        
        return if (bestSimilarity >= 0.7) bestRepresentative else null
    }
    
    /**
     * Aprende variaciones comunes de productos automáticamente.
     * Agrupa productos similares y genera un mapeo de variaciones.
     */
    fun learnVariations(
        products: List<String>,
        embeddings: Map<String, FloatArray?>
    ): Map<String, List<String>> {
        val productPairs = products.map { name ->
            name to embeddings[name]
        }
        
        val clusters = clusterProducts(productPairs, threshold = 0.75)
        
        // Convertir clusters a mapa de variaciones
        val variations = mutableMapOf<String, MutableList<String>>()
        
        for ((_, clusterProducts) in clusters) {
            if (clusterProducts.size > 1) {
                // El primer producto es el "canonical", los demás son variaciones
                val canonical = clusterProducts[0]
                variations[canonical] = clusterProducts.drop(1).toMutableList()
            }
        }
        
        Log.d(TAG, "Learned ${variations.size} product variations from ${products.size} products")
        return variations
    }
}
