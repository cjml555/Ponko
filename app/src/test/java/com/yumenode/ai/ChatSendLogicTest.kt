package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization tests for ChatSendLogic — pin the *current* doSend() behavior
 * extracted as pure functions BEFORE wiring them into doSend(). Each case maps to
 * an exact branch in the production code so a refactor that drifts behavior fails here.
 */
class ChatSendLogicTest {

    // ── composeSendText: file/text merge logic (doSend L3290–3293) ──────────
    @Test fun text_only_returns_text() =
        assertEquals("hola", ChatSendLogic.composeSendText("hola", ""))

    @Test fun fileBlock_only_returns_fileBlock() =
        assertEquals("fb", ChatSendLogic.composeSendText("", "fb"))

    @Test fun both_returns_text_double_newline_fileBlock() =
        assertEquals("hola\n\nfb", ChatSendLogic.composeSendText("hola", "fb"))

    @Test fun both_empty_returns_empty() =
        assertEquals("", ChatSendLogic.composeSendText("", ""))

    // ── computeSeedTitle: title fallback (doSend L3442–3445, take(18)) ────────
    @Test fun placeholder_with_text_seeds_from_text_capped_18() {
        val t = "abcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals("abcdefghijklmnopqr", ChatSendLogic.computeSeedTitle(
            text = t, firstFileName = null,
            defaultTitle = "New chat", isPlaceholderTitle = true))
    }

    @Test fun placeholder_empty_text_falls_back_to_firstFile() {
        assertEquals("model.gguf", ChatSendLogic.computeSeedTitle(
            text = "", firstFileName = "model.gguf",
            defaultTitle = "New chat", isPlaceholderTitle = true))
    }

    @Test fun placeholder_empty_text_and_file_falls_back_to_default() {
        assertEquals("New chat", ChatSendLogic.computeSeedTitle(
            text = "", firstFileName = null,
            defaultTitle = "New chat", isPlaceholderTitle = true))
    }

    @Test fun non_placeholder_returns_default_untouched() {
        assertEquals("Previous session title", ChatSendLogic.computeSeedTitle(
            text = "ignored text", firstFileName = "ignored.gguf",
            defaultTitle = "Previous session title", isPlaceholderTitle = false))
    }

    @Test fun seeded_title_never_exceeds_18_chars() {
        assertEquals(18, ChatSendLogic.computeSeedTitle(
            text = "this is a very very long user prompt", firstFileName = null,
            defaultTitle = "New chat", isPlaceholderTitle = true).length)
    }

    // ── countImages: userImage "|" split (doSend L3372, tagHint L320) ────────
    @Test fun null_images_is_zero() =
        assertEquals(0, ChatSendLogic.countImages(null))

    @Test fun empty_string_is_zero() =
        assertEquals(0, ChatSendLogic.countImages(""))

    @Test fun single_image() =
        assertEquals(1, ChatSendLogic.countImages("u_1.jpg"))

    @Test fun multiple_trailing_empty_ignored() =
        assertEquals(2, ChatSendLogic.countImages("a.jpg|b.jpg|"))

    // ── buildTagHint: notice + tool prompt (doSend L3358, tagHint L319–322) ─
    @Test fun buildTagHint_concatenates_count_and_prompt() {
        val rendered = ChatSendLogic.buildTagHint(2, "Noticed %d images", "TOOL")
        assertEquals("Noticed 2 images\nTOOL", rendered)
    }

    @Test fun buildTagHint_zero_then_prompt() {
        val rendered = ChatSendLogic.buildTagHint(0, "Noticed %d images", "TOOL")
        assertEquals("Noticed 0 images\nTOOL", rendered)
    }
}
