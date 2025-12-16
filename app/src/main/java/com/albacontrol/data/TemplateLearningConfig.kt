package com.albacontrol.data

object TemplateLearningConfig {
    // Número mínimo de muestras requeridas para crear/actualizar una plantilla
    // Reducido a 1 para facilitar pruebas (en producción debería ser 2-3)
    const val MIN_SAMPLES_CREATE_TEMPLATE: Int = 1

    // Umbrales de scoring para aplicar/confirmar plantillas
    // Reducido a 0.30 para permitir matching más flexible y aprendizaje más rápido
    // Prioriza aprendizaje continuo sobre precisión estricta
    const val SCORE_APPLY_THRESHOLD: Double = 0.30
    const val SCORE_CONFIRM_THRESHOLD: Double = 0.25

    // Pesos para combinar IoU y similitud de texto en el scoring
    const val IOU_WEIGHT: Double = 0.7
    const val TEXT_SIM_WEIGHT: Double = 0.3
    // Weight for embedding similarity (additional signal when available)
    const val EMBEDDING_WEIGHT: Double = 0.0

    // Cooldown time for automatic sample saves (ms)
    const val AUTO_SAVE_COOLDOWN_MS: Long = 30000L
}
