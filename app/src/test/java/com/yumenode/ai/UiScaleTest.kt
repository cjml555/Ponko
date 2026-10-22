package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la conversion dp -> px.
 *
 * Fijan las DOS convenciones que coexistian en el arbol: truncado (la
 * mayoria) y redondeo (ChatImageHandler). A densidad 2.5 un 3dp son 7.5px:
 * truncado da 7 y redondeo da 8, asi que la diferencia es real.
 */
class UiScaleTest {

    // densidad tipica de un telefono (400dpi -> 2.5)
    private val d = 2.5f

    // ---------- truncado ----------

    @Test
    fun exactValuesAreUnchanged() {
        assertEquals(25, UiScale.dpToPx(10, d))
        assertEquals(0, UiScale.dpToPx(0, d))
    }

    @Test
    fun fractionsAreTruncated() {
        // 3dp * 2.5 = 7.5 -> 7
        assertEquals(7, UiScale.dpToPx(3, d))
        // 1dp * 2.5 = 2.5 -> 2
        assertEquals(2, UiScale.dpToPx(1, d))
    }

    @Test
    fun densityOneIsIdentity() {
        assertEquals(12, UiScale.dpToPx(12, 1f))
    }

    @Test
    fun densityThreeScalesCorrectly() {
        assertEquals(36, UiScale.dpToPx(12, 3f))
    }

    // ---------- redondeo ----------

    @Test
    fun roundedFractionsGoUp() {
        // 3dp * 2.5 = 7.5 -> 8 (no 7)
        assertEquals(8, UiScale.dpToPxRounded(3, d))
        assertEquals(3, UiScale.dpToPxRounded(1, d))
    }

    @Test
    fun roundedAndTruncatedDifferOnlyOnFractions() {
        // El valor entero es el mismo en las dos convenciones.
        assertEquals(
            UiScale.dpToPx(10, d),
            UiScale.dpToPxRounded(10, d),
        )
    }

    @Test
    fun roundingNeverExceedsOnePixelOfTruncation() {
        for (dp in 0..50) {
            val t = UiScale.dpToPx(dp, d)
            val r = UiScale.dpToPxRounded(dp, d)
            // El redondeo nunca se aleja mas de 1px del truncado, y nunca baja.
            assertTrue("dp=$dp trunca=$t redondea=$r", r - t in 0..1)
        }
    }

    // ---------- variante float ----------

    @Test
    fun floatVariantKeepsTheFraction() {
        // 3.5dp a densidad 2.5 = 8.75px: no se trunca ni se redondea.
        assertEquals(8.75f, UiScale.dpToPxFloat(3.5f, d), 0.0001f)
    }

    @Test
    fun floatVariantOfAnIntegerMatchesTheTruncatedOne() {
        assertEquals(
            UiScale.dpToPx(10, d).toFloat(),
            UiScale.dpToPxFloat(10f, d),
            0.0001f,
        )
    }

    // ---------- multiplo de paso ----------

    @Test
    fun roundedToStepAlignsDownwards() {
        // 100px redondeados a multiplo de 64 -> 64
        assertEquals(64, UiScale.dpToPxRoundedToStep(100, 1f, 64))
    }

    @Test
    fun roundedToStepWithStepOneIsIdentity() {
        assertEquals(25, UiScale.dpToPxRoundedToStep(10, d, 1))
        assertEquals(25, UiScale.dpToPxRoundedToStep(10, d, 0))
    }

    @Test
    fun roundedToStepKeepsExactMultiples() {
        assertEquals(64, UiScale.dpToPxRoundedToStep(64, 1f, 64))
        assertEquals(128, UiScale.dpToPxRoundedToStep(128, 1f, 64))
    }
}
