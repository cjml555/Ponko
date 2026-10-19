package com.yumenode.ai

import java.util.Locale

/**
 * Pure, testable send-time logic factored out of `doSend()` in MainActivity.
 *
 * `doSend()` orchestrates model/engine state (Conversation, llamaModel, engine,
 * attachmentHandler) and is intentionally NOT moved here. What moves is the
 * *stateless* decision math that only depends on the inputs already collected
 * by doSend — this lets us pin the current behavior with JVM unit tests
 * (ChatSendLogicTest) *before* refactoring doSend to call back into this class,
 * satisfying the constraint that doSend/runRound may only be touched once
 * characterization coverage exists.
 */
object ChatSendLogic {

    /**
     * Mirrors the exact composition in `doSend()`:
     *  - text only → text
     *  - fileBlock only → fileBlock
     *  - both → text + "\n\n" + fileBlock
     * (empty text AND empty fileBlock is rejected by the caller before this)
     */
    fun composeSendText(text: String, fileBlock: String): String =
        if (fileBlock.isEmpty()) text
        else if (text.isEmpty()) fileBlock
        else text + "\n\n" + fileBlock

    /**
     * Mirrors the title-fallback in `doSend()`: when the session still holds
     * the placeholder title, seed it from the user text (first 18 chars) or
     * fall back to the first attached file name, then to the default title.
     */
    fun computeSeedTitle(
        text: String,
        firstFileName: String?,
        defaultTitle: String,
        isPlaceholderTitle: Boolean,
    ): String {
        if (!isPlaceholderTitle) return defaultTitle
        val fallback = if (firstFileName != null && firstFileName.isNotEmpty()) firstFileName else defaultTitle
        val seed = if (text.isNotEmpty()) text else fallback
        return seed.take(18)
    }

    /** Number of non-empty image paths encoded in a "|" separated `userImage`. */
    fun countImages(userImage: String?): Int =
        userImage?.split("|")?.count { it.isNotEmpty() } ?: 0

    /**
     * Mirrors `tagHint()`: a localized notice (s_329, 1-arg count) followed by
     * the tool prompt that tells a vision-less model it may call <tag>.
     * `toolPrompt` carries the exact string currently in `tagToolPrompt`.
     */
    fun buildTagHint(count: Int, s_329: String, toolPrompt: String): String =
        s_329.format(Locale.getDefault(), count) + "\n" + toolPrompt
}
