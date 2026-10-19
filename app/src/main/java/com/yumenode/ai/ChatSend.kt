// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.yumenode.ai.draw.DrawPage
import com.yumenode.ai.draw.GgufProbe
import com.yumenode.ai.draw.TaggerEngine
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.channelFlow
import net.ladenthin.llama.LlamaIterator
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import net.ladenthin.llama.value.ChatMessage
import net.ladenthin.llama.value.ContentPart
import net.ladenthin.llama.value.Pair
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import com.yumenode.ai.SessionManager
import com.yumenode.ai.QaTurn as ManagerQaTurn
import com.yumenode.ai.ChatManager
import com.yumenode.ai.QaTurn
import com.yumenode.ai.ChatSession
import com.yumenode.ai.AttachmentHandler
import com.yumenode.ai.RoundOut
import com.yumenode.ai.ToolReturn
import com.yumenode.ai.tagRolesGguf

/*
 * Pipeline de envio de chat, extraido de MainActivity.
 *
 * Son extensiones sobre MainActivity: comparten su estado (modelo cargado,
 * conversacion, vista de burbujas) sin obligar a una interfaz de 40 miembros.
 * Al vivir en su propio archivo, el flujo (doSend -> runRound) queda junto y
 * MainActivity deja de ser el contenedor de todo.
 */

    internal suspend fun MainActivity.runRound(
        turn: QaTurn,
        ai: MainActivity.AiArea,
        gguf: Boolean,
        jpegs: List<ByteArray>,
        overText: String,
        displayPrefix: String,
        thoughtPrefix: String,
        toolReturn: ToolReturn?,
        dropLastForBudget: Boolean,
        hideStream: Boolean,
    ): RoundOut {
        val answerBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        var lastRender = 0L

        fun renderAnswer() {
            // 第一轮可能先藏着不显示（等确认要不要调工具），见 hideStream
            if (hideStream) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRender < 120) return
            lastRender = now
            val md = displayPrefix + answerBuf
            if (md.isNotEmpty()) {
                // 流式重渲染可能把焦点从输入框抢走：用户正在打字时把焦点还回去
                val hadFocus = inputEdit.hasFocus()
                markwonStream.setMarkdown(ai.answer, md)
                if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
            }
        }

        fun renderThought() {
            val now = SystemClock.uptimeMillis()
            if (now - lastRender < 90) return
            val hadFocus = inputEdit.hasFocus()
            ai.thoughtBody.text = thoughtPrefix + thoughtBuf
            ai.thoughtHeader.text = if (ai.thoughtBody.visibility == View.VISIBLE)
                getString(R.string.s_206) else getString(R.string.s_205)
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
        }

        // 「工具返回」轮：模板/引擎可能不认 system、tool 角色（会直接抛错），
        // 那就换个候选角色重发一次；一但试出能用的就记住，后面不再试错。
        var attempt = if (toolReturn == null) 0 else tagReturnIdx.coerceIn(0, 2)
        var genStart = 0L
        lastTpsFoot = null
        while (true) {
            answerBuf.setLength(0)
            thoughtBuf.setLength(0)
            try {
                genStart = SystemClock.uptimeMillis()
                if (gguf) {
                    val lm = llamaModel ?: return RoundOut("", "")
                    val extra: List<net.ladenthin.llama.value.Pair<String, String>> = if (toolReturn == null) {
                        emptyList()
                    } else {
                        listOf(
                            net.ladenthin.llama.value.Pair("assistant", toolReturn.modelText),
                            net.ladenthin.llama.value.Pair(tagRolesGguf[attempt], toolReturn.text),
                        )
                    }
                    // generateChat 会套用模型自带的对话模板；cache_prompt=true + 固定 slot
                    // 让 llama.cpp 复用上一轮已算好的 KV 前缀，长对话不再重复 prefill。
                    // 本回合带图且模型能看图 → 走多模态消息（ContentPart）；否则沿用纯文字路径
                    val params = if (visionOk && jpegs.isNotEmpty()) {
                        inferenceParamsBuilder.buildMultimodalParams(current, jpegs, extra)
                    } else {
                        inferenceParamsBuilder.buildInferenceParams(current, extra)
                    }

                    // GGUF 模型的思考内容混在正文流里（<|channel>thought…<channel|> 或 …），
                    // 用状态机把两路分开：思考进折叠区，正文走 Markdown 渲染。
                    fun onThoughtDelta(d: String) {
                        if (d.isEmpty()) return
                        thoughtBuf.append(d)
                        turn.thought = thoughtPrefix + thoughtBuf
                        if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                        renderThought()
                    }

                    fun onAnswerDelta(d: String) {
                        if (d.isEmpty()) return
                        answerBuf.append(d)
                        turn.answer = displayPrefix + answerBuf
                        renderAnswer()
                    }

                    // 开了思考模式就默认从思考区开始：Gemma 这类模型的 `<|channel>thought`
                    // 前缀是写在 prompt 模板里的，不会出现在输出流中，只能靠结束标记分界。
                    val splitter = ReasoningSplitter(
                        ::onThoughtDelta,
                        ::onAnswerDelta,
                        assumeThinking = thinkingActive,
                    )

                    val flow = channelFlow {
                        withContext(Dispatchers.IO) {
                            val iterable = lm.generateChat(params)
                            val it = iterable.iterator()
                            ggufIterator = it
                            try {
                                // 用户中断后 native 迭代器会抛异常（如 task not found），
                                // 此时正常收尾，不当成错误
                                val next = {
                                    try {
                                        if (it.hasNext()) it.next() else null
                                    } catch (e: Throwable) {
                                        if (!stopRequested) throw e
                                        null
                                    }
                                }
                                while (true) {
                                    val piece = next() ?: break
                                    if (piece.text.isNotEmpty()) send(piece.text)
                                }
                            } finally {
                                runCatching { it.close() }
                                ggufIterator = null
                            }
                        }
                    }
                    flow.collect { piece ->
                        splitter.feed(piece)
                        chatScrollManager?.scrollToBottom()
                    }
                    splitter.finish()
                    renderThought()
                } else {
                    // Flow emits INCREMENTAL chunks (not snapshots): accumulate.
                    // Reasoning text arrives on channels["thought"], answer text in contents.
                    val items = ArrayList<Content>()
                    if (overText.isNotEmpty()) items.add(Content.Text(overText))
                    // 模型自己看不见图时不要把图片塞给它（图片只交给打标模型用）
                    if (visionOk) jpegs.forEach { items.add(Content.ImageBytes(it)) }
                    // LiteRT 的会话是【增量累积】的：历史 + 本轮一旦超过模型上下文（4096），
                    // native 直接抛 "Input token ids too long"，这一轮就废了。
                    // 所以发送前自己估一把：超预算就重建会话——configFor 会按预算裁掉最老的对话。
                    if (chatParams.dirty ||
                        chatManager.estimateConversationTokens(
                            current, overText, dropLast = dropLastForBudget, 
                            canDrawFromChat = canDrawFromChat, 
                            drawToolPrompt = drawToolPrompt
                        ) > chatParams.inputTokenBudget()
                    ) {
                        rebuildConversation(silent = true, dropLast = dropLastForBudget)
                        chatParams.setDirty(false)
                    }
                    val conv = conversation ?: throw IllegalStateException(getString(R.string.s_042))
                    val tc = ThinkingConfig(
                        enableThinking = thinkingActive,
                        thinkingTokenBudget = chatParams.thinkBudgetOrUnlimited(),
                    )
                    val flow = when {
                        toolReturn == null -> {
                            val ask: Contents = if (items.size == 1 && items[0] is Content.Text) {
                                Contents.of(overText)
                            } else {
                                Contents.of(items)
                            }
                            conv.sendMessageAsync(ask, thinkingConfig = tc)
                        }
                        // 工具结果优先按「工具返回」回传（LiteRT-LM 原生帧），
                        // 模板不认再退回 system 消息，最后才退回普通文本消息。
                        attempt == 0 -> conv.sendMessageAsync(
                            Message.tool(Contents.of(Content.ToolResponse("tagger", toolReturn.text))),
                            thinkingConfig = tc,
                        )
                        attempt == 1 -> conv.sendMessageAsync(
                            Message.system(toolReturn.text),
                            thinkingConfig = tc,
                        )
                        else -> conv.sendMessageAsync(Message.user(toolReturn.text), thinkingConfig = tc)
                    }
                    flow.collect { msg ->
                        val delta = extractText(msg)
                        val thoughtDelta = msg.channels["thought"]
                        if (!thoughtDelta.isNullOrEmpty()) {
                            val cur = thoughtBuf.toString()
                            val merged = mergeStreamDelta(cur, thoughtDelta)
                            if (merged != cur) {
                                thoughtBuf.setLength(0)
                                thoughtBuf.append(merged)
                                turn.thought = thoughtPrefix + merged
                                if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                                renderThought()
                            }
                        }
                        if (delta.isNotEmpty()) {
                            val cur = answerBuf.toString()
                            val merged = mergeStreamDelta(cur, delta)
                            if (merged != cur) {
                                answerBuf.setLength(0)
                                answerBuf.append(merged)
                                turn.answer = displayPrefix + merged
                                renderAnswer()
                            }
                        }
                        chatScrollManager?.scrollToBottom()
                    }
                    renderThought()
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 只在「一个字都还没吐出来」时换角色重试，避免把半截输出丢了
                val retry = toolReturn != null && !stopRequested && attempt < 2 &&
                    answerBuf.isEmpty() && thoughtBuf.isEmpty() && !isContextOverflow(e)
                if (!retry) throw e
                attempt++
                tagReturnIdx = attempt
                // 这一条消息可能已经被引擎吃进会话，重建一次把这轮的脏状态丢掉
                if (!gguf) runCatching { rebuildConversation(silent = true) }
            }
        }

        // 本轮收尾：用完整渲染器渲染一次（含表格），并把本轮内容并进这一轮的回答
        val ans = answerBuf.toString()
        val th = thoughtBuf.toString()
        val full = displayPrefix + ans
        turn.answer = full
        turn.thought = thoughtPrefix + th
        if (!hideStream) when {
            ans.isEmpty() && th.isEmpty() && displayPrefix.isEmpty() -> {
                ai.answer.text = getString(R.string.s_007)
                ai.answer.setTextColor(C_SUBTEXT)
            }
            ans.isEmpty() && displayPrefix.isEmpty() && th.isNotEmpty() -> {
                // Modelo sin canal de respuesta (típico: no-reasoning con "Thinking
                // mode" activo) → no dejar la burbuja vacía: usar lo generado como
                // respuesta. Antes solo se veía "(model returned reasoning only)".
                markwonFull.setMarkdown(ai.answer, th)
                ai.answer.setTextColor(C_TEXT)
                turn.answer = th
            }
            full.isNotEmpty() -> {
                // tps: estimación ~4 chars/token; nota itálica al pie (no persistida en turn.answer)
                val elapsed = SystemClock.uptimeMillis() - genStart
                val foot = if (elapsed >= 50 && ans.isNotEmpty()) {
                    val tok = ans.length / 4.0
                    "\n\n_≈" + "%.1f".format(tok / (elapsed / 1000.0)) + " tok/s · " +
                        "%.1f".format(elapsed / 1000.0) + "s_"
                } else null
                lastTpsFoot = foot
                markwonFull.setMarkdown(ai.answer, if (foot != null) full + foot else full)
            }
        }
        ai.thoughtBody.text = thoughtPrefix + th
        chatScrollManager?.scrollToBottom()
        return RoundOut(ans, th)
    }

    internal fun MainActivity.doSend() {
        val text = inputEdit.text.toString().trim()
        val attachBmps = attachmentHandler.getPendingImages().map { it.bmp }
        val attachJpegs = attachmentHandler.getPendingImages().map { it.jpeg }
        // 用户这一轮带的文件：附件条里有就正常发（「重新生成」也会先把上一轮的文件还原回附件条）
        if (text.isEmpty() && attachJpegs.isEmpty() && attachmentHandler.getPendingFiles().isEmpty()) return
        // 文件是「文字」，任何模型都能读，不受多模态限制
        val fileBlock = chatManager.buildFileBlock(attachmentHandler.getPendingFiles())
        val fileNames = attachmentHandler.getPendingFiles().map { it.name }
        var sendText = ChatSendLogic.composeSendText(text, fileBlock)
        // 生成过程中不允许发起新的一轮（选图/引用可以在生成中进行，发送不行）
        if (busy) { toast(getString(R.string.s_133)); return }

        // 带图发送：模型要么自己能看图（.litertlm 多模态 / 挂了 mmproj 的 gguf），
        // 要么能借打标模型看图（看不见图时用 <tag> 命令让打标模型识别）
        if (attachJpegs.isNotEmpty()) {
            if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
            if (!visionOk && !canTagFromChat) { toast(getString(R.string.s_272)); return }
        }

        // 绘图模式：把输入当作正面提示词，按绘图页的参数（除正面提示词外）生成
        val dpg = drawPage
        if (drawMode && dpg != null) {
            doDrawFromChat(text, dpg)
            return
        }

        val isGgufRun = llamaModel != null
        if (conversation == null && !isGgufRun) { toast(getString(R.string.s_174)); return }
        if (busy) {
            toast(getString(R.string.s_133))
            return
        }
        var conv: Conversation? = null
        if (!isGgufRun) {
            val needThinkingRebuild = convThinking != thinkCheck.isChecked
            // 绘图能力开关变了（加载/卸载绘图模型）也要重建：
            // system 里的 <draw> 提示只在建会话时写入，不重建则模型不知道能画图
            val needCapRebuild = convDrawCapable != canDrawFromChat
            if (convNeedsRebuild || needThinkingRebuild || needCapRebuild) {
                // 上一轮被中断：cancelProcess() 之后会话可能已不可用，静默用历史重建
                convNeedsRebuild = false
                // 只有「思考开关变化」需要给用户可见提示，中断恢复/能力变化都静默重建
                rebuildConversation(silent = !needThinkingRebuild)
            }
            conv = conversation ?: run { toast(getString(R.string.s_042)); return }
        }

        val turn = QaTurn(text)
        current.turns += turn
        // 模型看不见图：把可用的打标命令说明附在本轮消息末尾（不进历史）
        // 注意：必须等 turn.userImage 写好后才能判断，否则提示根本不会被加上
        if (fileNames.isNotEmpty()) {
            turn.userFile = fileNames.joinToString("|")
            turn.fileText = fileBlock
            // 把文件原始内容存进这一轮，方便「重新生成」把附件还原回附件条
            turn.fileStore = chatManager.encodeFiles(attachmentHandler.getPendingFiles())
        }
        if (current.title == getString(R.string.s_109)) {
            current.title = ChatSendLogic.computeSeedTitle(
                text = text,
                firstFileName = fileNames.firstOrNull(),
                defaultTitle = getString(R.string.s_278),
                isPlaceholderTitle = true,
            )
        }

        if (attachJpegs.isNotEmpty()) {
            val names = ArrayList<String>()
            for ((idx, img) in attachmentHandler.getPendingImages().withIndex()) {
                val nm = "u_${System.currentTimeMillis()}_$idx.jpg"
                if (writeChatJpeg(img.bmp, nm)) names.add(nm)
            }
            if (names.isNotEmpty()) turn.userImage = names.joinToString("|")
            attachmentHandler.clearPendingImages()
        }
        // 模型看不见图：本轮带图时先告诉它「这条消息带了图片」，再给打标工具说明，
        // 否则它会直接反问「请提供图片」（这段只用于本轮，不进历史）
        if (canTagTurn(turn)) sendText += "\n\n" + tagHint(turn)
        attachmentHandler.clearPendingFiles()

        inputEdit.setText("")
        addUserBubble(text, attachBmps, fileNames)
        chatScrollManager?.jumpToBottom()
        applyBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_143), C_WARN)

        val ai = addAiArea { regenerate(turn) }
        ai.regenButton.visibility = View.GONE   // 生成结束后再显示，避免与「停止」混淆
        var cancelled = false
        stopRequested = false

        genJob = scope.launch {
            var drawReq: String? = null
            var shown = ""          // 气泡里当前的正文（前几轮 + 本轮输出）
            var thoughtShown = ""   // 已经落在思考区里的内容
            try {
                // ===== agent 循环：生成 → 模型要调工具就执行 → 把结果补回上下文再生成 =====
                var overText = sendText   // 本轮要发的文本（第一轮＝输入＋文件正文＋工具说明）
                // 打标结果按「工具返回」回传（system / tool 角色，不冒充用户消息）
                var toolReturn: ToolReturn? = null
                var round = 0
                var autoTagged = false
                var tagRan = false      // 这一轮真的跑过打标（用于兜底纠偏）
                var nudgeUsed = false
                // 带图、模型又看不见图：第一轮先显示一句占位（这一轮的内容不显示，免得命令命中后整段消失重来）
                if (canTagTurn(turn)) markwonStream.setMarkdown(ai.answer, getString(R.string.s_323))
                while (true) {
                    val out = runRound(
                        turn = turn,
                        ai = ai,
                        gguf = isGgufRun,
                        jpegs = attachJpegs,
                        overText = overText,
                        displayPrefix = shown,
                        thoughtPrefix = thoughtShown,
                        toolReturn = toolReturn,
                        dropLastForBudget = round == 0,
                        // 带图、模型又看不见图时，第一轮先不显示：它可能先啰嗦一段再吐 <tag>，
                        // 直接显示的话命令命中后这段会消失重来
                        hideStream = round == 0 && canTagTurn(turn),
                    )
                    var roundText = out.answer

                    // ① 出图命令优先：交给绘图模型（真正出图放在 finally，必须等 busy 复位）
                    val drawHit = scanDrawCommand(roundText, out.thought)
                    if (drawHit != null) {
                        if (drawHit.inAnswer) {
                            roundText = roundText.replaceRange(
                                drawHit.start, drawHit.end, getString(R.string.s_200, drawHit.prompt)
                            )
                            thoughtShown += out.thought
                        } else {
                            thoughtShown += out.thought.replaceRange(
                                drawHit.start, drawHit.end, getString(R.string.s_199, drawHit.prompt)
                            )
                        }
                        shown += roundText
                        drawReq = drawHit.prompt
                        break
                    }

                    // ② 打标命令：模型自己看不见图时，让它借打标模型看图，拿到标签再回答
                    val tagHit = if (round < MAX_TAG_ROUNDS) scanTagCommand(roundText, out.thought) else null
                    // 兜底：小模型经常无视图片工具、直接反问「请提供图片」。这一轮带图、它又像在推辞时，
                    // App 替它调用一次打标模型（阈值/标签数用默认 0.35 / 40），结果照样按「工具返回」发回去。
                    val autoTagRes: String? = if (tagHit == null && round == 0 && !autoTagged && canTagFromChat &&
                        attachJpegs.isNotEmpty() && commandScanner.looksLikeBlindRefusal(roundText)
                    ) {
                        autoTagged = true
                        val autoNames = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
                        val r = if (autoNames.isEmpty()) "" else {
                            // 打标结果不展示：先把这一轮的推辞内容清掉
                            shown = ""
                            turn.answer = ""
                            markwonFull.setMarkdown(ai.answer, "")
                            withContext(Dispatchers.IO) {
                                runCatching { runTaggerOnImages(autoNames, TagReq(0.35f, 40)) }.getOrDefault("")
                            }
                        }
                        r.ifEmpty { null }
                    } else null
                    if (tagHit == null && autoTagRes == null) {
                        val clean = commandScanner.stripTagCommand(roundText)
                        // 模型把工具结果当成新的输入、反过来问用户「请提出您的问题」：明确提示后重来一轮（只一次）
                        if (tagRan && !nudgeUsed && commandScanner.looksLikeQuestionAsk(clean)) {
                            nudgeUsed = true
                            shown = ""
                            turn.answer = ""
                            markwonFull.setMarkdown(ai.answer, "")
                            thoughtShown += out.thought
                            overText = getString(R.string.s_331, turn.user.take(200))
                            round++
                            continue
                        }
                        // 没有可用的工具调用：把可能残留的 <tag> 命令抹掉（模型有读图能力、打标路径已关闭时）
                        shown += clean
                        thoughtShown += out.thought
                        break
                    }
                    if (tagHit == null) {
                        // 兜底路径：模型没调用工具，但打标结果已经有了，直接当工具返回发回去（不展示标签）
                        thoughtShown += out.thought
                        val autoBlock = getString(R.string.s_328, autoTagRes, turn.user.take(200))
                        overText = autoBlock
                        // gguf：用「模型只输出了 <tag> 命令」的干净形态回填，别把上一轮的推辞带进上下文
                        val autoCmd = "<tag threshold=\"0.35\" topk=\"40\"></tag>"
                        toolReturn = ToolReturn(if (isGgufRun) autoCmd else out.answer, autoBlock)
                        tagRan = true
                        round++
                        chatScrollManager?.scrollToBottom()
                        continue
                    }
                    // 把命令换成一行说明：正文里的换在正文，思考里的换在思考区、正文补一行
                    val _tagHit = tagHit!! // safe: already null-checked above at line 4930
                    fun swapLine(replacement: String): String = if (_tagHit.inAnswer) {
                        thoughtShown += out.thought
                        roundText.replaceRange(_tagHit.start, _tagHit.end, replacement)
                    } else {
                        thoughtShown += out.thought.replaceRange(
                            _tagHit.start, _tagHit.end, getString(R.string.s_323)
                        )
                        (roundText + "\n\n" + replacement).trim()
                    }

                    val names = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
                    // 打标结果不展示：先把气泡里这一轮的调用过程清掉，识别完由模型直接给最终回答
                    shown = ""
                    turn.answer = ""
                    markwonFull.setMarkdown(ai.answer, "")
                    val res = if (names.isEmpty()) {
                        "\u0000" + getString(R.string.s_326)
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching { runTaggerOnImages(names, tagHit.req) }.getOrElse { e ->
                                "\u0000" + (e.message ?: "")
                            }
                        }
                    }
                    if (res.startsWith("\u0000")) {
                        // 这轮没有图片、或打标失败：把命令换成原因，本轮就此结束
                        shown += swapLine(getString(R.string.s_325, res.removePrefix("\u0000")))
                        break
                    }
                    if (res.isEmpty()) {
                        shown += swapLine(getString(R.string.s_327))
                        break
                    }
                    // 成功：不展示标签，气泡留空，等模型基于标签给出最终回答
                    thoughtShown += if (tagHit.inAnswer) out.thought else {
                        out.thought.replaceRange(_tagHit.start, _tagHit.end, getString(R.string.s_323))
                    }
                    val block = getString(R.string.s_328, res, turn.user.take(200))
                    overText = block
                    // gguf：用「模型只输出了 <tag> 命令」的干净形态回填，别把它上一轮的碎碎念带进上下文
                    val cmdEcho = "<tag threshold=\"${tagHit.req.threshold}\" topk=\"${tagHit.req.topK}\"></tag>"
                    toolReturn = ToolReturn(if (isGgufRun) cmdEcho else out.answer, block)
                    tagRan = true
                    round++
                    chatScrollManager?.scrollToBottom()
                }
                turn.answer = shown.trimEnd()
                if (turn.answer.isNotEmpty()) {
                    markwonFull.setMarkdown(ai.answer, turn.answer + (lastTpsFoot ?: ""))
                } else if (turn.thought.isEmpty()) {
                    // 第一轮被隐藏、模型又什么都没说：给个提示，别留一个空气泡
                    ai.answer.text = getString(R.string.s_007)
                    ai.answer.setTextColor(C_SUBTEXT)
                }
                sessionManager.save()
                setStatus(getString(R.string.s_081), C_OK)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                if (stopRequested) {
                    // 主动中断：native 被打断后常抛异常（task not found 等），
                    // 按「已中断」处理，保留已经输出到界面上的内容
                    cancelled = true
                } else if (isContextOverflow(e)) {
                    // 上下文塞不下：给能操作的提示，别甩一串 Status Code 给用户
                    ai.answer.text = getString(R.string.s_302)
                    ai.answer.setTextColor(C_ERR)
                    setStatus(getString(R.string.s_145), C_ERR)
                    toast(getString(R.string.s_302))
                } else {
                    ai.answer.text = getString(R.string.v_024, (e.message))
                    ai.answer.setTextColor(C_ERR)
                    setStatus(getString(R.string.s_145), C_ERR)
                    toast(getString(R.string.v_025, (e.message)))
                }
            } finally {
                ai.regenButton.visibility = View.VISIBLE
                sessionManager.save()
                applyBusy(false)
                setStoppingUi(false)
                if (cancelled) {
                    if (turn.answer.isNotEmpty()) {
                        markwonFull.setMarkdown(ai.answer, turn.answer)
                    } else if (turn.thought.isEmpty()) {
                        ai.answer.text = getString(R.string.s_004)
                        ai.answer.setTextColor(C_SUBTEXT)
                    }
                    setStatus(getString(R.string.s_082), C_IDLE)
                } else if (drawReq != null) {
                    // 交给绘图模型出图（必须等 busy 复位后再启动，否则会被当成「正在生成中」拦住）
                    val dpg = drawPage
                    if (dpg != null) doDrawFromChat(drawReq, dpg)
                }
                chatScrollManager?.scrollToBottom()
            }
        }
    }
