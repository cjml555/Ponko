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

    @Test
    fun contentBeforeTheMarkerIsAnswer() {
        val (thought, answer) = split(listOf("prefijo<|channel>thoughtidea<channel|>final"))
        assertEquals("idea", thought)
        assertEquals("prefijofinal", answer)
    }

    // ---------- marcador de DeepSeek / Qwen / GLM ----------

    @Test
    fun thinkingMarkerSplitsThoughtFromAnswer() {
        val (thought, answer) = split(listOf(" thinkingalgo que pienso<｜end▁of▁thinking｜>la respuesta"))
        assertEquals("algo que pienso", thought)
        assertEquals("la respuesta", answer)
    }

    @Test
    fun theThinkingMarkerCanArriveSplitInChunks() {
        val (thought, answer) = split(
            listOf(" think", "ingpienso", "<｜end▁of", "▁thinking｜>respuesta")
        )
        assertEquals("pienso", thought)
        assertEquals("respuesta", answer)
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

    @Test
    fun aMarkerSplitAtTheVeryEndIsHeldUntilTheNextChunk() {
        // "<|chan" puede ser el inicio del marcador: se retiene en vez de
        // emitirse como texto (y el texto que si se sabe que no es marcador
        // se emite ya).
        val sink = Sink()
        val s = ReasoningSplitter({ sink.thought.append(it) }, { sink.answer.append(it) })
        s.feed("Hola<|chan")
        assertEquals("Hola", sink.answer.toString())

        s.feed("nel>thoughtx<channel|>fin")
        s.finish()
        assertEquals("x", sink.thought.toString())
        // "Hola" ya se habia emitido; el "fin" de despues va a respuesta.
        assertEquals("Hola" + "fin", sink.answer.toString())
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
