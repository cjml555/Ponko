package com.yumenode.ai.draw

/**
 * Presets para modelos destilados (Turbo / Lightning / Hyper / LCM / SDXS).
 *
 * Estos modelos se entrenaron para 1-4 pasos con CFG 1. Generar con los valores
 * por defecto (20 pasos, CFG 7) tarda ~5x mas y encima empeora el resultado,
 * porque se les aplica refinamiento para el que no fueron entrenados.
 */
object DistilledPresets {

    /**
     * Pasos recomendados segun el nombre del modelo, del set o de la LoRA.
     * Devuelve null si no es de la familia destilada (modelo normal, 20+ pasos).
     */
    fun stepsFor(vararg names: String?): Int? {
        val n = names.filterNotNull().joinToString(" ").lowercase()
        if (n.isBlank()) return null
        return when {
            n.contains("1step") || n.contains("1-step") || n.contains("hyper") -> 1
            n.contains("2step") || n.contains("2-step") -> 2
            n.contains("turbo") || n.contains("lightning") || n.contains("sdxs") ||
                n.contains("lcm") || n.contains("distill") -> 4
            else -> null
        }
    }

    /** CFG recomendada para destilados (siempre 1). */
    fun cfgFor(vararg names: String?): Float? = if (stepsFor(*names) != null) 1f else null
}
