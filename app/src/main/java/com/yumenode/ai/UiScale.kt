// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Conversion de dp a px.
 *
 * La aritmetica vive aqui, **fuera de Android**, para poder comprobarla en
 * tests JVM: `Context.dp()` depende de `resources`, que no existe en un test
 * plano.
 *
 * Antes cada clase tenia su propia copia del calculo (con truncado) y
 * `ChatImageHandler` tenia una variante que **redondeaba**. Las dos
 * convenciones se mantienen aqui, explicitas, en vez de repartidas por el
 * arbol de ficheros.
 */
object UiScale {

    /**
     * dp a px, **truncando** (la convencion mayoritaria).
     *
     * 10dp a densidad 2.5 -> 25px. Un valor fraccionario se pierde: 3dp a
     * densidad 2.5 son 7.5px y aqui quedan 7.
     */
    fun dpToPx(dp: Int, density: Float): Int = (dp * density).toInt()

    /**
     * dp a px en coma flotante, sin truncar.
     *
     * Para los valores que no son pixeles enteros (tamaño de texto, grosor de
     * linea): ahi un redondeo cambiaria el diseno.
     */
    fun dpToPxFloat(dp: Float, density: Float): Float = dp * density

    /**
     * dp a px, **redondeando** al entero mas cercano.
     *
     * 3dp a densidad 2.5 -> 8px en vez de 7. Se usa donde un padding de un
     * pixel de diferencia notaba (p.ej. las margenes de las imagenes en el
     * chat).
     */
    fun dpToPxRounded(dp: Int, density: Float): Int = (dp * density + 0.5f).toInt()

    /**
     * Redondea a un multiplo de [step].
     *
     * sd.cpp exige que el tamano de las imagenes sea multiplo de 64; el
     * truncado y el redondeo dan resultados distintos, asi que el redondeo
     * al multiplo se hace despues de la conversion.
     */
    fun dpToPxRoundedToStep(dp: Int, density: Float, step: Int): Int {
        val px = dpToPxRounded(dp, density)
        if (step <= 1) return px
        return (px / step) * step
    }
}
