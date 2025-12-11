package com.albacontrol

import android.graphics.Rect

// Conservador: constantes y tipos placeholder para compilar.
// Reemplazar por implementaciones reales cuando se integren.
object Placeholders {
  const val EMBEDDING_WEIGHT: Float = 1.0f
  const val AUTO_SAVE_COOLDOWN_MS: Long = 30_000L
}

// Normalized fields mapping used by templates code
typealias NormalizedFields = Map<String, String>

// Minimal holder for field confidences
data class FieldConfidence(val name: String, val confidence: Double = 0.0)

// Minimal sample metadata used in save/export flows
data class SampleMeta(
  val version: String? = null,
  val active: Boolean = false,
  val createdFromSampleIds: List<Long> = emptyList(),
  val fieldConfidence: Map<String, Double> = emptyMap()
)
