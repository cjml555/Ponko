package com.yumenode.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del formato de la API local compatible con OpenAI.
 *
 * Esta es la **superficie publica** de la app: lo que ven Hermes, OpenWebUI,
 * Continue o cualquier otro cliente. Un cambio de forma rompe agentes en
 * silencio, asi que el formato queda fijado aqui.
 */
class OpenAiApiTest {

    // ---------- lectura de mensajes ----------

    @Test
    fun stringContentIsKeptAsIs() {
        assertEquals("hola", OpenAiApi.contentToText("hola"))
    }

    @Test
    fun nullContentBecomesEmpty() {
        assertEquals("", OpenAiApi.contentToText(null))
    }

    @Test
    fun anArrayOfTextPartsIsConcatenated() {
        val arr = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "parte1 "))
            .put(JSONObject().put("type", "text").put("text", "parte2"))
        assertEquals("parte1 parte2", OpenAiApi.contentToText(arr))
    }

    @Test
    fun imagePartsBecomeTheImageMarker() {
        val arr = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "mira "))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:...")))
        assertEquals("mira [image]", OpenAiApi.contentToText(arr))
    }

    @Test
    fun inputTextPartsAreAlsoText() {
        val arr = JSONArray().put(JSONObject().put("type", "input_text").put("text", "nuevo formato"))
        assertEquals("nuevo formato", OpenAiApi.contentToText(arr))
    }

    @Test
    fun unknownPartTypesAreIgnored() {
        val arr = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "visible"))
            .put(JSONObject().put("type", "audio").put("data", "xxx"))
        assertEquals("visible", OpenAiApi.contentToText(arr))
    }

    @Test
    fun messagesAreParsedWithTheirRole() {
        val arr = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "eres util"))
            .put(JSONObject().put("role", "user").put("content", "hola"))
        val msgs = OpenAiApi.parseMessages(arr)

        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("eres util", msgs[0].text)
        assertEquals("user", msgs[1].role)
    }

    @Test
    fun aMessageWithoutRoleDefaultsToUser() {
        val arr = JSONArray().put(JSONObject().put("content", "sin rol"))
        assertEquals("user", OpenAiApi.parseMessages(arr)[0].role)
    }

    @Test
    fun nullMessagesGiveAnEmptyList() {
        assertTrue(OpenAiApi.parseMessages(null).isEmpty())
    }

    // ---------- validacion de la peticion ----------

    @Test
    fun aUserMessageIsRequired() {
        val msgs = listOf(OpenAiApi.Message("user", "hola"))
        assertTrue(OpenAiApi.hasUserMessage(msgs))
    }

    @Test
    fun aSystemOnlyRequestIsNotServable() {
        val msgs = listOf(OpenAiApi.Message("system", "eres util"))
        assertFalse(OpenAiApi.hasUserMessage(msgs))
    }

    @Test
    fun anEmptyUserMessageIsNotServable() {
        assertFalse(OpenAiApi.hasUserMessage(listOf(OpenAiApi.Message("user", ""))))
    }

    // ---------- sampling ----------

    @Test
    fun temperatureIsReadWhenPresent() {
        val req = JSONObject().put("temperature", 0.7)
        assertEquals(0.7f, OpenAiApi.parseSampling(req).temperature!!, 0.0001f)
    }

    @Test
    fun temperatureIsNullWhenAbsent() {
        assertNull(OpenAiApi.parseSampling(JSONObject()).temperature)
    }

    @Test
    fun maxTokensIsRead() {
        val req = JSONObject().put("max_tokens", 512)
        assertEquals(512, OpenAiApi.parseSampling(req).maxTokens)
    }

    @Test
    fun maxCompletionTokensIsTheFallback() {
        val req = JSONObject().put("max_completion_tokens", 256)
        assertEquals(256, OpenAiApi.parseSampling(req).maxTokens)
    }

    @Test
    fun maxTokensWinsOverMaxCompletionTokens() {
        val req = JSONObject().put("max_tokens", 100).put("max_completion_tokens", 256)
        assertEquals(100, OpenAiApi.parseSampling(req).maxTokens)
    }

    @Test
    fun bothAbsentGivesNull() {
        assertNull(OpenAiApi.parseSampling(JSONObject()).maxTokens)
    }

    // ---------- respuestas ----------

    @Test
    fun theCompletionHasTheOpenAiShape() {
        val usage = JSONObject().put("prompt_tokens", 10).put("completion_tokens", 20).put("total_tokens", 30)
        val body = OpenAiApi.chatCompletion("chatcmpl-1", "m", 1700000000L, "respuesta", usage)

        assertEquals("chatcmpl-1", body.getString("id"))
        assertEquals("chat.completion", body.getString("object"))
        assertEquals("m", body.getString("model"))
        assertEquals(1700000000L, body.getLong("created"))

        val choices = body.getJSONArray("choices")
        assertEquals(1, choices.length())
        val choice = choices.getJSONObject(0)
        assertEquals(0, choice.getInt("index"))
        assertEquals("assistant", choice.getJSONObject("message").getString("role"))
        assertEquals("respuesta", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals(30, body.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun aChunkHasTheDeltaShape() {
        val chunk = OpenAiApi.chatChunk("id1", "m", 1L, "trozo")

        assertEquals("chat.completion.chunk", chunk.getString("object"))
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals("trozo", choice.getJSONObject("delta").getString("content"))
        assertTrue("El finish_reason de un chunk es null", choice.isNull("finish_reason"))
    }

    @Test
    fun theFirstChunkCarriesTheRole() {
        val chunk = OpenAiApi.chatChunk("id1", "m", 1L, "", role = "assistant")
        val delta = chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("assistant", delta.getString("role"))
        assertEquals("", delta.getString("content"))
    }

    @Test
    fun aChunkWithoutRoleHasNoRoleInTheDelta() {
        val chunk = OpenAiApi.chatChunk("id1", "m", 1L, "trozo")
        val delta = chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertFalse("El delta no debe llevar role si no se pide", delta.has("role"))
    }

    @Test
    fun theFinishChunkHasAnEmptyDeltaAndStop() {
        val chunk = OpenAiApi.chatChunkFinish("id1", "m", 1L)
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals(0, choice.getJSONObject("delta").length())
        assertEquals("stop", choice.getString("finish_reason"))
    }

    // ---------- SSE ----------

    @Test
    fun anSseEventIsDataJsonAndABlankLine() {
        val ev = OpenAiApi.sseEvent(JSONObject().put("a", 1))
        assertTrue("El evento SSE empieza por 'data: '", ev.startsWith("data: "))
        assertTrue("El evento SSE acaba en linea en blanco", ev.endsWith("\n\n"))
        assertTrue(ev.contains("\"a\":1"))
    }

    @Test
    fun theDoneMarkerIsTheOpenAiOne() {
        assertEquals("data: [DONE]\n\n", OpenAiApi.SSE_DONE)
    }

    // ---------- errores ----------

    @Test
    fun theErrorHasTheOpenAiShape() {
        val e = OpenAiApi.error("algo fallo", OpenAiApi.ErrorType.SERVER)
        assertEquals("algo fallo", e.getJSONObject("error").getString("message"))
        assertEquals("server_error", e.getJSONObject("error").getString("type"))
    }

    @Test
    fun theErrorTypesAreTheOpenAiOnes() {
        assertEquals("invalid_request_error", OpenAiApi.ErrorType.INVALID_REQUEST)
        assertEquals("rate_limit_error", OpenAiApi.ErrorType.RATE_LIMIT)
        assertEquals("server_error", OpenAiApi.ErrorType.SERVER)
        assertEquals("service_unavailable", OpenAiApi.ErrorType.SERVICE_UNAVAILABLE)
    }

    // ---------- modelos ----------

    @Test
    fun theModelListHasTheOpenAiShape() {
        val list = OpenAiApi.modelList("m", "yumenode", 1700000000L)
        assertEquals("list", list.getString("object"))
        val data = list.getJSONArray("data")
        assertEquals(1, data.length())
        assertEquals("m", data.getJSONObject(0).getString("id"))
        assertEquals("model", data.getJSONObject(0).getString("object"))
        assertEquals("yumenode", data.getJSONObject(0).getString("owned_by"))
    }
}
