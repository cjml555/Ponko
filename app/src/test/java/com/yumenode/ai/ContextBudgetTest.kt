package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del presupuesto de contexto.
 *
 * Fijan el comportamiento actual de `fitTokens` (que estaba duplicado byte a
 * byte en `ChatManager` y `AttachmentHandler`) y del calculo del presupuesto
 * de la conversacion.
 */
class ContextBudgetTest {

    // ---------- fitTokens ----------

    @Test
    fun textThatFitsIsReturnedUnchanged() {
        val text = "hola"
        assertEquals(text, ContextBudget.fitTokens(text, 1000))
    }

    @Test
    fun textIsCutWhenItExceedsTheBudget() {
        val text = "a".repeat(1000)
        val cut = ContextBudget.fitTokens(text, 10)

        assertTrue("El recorte debe reducir el texto", cut.length < text.length)
        assertTrue("El recorte no debe pasar del presupuesto", cut.length <= 50)
    }

    @Test
    fun theCutLandsOnALineBreakWhenItIsInTheSecondHalf() {
        // 40 lineas cortas: el corte cae dentro de la segunda mitad, asi que
        // el recorte se queda con el texto hasta el ultimo salto anterior.
        val text = (1..40).joinToString("\n") { "linea$it" }
        val cut = ContextBudget.fitTokens(text, 5)

        // El texto conservado no incluye el caracter que disparo el corte, y
        // al cortar en el salto no queda ninguna linea a medias.
        assertTrue("El recorte debe ser un prefijo del original", cut == text.substring(0, cut.length))
        assertTrue("El recorte no debe include a half line", cut.lastOrNull()?.isLetterOrDigit() == true)
        assertTrue("El recorte debe ser mas corto que el original", cut.length < text.length)
    }

    @Test
    fun theCutStaysMidLineWhenThereIsNoUsefulBreak() {
        // Una sola linea larga: no hay salto de linea donde cortar.
        val text = "x".repeat(500)
        val cut = ContextBudget.fitTokens(text, 5)

        assertTrue(cut.isNotEmpty())
        assertTrue(cut.length < 500)
    }

    @Test
    fun emptyTextIsHandled() {
        assertEquals("", ContextBudget.fitTokens("", 10))
    }

    @Test
    fun cjkIsCountedAsOneTokenPerCharacter() {
        // Cada caracter CJK vale 1 token: con presupuesto 5 caben 5.
        val cut = ContextBudget.fitTokens("中文字符测试内容", 5)
        assertTrue("Con CJK caben ~5 caracteres, no ~16", cut.length <= 6)
    }

    @Test
    fun latinIsCountedAsAboutThreeCharactersPerToken() {
        // Cada caracter latino vale 0.3 tokens: con presupuesto 5 caben ~16.
        val cut = ContextBudget.fitTokens("a".repeat(100), 5)
        assertTrue("Con latino caben ~16 caracteres", cut.length in 10..20)
    }

    // ---------- estimateConversationTokens ----------

    private fun sessionWith(vararg turns: QaTurn): ChatSession {
        val s = ChatSession(1L, "titulo")
        s.turns.addAll(turns.toList())
        return s
    }

    @Test
    fun anEmptyConversationCostsOnlyTheExtra() {
        val t = ContextBudget.estimateConversationTokens(
            session = sessionWith(), extra = "hola", dropLast = false,
            canDrawFromChat = false, drawToolPrompt = "",
        )
        // "hola" = 4 * 0.3 = 1.2 tokens, mas el sobre del turno
        assertEquals(1.2f + ContextBudget.TOKENS_PER_TURN, t, 0.001f)
    }

    @Test
    fun eachTurnAddsItsTextPlusTheEnvelope() {
        val s = sessionWith(
            QaTurn(user = "abc", answer = "def"),
        )
        val t = ContextBudget.estimateConversationTokens(
            session = s, extra = "", dropLast = false,
            canDrawFromChat = false, drawToolPrompt = "",
        )
        // El turno aporta "abc" + "def" = 6 * 0.3 = 1.8, mas 12 del sobre del
        // turno, mas los 12 del sobre del mensaje actual (extra vacio).
        assertEquals(1.8f + ContextBudget.TOKENS_PER_TURN * 2, t, 0.001f)
    }

    @Test
    fun dropLastExcludesTheLastTurn() {
        val s = sessionWith(QaTurn(user = "abc"), QaTurn(user = "xyz"))
        val full = ContextBudget.estimateConversationTokens(
            session = s, extra = "", dropLast = false,
            canDrawFromChat = false, drawToolPrompt = "",
        )
        val withoutLast = ContextBudget.estimateConversationTokens(
            session = s, extra = "", dropLast = true,
            canDrawFromChat = false, drawToolPrompt = "",
        )

        assertTrue("Quitar el ultimo turno debe reducir el coste", withoutLast < full)
    }

    @Test
    fun theDrawToolPromptIsCountedWhenAvailable() {
        val s = sessionWith()
        val without = ContextBudget.estimateConversationTokens(
            session = s, extra = "", dropLast = false,
            canDrawFromChat = false, drawToolPrompt = "dibuja algo",
        )
        val with_ = ContextBudget.estimateConversationTokens(
            session = s, extra = "", dropLast = false,
            canDrawFromChat = true, drawToolPrompt = "dibuja algo",
        )

        assertTrue(with_ > without)
    }

    @Test
    fun theFileTextOfATurnIsCounted() {
        val withFile = QaTurn(user = "abc").also { it.fileText = "contenido del fichero" }
        val s = sessionWith(withFile)
        val t = ContextBudget.estimateConversationTokens(
            session = s, extra = "", dropLast = false,
            canDrawFromChat = false, drawToolPrompt = "",
        )
        val plain = ContextBudget.estimateConversationTokens(
            session = sessionWith(QaTurn(user = "abc")), extra = "", dropLast = false,
            canDrawFromChat = false, drawToolPrompt = "",
        )

        assertTrue("El texto del fichero debe contar", t > plain)
    }
}
