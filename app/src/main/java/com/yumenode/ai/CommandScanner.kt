package com.yumenode.ai

/**
 * Data classes for command scanning results.
 * These are used by the command scanning logic to communicate hits to the caller.
 */

data class DrawHit(
    val prompt: String,
    val inAnswer: Boolean,
    val start: Int,
    val end: Int,
)

data class TagReq(
    val threshold: Float,
    val topK: Int,
)

data class TagHit(
    val req: TagReq,
    val inAnswer: Boolean,
    val start: Int,
    val end: Int,
)

/**
 * Handles command scanning for drawing and tagging commands.
 * Pure logic - no UI dependencies. Caller provides state via parameters.
 */
class CommandScanner {

    companion object {
        // Regex patterns - compile once
        val DRAW_CMD_REGEX = Regex("<draw>(.*?)</draw>", RegexOption.DOT_MATCHES_ALL)
        val TAG_CMD_REGEX = Regex("<tag\\b([^>]*)>(.*?)(?:</tag>|$)", RegexOption.DOT_MATCHES_ALL)

        // Tool prompt strings
        val DRAW_TOOL_PROMPT = """
            You have a local drawing model you can delegate to.
            Only use it when the user explicitly asks to generate/draw an image.
            For all other cases (chatting, Q&A, coding, translation, etc.),
            answer normally — don't proactively draw or mention this command.

            When you need to draw, don't say you can't draw, don't refuse,
            and don't suggest the user use another tool —
            just reply with a sentence or two, then on a new line at the end of your reply output:
            <draw>scene description</draw>

            The scene description uses English, comma-separated keywords
            (e.g. 1girl, silver hair, school uniform, cherry blossoms).
            Only describe the scene itself; no parameters, numbering, or explanations.
            This command MUST appear in your final reply body,
            not in the thinking process, or the system won't see it.
        """.trimIndent()

        val TAG_TOOL_PROMPT = """
            You have a local image tagging model you can delegate to.
            Only use it when the user explicitly asks to tag/analyze an image.
            For all other cases, answer normally — don't proactively tag or mention this command.
        """.trimIndent()

        /** Leftover <tag …></tag> command text stripped out of a reply before showing it to the user */
        val TAG_STRIP_REGEX = Regex("<tag\\b[^>]*>(?:.*?</tag>)?", RegexOption.DOT_MATCHES_ALL)

        /** Phrases small models use to refuse an image they can't see (e.g. "please provide an image") */
        private val BLIND_REFUSAL_KEYS = listOf(
            "提供图片", "提供一张图", "发送图片", "发一张图", "发个图", "发张图", "上传图", "看不到图", "无法查看",
            "无法看到", "没有图片", "没收到图", "图片吗", "请提供", "重新发送图",
            "provide an image", "send an image", "send me the image", "no image", "attach", "can't see",
            "cannot see", "unable to see", "please upload", "please provide", "i don't see",
        )

        /** Phrases a model uses when it mistakes a tool return for a new user turn */
        private val QUESTION_ASK_KEYS = listOf(
            "请提出您的问题", "请您提出", "请提出你的问题", "请向我提问", "请告诉我您想问", "请告诉我你的问题",
            "您想问什么", "你想问什么", "您的问题是什么", "你的问题是什么", "等待您的提问", "等待你的提问",
            "请提供您的问题", "请提供你的问题", "有什么可以帮",
            "ask your question", "what would you like to ask", "what is your question", "please ask",
            "let me know your question", "how can i help", "what can i help",
        )
    }

    /**
     * Does the reply read like a refusal from a model that can't see the image
     * (small models routinely ignore the image tool)?
     */
    fun looksLikeBlindRefusal(s: String): Boolean {
        if (s.isEmpty()) return false
        return BLIND_REFUSAL_KEYS.any { s.contains(it, ignoreCase = true) }
    }

    /** Did the model mistake the tool result for a new input and turn around to ask the user a question? */
    fun looksLikeQuestionAsk(s: String): Boolean {
        if (s.isEmpty()) return false
        return QUESTION_ASK_KEYS.any { s.contains(it, ignoreCase = true) }
    }

    /** Drop any leftover <tag …></tag> command so it isn't shown to the user as reply body */
    fun stripTagCommand(text: String): String =
        text.replace(TAG_STRIP_REGEX, "").trim()

    /**
     * Scan a user's answer for a <draw>…</draw> command.
     * Returns a DrawHit if a drawing command is detected.
     */
    fun scanDrawCommand(
        answer: String,
        thought: String,
        canDrawFromChat: Boolean
    ): DrawHit? {
        if (!canDrawFromChat) return null
        // First scan the answer, then scan the thought (thinking mode)
        DRAW_CMD_REGEX.find(answer)?.let { m ->
            val prompt = m.groupValues[1].trim()
            if (prompt.isNotEmpty()) return DrawHit(prompt, true, m.range.first, m.range.last + 1)
        }
        DRAW_CMD_REGEX.find(thought)?.let { m ->
            val prompt = m.groupValues[1].trim()
            if (prompt.isNotEmpty()) return DrawHit(prompt, false, m.range.first, m.range.last + 1)
        }
        return null
    }

    /**
     * Scan a user's answer for a <tag …></tag> command.
     * Returns a TagHit if a tagging command is detected.
     */
    fun scanTagCommand(
        answer: String,
        thought: String,
        canTagFromChat: Boolean
    ): TagHit? {
        if (!canTagFromChat) return null

        fun parse(text: String, inAnswer: Boolean): TagHit? {
            val m = TAG_CMD_REGEX.find(text) ?: return null
            // Parameters may appear in the tag attribute or inside the tag content
            val attrs = m.groupValues[1] + " " + m.groupValues[2]
            val th = Regex("threshold\\s*=\\s*[\"']?([0-9]*\\.?[0-9]+)", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
            val tk = Regex("(topk|top_k|topK|max)\\s*=\\s*[\"']?([0-9]+)", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(2)?.toIntOrNull()
            return TagHit(
                TagReq((th ?: 0.35f).coerceIn(0.05f, 0.99f), (tk ?: 40).coerceIn(1, 80)),
                inAnswer, m.range.first, m.range.last + 1,
            )
        }
        return parse(answer, true) ?: parse(thought, false)
    }
}