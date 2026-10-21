package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de caracterizacion de [ReasoningSplitter].
 *
 * Es la pieza mas delicada del chat: separa el razonamiento del texto
 * cuando el modelo mezcla ambos en el mismo flujo, y los marcadores pueden
 * venir partidos entre chunks ("<chan" + "nel|>").
 *
 * Estos tests fijan el comportamiento actual **antes** de tocar nada.
 */
class ReasoningSplitterTest {

    /** Recoge las dos salidas del splitter. */
    private class Sink {
        val thought = StringBuilder()
        val answer = StringBuilder()
    }

    private fun split(chunks: List<String>, assumeThinking: Boolean = false): Pair<String, String> {
        val sink = Sink()
        val s = ReasoningSplitter(
            onThought = { sink.thought.append(it) },
            onAnswer = { sink.answer.append(it) },
            assumeThinking = assumeThinking,
        )
        chunks.forEach { s.feed(it) }
        s.finish()
        return sink.thought.toString() to sink.answer.toString()
    }

    // ---------- sin marcadores ----------

    @Test
    fun plainTextIsAllAnswer() {
        val (thought, answer) = split(listOf("Hola, que tal?"))
        assertEquals("", thought)
        assertEquals("Hola, que tal?", answer)
    }

    @Test
    fun plainTextSplitInChunksIsAllAnswer() {
        val (thought, answer) = split(listOf("Hola", ", que", " tal?"))
        assertEquals("", thought)
        assertEquals("Hola, que tal?", answer)
    }

    @Test
    fun emptyChunksAreIgnored() {
        val (thought, answer) = split(listOf("", "Hola", "", " mundo"))
        assertEquals("", thought)
        assertEquals("Hola mundo", answer)
    }

    // ---------- marcador de Gemma 3n / 4 ----------

    @Test
    fun gemmaChannelMarkerSplitsThoughtFromAnswer() {
        val (thought, answer) = split(
            listOf("<|channel>thoughtPienso esto<channel|>Esta es la respuesta")
        )
        assertEquals("Pienso esto", thought)
        assertEquals("Esta es la respuesta", answer)
    }

    @Test
    fun theGemmaMarkerCanArriveSplitInChunks() {
        // El buffer debe retener "<chan" hasta que llegue "nel|>".
        val (thought, answer) = split(
            listOf("<|chan", "nel>thought", "razonando", "<chan", "nel|>respuesta")
        )
        assertEquals("razonando", thought)
        assertEquals("respuesta", answer)
    }

    // BUG 2 (documentado, pendiente de arreglar): el texto que precede al
    // marcador se PIERDE. El codigo emite solo los blancos iniciales
    // (`lead`) y luego borra el buffer hasta el final del marcador, asi que
    // el prefijo se descarta sin emitirse nunca.
    @Test
    fun contentBeforeTheMarkerIsCurrentlyLost() {
        val (thought, answer) = split(listOf("prefijo<|channel>thoughtidea<channel|>final"))
        assertEquals("idea", thought)
        assertEquals("final", answer)
    }

    // ---------- marcador de DeepSeek / Qwen / GLM ----------

    // BUG 1 (documentado, pendiente de arreglar): el marcador " thinking"
    // (DeepSeek / Qwen / GLM) NO llega a detectarse nunca. En el estado 0 se
    // busca en `core`, que ya no tiene los blancos iniciales, y el marcador
    // empieza por un espacio -> findFirst nunca lo encuentra. Solo funciona
    // el de Gemma ("<|channel>thought").
    @Test
    fun theThinkingMarkerIsCurrentlyNotDetected() {
        val (thought, answer) = split(listOf(" thinkingalgo que pienso<｜end▁of▁thinking｜>la respuesta"))
        assertEquals("", thought)
        assertEquals(" thinkingalgo que pienso<｜end▁of▁thinking｜>la respuesta", answer)
    }

    @Test
    fun theThinkingMarkerSplitInChunksIsAlsoNotDetected() {
        val (thought, answer) = split(
            listOf(" think", "ingpienso", "<｜end▁of", "▁thinking｜>respuesta")
        )
        assertEquals("", thought)
        assertEquals(" thinkingpienso<｜end▁of▁thinking｜>respuesta", answer)
    }

    // ---------- modo assumeThinking ----------

    @Test
    fun withAssumeThinkingEverythingBeforeTheEndIsThought() {
        // Gemma escribe el prefijo en el prompt, no en la salida: sin marcador
        // de inicio hay que asumir que es razonamiento.
        val (thought, answer) = split(listOf("pienso en voz alta"), assumeThinking = true)
        assertEquals("pienso en voz alta", thought)
        assertEquals("", answer)
    }

    @Test
    fun withAssumeThinkingTheEndMarkerSwitchesToAnswer() {
        val (thought, answer) = split(
            listOf("razonamiento<channel|>la respuesta"),
            assumeThinking = true,
        )
        assertEquals("razonamiento", thought)
        assertEquals("la respuesta", answer)
    }

    @Test
    fun withAssumeThinkingAnUnclosedThoughtIsFlushedAsThought() {
        val (thought, answer) = split(listOf("razonamiento sin cerrar"), assumeThinking = true)
        assertEquals("razonamiento sin cerrar", thought)
        assertEquals("", answer)
    }

    @Test
    fun withoutAssumeThinkingTheSameTextIsAnswer() {
        val (thought, answer) = split(listOf("razonamiento sin cerrar"), assumeThinking = false)
        assertEquals("", thought)
        assertEquals("razonamiento sin cerrar", answer)
    }

    // ---------- casos limite ----------

    // BUG 3 (documentado, pendiente de arreglar): un marcador partido al
    // final NO se retiene. La condicion para esperar mas datos exige que
    // TODO el core sea un prefijo posible del marcador, asi que con
    // "Hola<|chan" el texto entero se emite como respuesta y el "<|chan"
    // ya no se puede reconocer.
    @Test
    fun aMarkerSplitAtTheVeryEndIsCurrentlyNotHeld() {
        val sink = Sink()
        val s = ReasoningSplitter({ sink.thought.append(it) }, { sink.answer.append(it) })
        s.feed("Hola<|chan")
        assertEquals("Hola<|chan", sink.answer.toString())

        s.feed("nel>thoughtx<channel|>fin")
        s.finish()
        assertEquals("", sink.thought.toString())
        // El "<|chan" ya se habia emitido como texto, asi que el "<|channel>thought"
        // reconstruido ya no se reconoce como marcador: todo va a respuesta.
        assertEquals("Hola<|channel>thoughtx<channel|>fin", sink.answer.toString())
    }

    @Test
    fun leadingWhitespaceBeforeTheMarkerIsAnswer() {
        val (thought, answer) = split(listOf("  <|channel>thoughtidea<channel|>final"))
        assertEquals("idea", thought)
        assertEquals("  final", answer)
    }

    @Test
    fun theSecondChannelEndVariantAlsoCloses() {
        val (thought, answer) = split(listOf("<|channel>thoughtidea<|channel|>final"))
        assertEquals("idea", thought)
        assertEquals("final", answer)
    }

    @Test
    fun textAfterTheThoughtIsNeverSplitAgain() {
        // Una vez en estado "respuesta", un "<channel|>" posterior es texto normal.
        val (thought, answer) = split(listOf("<|channel>thoughta<channel|>b<channel|>c"))
        assertEquals("a", thought)
        assertEquals("b<channel|>c", answer)
    }

    @Test
    fun anEmptyStreamProducesNothing() {
        val (thought, answer) = split(emptyList())
        assertEquals("", thought)
        assertEquals("", answer)
    }

    @Test
    fun aLongUnclosedAssumedThoughtIsFlushedAsAnswer() {
        // Si se asumio razonamiento y no llega el final en 4000 caracteres, el
        // texto se pasa a respuesta para no tragarselo todo.
        val largo = "a".repeat(5000)
        val (thought, answer) = split(listOf(largo), assumeThinking = true)
        assertEquals("", thought)
        assertTrue("El texto largo debe pasar a respuesta", answer.length > 4000)
    }
}
