// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context

/**
 * Modo de memoria del motor de dibujo (stable-diffusion.cpp).
 *
 * **Weight residency**: cuando está activo, los pesos de los componentes del
 * modelo (Conditioner / Diffusion / VAE) NO se quedan todos en RAM a la vez.
 * sd.cpp los marca como `ResidencyMode::Disk`: cada etapa del proceso
 * (codificar el prompt →(samplear con el UNet → decodificar con el VAE)
 * carga solo el componente que necesita y lo suelta al terminar, dejando el
 * fichero mmapado.
 *
 * El efecto: el pico de memoria pasa de "la suma de todos los componentes"
 * a "el componente más grande".
 *
 *   SD 1.5 : 2,11 GB → 1,70 GB
 *   SDXL   : 3,46 GB → 2,60 GB
 *   FLUX   : 21,4 GB → 11,2 GB   ← aquí es la diferencia entre que quepa
 *                                  en un móvil y que no.
 *
 * El coste: si el page cache del kernel no da abasto, las páginas van al
 * flash y la generación se ralentiza. Por eso es un conmutador y no el
 * comportamiento por defecto.
 */
internal object DrawMemoryMode {

    private const val PREFS = "ponko"
    private const val KEY_DISK_WEIGHTS = "drawDiskWeights"

    /**
     * ¿Los pesos se cargan por etapas desde disco?
     *
     * Por defecto **activado**: es el modo que hace que un modelo grande
     * quepa en un móvil, que es el caso de uso de la app.
     */
    fun useDiskWeights(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISK_WEIGHTS, true)

    /** Guarda la preferencia (toma efecto en la siguiente carga de modelo). */
    fun setUseDiskWeights(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISK_WEIGHTS, value).apply()
    }
}
