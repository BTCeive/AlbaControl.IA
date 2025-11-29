package com.albacontrol.data

object TemplateLearningConfig {
    // Número mínimo de muestras requeridas para crear/actualizar una plantilla
    const val MIN_SAMPLES_CREATE_TEMPLATE: Int = 2

    // Umbrales de scoring para aplicar/confirmar plantillas
    const val SCORE_APPLY_THRESHOLD: Double = 0.65
    const val SCORE_CONFIRM_THRESHOLD: Double = 0.45

    // Pesos para combinar IoU y similitud de texto en el scoring
    const val IOU_WEIGHT: Double = 0.7
    const val TEXT_SIM_WEIGHT: Double = 0.3
}
