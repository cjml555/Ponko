// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
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
// Manager imports extracted from MainActivity
import com.yumenode.ai.SessionManager
import com.yumenode.ai.QaTurn as ManagerQaTurn
import com.yumenode.ai.ChatManager
import com.yumenode.ai.QaTurn
import com.yumenode.ai.ChatSession
import com.yumenode.ai.AttachmentHandler
import com.yumenode.ai.RoundOut
import com.yumenode.ai.ToolReturn
import com.yumenode.ai.tagRolesGguf

/**
 * On-device chat app that loads a Google LiteRT-LM (.litertlm) model from local
 * storage and runs it via com.google.ai.edge.litertlm.
 *
 * UI: flat modern look — deep surface app bar, card-style settings panel, chat bubbles,
 * bottom input bar, collapsible "thinking" section, and Markdown-rendered replies.
 */
class MainActivity : Activity(), SettingsHost, ThemeAliases {

    // ---- palette ----
    // ================= 主题 =================
    // 所有界面颜色都来自 pal（见 YumenodeTheme）；这里只做取名，方便沿用 C_XXX。
    // 新增控件请用这些常量，不要再写死色值，否则深色模式会留下白块。

    /** 主题管理器：separates theme state & palette logic from UI */
    internal val themeManager = ThemeManager(this) {
        // Theme applied — rebuild UI palette accessors
    }

    /** 当前主题模式：0=跟随系统 1=浅色 2=深色（存在 ponko 偏好里） */
    override val themeMode: Int
        get() = themeManager.themeMode

    /** 当前配色表 */
    val pal: YumenodeTheme
        get() = themeManager.pal

    // ---- Manager dependencies ----
    private val crashHandler = CrashHandler(this)
    private val sessionManager = SessionManager(this)
    private val attachmentHandler = AttachmentHandler.create(this, this)

    private val commandScanner = CommandScanner()

    /** Constructores de widgets con el estilo de Yumenode AI. */
    override val ui by lazy { UiWidgets(this) { pal } }

    // ---- SettingsHost ----
    override val activity: Activity get() = this
    override val palette: YumenodeTheme get() = pal
    override val drawBusy: Boolean get() = drawPage?.isBusy() == true
    override fun applyThemeMode(mode: Int) { themeManager.setThemeMode(this, mode) }
    override fun toast(msg: String) {
        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
    override fun prefBool(key: String, def: Boolean) = chatPrefs().getBoolean(key, def)
    override fun prefPutBool(key: String, value: Boolean) {
        chatPrefs().edit().putBoolean(key, value).apply()
    }

    override fun prefStr(key: String, def: String) = chatPrefs().getString(key, def) ?: def
    override fun prefPutStr(key: String, value: String) {
        chatPrefs().edit().putString(key, value).apply()
    }

    /** Envia el ultimo benchmark (anonimo) al endpoint configurado. */
    override fun shareBenchmark(endpoint: String, onResult: (String) -> Unit) {
        val b = lastBench
        if (b == null) { onResult("Run the benchmark first."); return }
        val url = endpoint.trim()
        if (!url.startsWith("http")) { onResult("Set a valid endpoint URL first."); return }
        scope.launch {
            val body = JSONObject().apply {
                put("model", b.model); put("backend", b.backend); put("device", b.device)
                put("soc", b.soc); put("version", appVersion())
                put("tps_avg", b.tpsAvg); put("tps_min", b.tpsMin); put("tps_max", b.tpsMax)
                put("latency_min", b.latMin); put("latency_max", b.latMax); put("tokens", b.tokens)
            }.toString()
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 10000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) "Shared anonymously (HTTP $code)" else "Server returned HTTP $code"
                }.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}" }
            }
            onResult(msg)
        }
    }

    /** Benchmark local: corre N generaciones cortas sobre el modelo cargado y
     *  reporta tok/s, latencia, modelo, backend y dispositivo. No sube nada. */
    override fun runBenchmark(onUpdate: (String) -> Unit) {
        if (!ChatCore.isReady()) { onUpdate("Load a chat model first."); return }
        if (ChatCore.busy) { onUpdate("The model is busy. Try again in a moment."); return }
        scope.launch {
            val prompt = "Write a short, coherent paragraph about the sea."
            val runs = 3
            val tps = ArrayList<Double>()
            val lat = ArrayList<Long>()
            var lastTokens = 0
            for (i in 1..runs) {
                onUpdate("Running $i/$runs…")
                val t0 = SystemClock.uptimeMillis()
                val out = try {
                    ChatCore.generate(listOf(ChatCore.Msg("user", prompt)), 0.7f, 128) { }
                } catch (e: Throwable) {
                    onUpdate("Error: ${e.message ?: e.javaClass.simpleName}")
                    return@launch
                }
                val dt = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1)
                lastTokens = (out.length / 4).coerceAtLeast(1)
                lat += dt
                tps += lastTokens / (dt / 1000.0)
            }
            val name = ChatCore.modelName ?: "?"
            val backend = when {
                name.endsWith(".gguf", true) -> "llama.cpp (CPU)"
                name.endsWith(".litertlm", true) -> "LiteRT (CPU)"
                else -> "?"
            }
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
            val sb = StringBuilder()
            sb.append("Model: ").append(name).append('\n')
            sb.append("Backend: ").append(backend).append('\n')
            sb.append("Device: ").append(Build.MODEL).append(" · ").append(soc).append('\n')
            sb.append("Tokens (est./run): ").append(lastTokens).append('\n')
            sb.append("tok/s: avg ").append("%.1f".format(tps.average()))
                .append(" (min ").append("%.1f".format(tps.min()))
                .append(" / max ").append("%.1f".format(tps.max())).append(")\n")
            sb.append("Latency: min ").append(lat.min()).append(" ms · max ").append(lat.max()).append(" ms")
            lastBench = BenchStats(
                model = name, backend = backend, device = Build.MODEL, soc = soc,
                tpsAvg = tps.average(), tpsMin = tps.min(), tpsMax = tps.max(),
                latMin = lat.min(), latMax = lat.max(), tokens = lastTokens,
            )
            onUpdate(sb.toString())
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    /** El modelo cargado admite 'thinking' (heurístico por nombre). El checkbox
     *  de Thinking se oculta cuando el modelo no soporta razonamiento. */
    private var modelSupportsThinking: Boolean = true
    /** Pensamiento efectivo: el usuario lo activa Y el modelo lo admite. */
    private val thinkingActive: Boolean get() = modelSupportsThinking && thinkCheck.isChecked
    private var taggerUi = TaggerUi(
        this,
        scope,
        pal,
        { drawPage },
        { taggerStatusTv },
        { taggerBox },
        { taggerLoadBtn },
        { setDrawBusy(it) },
        { setMainBusy(it) },
        { showDrawProgress(it) },
        { text, color -> setStatus(text, color) },
        { toast(it) },
    )
    /** 多模态 gguf：视觉投影文件（mmproj）管理委托ado a VisionManager */
    private var visionManager: VisionManager? = null

    /** Construcción de parámetros de inferencia (gguf / litertlm) */
    private val inferenceParamsBuilder: InferenceParamsBuilder by lazy {
        InferenceParamsBuilder(
            chatParams = chatParams,
            drawToolPrompt = drawToolPrompt,
            canDrawFromChat = { canDrawFromChat },
            canTagTurn = { canTagTurn(it) },
            tagHint = { tagHint(it) },
            thinkChecked = { thinkingActive },
        ).apply {
            estimateTokens = { chatManager.estTokens(it).toInt() }
        }
    }

    // ---- 对话参数（模型页 · 对话模型）----
    private val chatParams = ChatParams(this) { modelPath }
    /** 回填参数到输入框时临时屏蔽 TextWatcher，免得多字段被互相覆盖 */
    private val chatParamsOwnerTv: TextView? get() = modelsPage.chatParamsOwnerTv
    private val chatCtxEdit: EditText? get() = modelsPage.chatCtxEdit
    private val chatMaxOutEdit: EditText? get() = modelsPage.chatMaxOutEdit
    private val chatTempEdit: EditText? get() = modelsPage.chatTempEdit
    private val chatTopKEdit: EditText? get() = modelsPage.chatTopKEdit
    private val chatTopPEdit: EditText? get() = modelsPage.chatTopPEdit
    private val chatRepEdit: EditText? get() = modelsPage.chatRepEdit
    private val chatBudgetEdit: EditText? get() = modelsPage.chatBudgetEdit
    private val chatSeedEdit: EditText? get() = modelsPage.chatSeedEdit
    private var busy = false

    /** Chat 消息处理：构造文件块、编码文件、估算 token、准备发送 */
    private val chatManager = ChatManager(this, attachmentHandler)

    /** 当前加载的 .litertlm 模型是否支持图像输入（读模型自带 Capabilities，失败按不支持处理）。 */
    /** true = el modelo cargado soporta entrada visual (delegado a VisionManager) */
    var visionOk: Boolean
        get() = visionManager?.visionOk ?: false
        set(v) { visionManager?.setVisionOk(v) }

    /** Ruta al mmproj seleccionado (delegado a VisionManager) */
    var mmprojPath: String?
        get() = visionManager?.mmprojPath
        set(v) { visionManager?.mmprojPath = v }
    /** 「借打标看图」时工具结果用哪个角色候选回传（0=首选，试错后记住可用的那个） */
    private var tagReturnIdx = 0
    /** 输入栏「＋」附件按钮；待发送图片的预览条与缩略图 */
    private lateinit var attachButton: TextView
    private lateinit var attachPreviewStrip: HorizontalScrollView
    private lateinit var attachPreviewBox: LinearLayout

    /** 待发送的文本文件（已解析出文字），最多 MAX_FILES 个 */
    // private val pendingFiles = ArrayList<PendingFile>()

    /** 用户选的文件：文件名 + 原始字节数 + 解析出的正文（可能已截断）+ 结构说明（如「12 页」） */
    // private class PendingFile(
    //     val name: String,
    //     val bytes: Long,
    //     var text: String,
    //     var truncated: Boolean,
    //     val detail: String? = null,
    // )

    /** 文件读取结果：成功给 file，失败给 error（可直接显示的文案） */
    // private class FileRead(val file: PendingFile?, val error: String?)
    /** 拍照时预创建的 MediaStore URI */
    private var pendingCameraUri: Uri? = null
    /** 安卓 9 及以下等拿到存储权限后再执行的动作（仅这些机型用得到） */
    private var pendingStorageAction: (() -> Unit)? = null

    /** 当前处于绘图模式：对话页的输入会被当作正面提示词去生成图片 */
    /** 语言模型可用的绘图命令说明（仅当绘图模型与语言模型同时就绪时注入）。 */
    private val drawToolPrompt = CommandScanner.DRAW_TOOL_PROMPT

    /** 语言模型与绘图模型同时就绪 → 语言模型可以用 <draw> 命令调绘图模型。 */
    private val canDrawFromChat: Boolean
        get() = drawPage?.isReady() == true && (engine != null || llamaModel != null)

    /** 从回答里取出 <draw>…</draw> 命令：返回提示词与命中位置（不改内容，由调用方替换） */
    private fun scanDrawCommand(answer: String, thought: String): DrawHit? =
            commandScanner.scanDrawCommand(answer, thought, canDrawFromChat)

        /** 语言模型看不见图、但打标模型已加载 → 可以用 <tag> 命令让系统去识别图片。 */
        private val canTagFromChat: Boolean
            get() = drawPage?.taggerLoaded() == true && (engine != null || llamaModel != null) && !visionOk

        /** 这一轮带图、模型自己看不见、且还没识别过 → 本轮就把打标命令说明给模型 */
        private fun canTagTurn(t: QaTurn?): Boolean =
            canTagFromChat && t?.userImage != null

        /** 看不见图的模型：告诉它可以用 <tag> 命令让打标模型去看用户发的图。 */
        private val tagToolPrompt = """【图片工具】你自己看不到图片内容，但可以调用本机的打标模型，把用户刚发来的图片转成英文标签。
            用户消息里说明了「带了图片」时就是真的有图：此时必须调用下面的图片工具，
            绝对不要回复「请提供图片」或「我看不到图片」之类的推辞。
            需要知道图里有什么时：不要向用户解释这个工具，不要复述这条规则，不要猜图。
            这一轮你的输出里只写下面这一行命令，不要有任何解释、计划或别的话（参数可省；threshold 是阈值 0.05~0.99，topk 是最多几个标签 1~80）：
            <tag threshold=\"0.35\" topk=\"40\"></tag>
            命令写在正文的最后一行，不要写进思考过程。系统随后会把识别结果发给你，你再根据这些标签回答用户的问题.
        """.trimIndent()

        /** 看不见图的模型：本轮带图时给它的提示（先告知「带了图」，再给工具命令格式） */
        private fun tagHint(t: QaTurn?): String {
            val n = ChatSendLogic.countImages(t?.userImage)
            return getString(R.string.s_329, n) + "\n" + tagToolPrompt
        }

        /** 不做工具调用时，把回答里可能残留的 <tag …></tag> 命令抹掉（别把命令原文当正文显示给用户） */

        /** 从回答里取出 <tag …></tag> 命令：返回参数与命中位置（不改内容，由调用方替换） */
        private fun scanTagCommand(answer: String, thought: String): TagHit? =
            commandScanner.scanTagCommand(answer, thought, canTagFromChat)

    /** 把这一轮用户发的图交给打标模型识别：返回标签文本（多张图各一行），失败抛异常 */
    private fun runTaggerOnImages(names: List<String>, req: TagReq): String {
        val rgbOrder = drawPage?.taggerRgbOrder() == true
        val sb = StringBuilder()
        for ((i, n) in names.withIndex()) {
            val bmp = readChatImage(n) ?: continue
            val tags = TaggerEngine.run(bmp, req.threshold, req.topK, rgbOrder = rgbOrder)
            if (tags.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n")
            if (names.size > 1) sb.append("#${i + 1} ")
            sb.append(tags.joinToString(", ") { it.first })
        }
        return sb.toString()
    }



    /**
     * 跑一轮生成（gguf / litertlm 两条路径），把输出流式渲染进 ai。
     * displayPrefix、thoughtPrefix 是前面几轮已经落在气泡里的内容，这样工具调用可以
     * 在同一个气泡里接着往下写，而不是把整轮重来一遍。
     */
    private suspend fun runRound(
        turn: QaTurn,
        ai: AiArea,
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

    /** 对话页当前是否「出图模式」。
     *  规则：语言模型在场时（包括两种模型同时加载），对话页一律走聊天；
     *  只有「绘图模型已就绪、且没有加载语言模型」时，对话页输入才当成绘图提示词。 */
    private val drawMode: Boolean
        get() = drawPage?.isReady() == true && engine == null && llamaModel == null

    /** .gguf 架构探测结果缓存，避免重复读头部 */
    private val ggufKindCache = HashMap<String, GgufProbe.Kind>()
    private var genJob: Job? = null
    /** Ultimo resultado del benchmark (para compartir anonimamente). */
    private data class BenchStats(
        val model: String, val backend: String, val device: String, val soc: String,
        val tpsAvg: Double, val tpsMin: Double, val tpsMax: Double,
        val latMin: Long, val latMax: Long, val tokens: Int,
    )
    private var lastBench: BenchStats? = null
    /** Footnote '≈X tok/s' de la última generación (solo render en vivo). */
    private var lastTpsFoot: String? = null

    // QaTurn is imported from ChatState.kt

    private val sessions = mutableListOf<ChatSession>()
    private lateinit var current: ChatSession

    /** 当前会话创建时的思考开关状态；与复选框不一致时需重建会话。 */
    private var convThinking: Boolean? = null

    /** 中断后 LiteRT 会话可能已不可用：标记下一条消息发送前静默重建。 */
    private var convNeedsRebuild = false

    /** 创建 LiteRT 会话时是否注入了 <draw> 绘图命令提示。
     *  加载/卸载绘图模型会改变这个能力，而 LiteRT 的 system 提示只在建会话时写入，
     *  所以能力变化后必须重建会话，否则模型根本不知道自己能画图。 */
    private var convDrawCapable = false

    /** GGUF（llama.cpp）后端实例；非空表示当前加载的是 .gguf 模型。 */
    private var llamaModel: LlamaModel? = null
    private var ggufIterator: LlamaIterator? = null

    /** 用户点了「停止」。native 中断后往往抛异常（如 llama.cpp 的 task not found），
     *  用它把「主动中断」和「真的出错」分开，避免已输出的内容被错误信息覆盖。 */
    @Volatile
    private var stopRequested = false

    private lateinit var markwonFull: Markwon
    private lateinit var markwonStream: Markwon

    // ---- views ----
    private lateinit var statusTv: TextView
    private lateinit var statusDot: View
    private lateinit var tabChat: View
    private lateinit var tabModels: View

    /** Página de modelos extraída: dueña de sus vistas (MainActivity las delega). */
    private lateinit var modelsPage: ModelsPage

    // 模型页：顶部页签 + 左右翻页
    private val modelsPager: ViewPager get() = modelsPage.modelsPager
    private val modelsTabChat: TextView get() = modelsPage.modelsTabChat
    private val modelsTabDraw: TextView get() = modelsPage.modelsTabDraw
    private val modelsTabTag: TextView get() = modelsPage.modelsTabTag
    private lateinit var tabSettings: View

    // ---- API 设置区块：对话 / 绘图各一套，由 buildApiBlock() 生成，结构完全一致 ----
    private val chatApiBlock: ApiBlockView? get() = modelsPage.chatApiBlock
    private val drawApiBlock: ApiBlockView? get() = modelsPage.drawApiBlock
    /** 两边各有一行「xx模型：xxx」，插在 API 区块的地址下面 */
    private val chatApiModelTv: TextView get() = modelsPage.chatApiModelTv
    // ---- 「对话模型」页内再分两个子页：模型 / API ----
    private val chatSubTabModel: TextView get() = modelsPage.chatSubTabModel
    private val chatSubTabApi: TextView get() = modelsPage.chatSubTabApi
    private val chatModelPane: View get() = modelsPage.chatModelPane
    private val chatApiPane: View get() = modelsPage.chatApiPane
    // ---- 「绘图模型」页内再分两个子页：模型 / API ----
    private val drawSubTabModel: TextView get() = modelsPage.drawSubTabModel
    private val drawSubTabApi: TextView get() = modelsPage.drawSubTabApi
    private val drawModelPane: View get() = modelsPage.drawModelPane
    private val drawApiPane: View get() = modelsPage.drawApiPane
    /** 「绘图模型：xxx」那一行（绘图侧独有，插在 API 区块的地址下面） */
    private val drawApiModelTv: TextView get() = modelsPage.drawApiModelTv
    private lateinit var tabDraw: View
    private lateinit var inputBar: View

    /** 绘图页（文生图/图生图），懒加载 */
    private var drawPage: DrawPage? = null
    private lateinit var appBarMenu: View
    private lateinit var appBarPlus: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var drawerBody: LinearLayout
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private val savedContainer: LinearLayout get() = modelsPage.savedContainer
    private val modelInfoText: TextView get() = modelsPage.modelInfoText
    private lateinit var tabDownload: View

    /** 模型页里的绘图模型状态文本（模型统一在模型页加载） */
    private val drawModelStatus: TextView? get() = modelsPage.drawModelStatus
    /** 已复制的绘图模型列表容器 */
    private val drawSavedContainer: LinearLayout? get() = modelsPage.drawSavedContainer
    /** 绘图模型的「加载/卸载」二合一按钮 */
    private val drawToggleBtn: TextView? get() = modelsPage.drawToggleBtn
    /** 绘图模型的「导入」按钮（复制期间禁用） */
    private val drawImportBtn: TextView? get() = modelsPage.drawImportBtn
    /** 绘图模型导入进度条（本地复制时显示） */
    private val drawProgress: ProgressBar? get() = modelsPage.drawProgress
    /** 打标（Tagger）状态文本（模型页） */
    private val taggerStatusTv: TextView? get() = modelsPage.taggerStatusTv
    /** 打标模型 / 标签表导入按钮 */
    private val taggerOnnxBtn: TextView? get() = modelsPage.taggerOnnxBtn
    private val taggerCsvBtn: TextView? get() = modelsPage.taggerCsvBtn
    /** 已导入的打标模型 / 标签表列表容器 */
    private val taggerBox: LinearLayout? get() = modelsPage.taggerBox
    /** 打标模型 加载/卸载 按钮 */
    private val taggerLoadBtn: TextView? get() = modelsPage.taggerLoadBtn
    /** 当前选定的绘图主模型（绝对路径） */
    /** 当前选用的模型集 id（多文件模型 = 一组文件） */
    private var drawSetId: String? = null
    /** 正在等文件选择结果的槽位（null = 主模型文件） */
    private var pendingSlotKey: String? = null
    private val drawSlotsContainer: LinearLayout? get() = modelsPage.drawSlotsContainer
    /** 槽位面板的折叠标题行 */
    private var drawSlotsHeader: TextView? = null
    /** 槽位面板是否展开（默认收起，省地方） */
    private var drawSlotsExpanded = false
    /** LoRA 状态文本（模型页） */
    private val loraStatusTv: TextView? get() = modelsPage.loraStatusTv
    /** 已安装 LoRA 列表容器（模型页） */
    private val loraBox: LinearLayout? get() = modelsPage.loraBox
    /** 上次启动时发现「加载绘图模型」中途崩了（native 崩溃，Java 层捕不到） */
    private var pendingDrawCrash = false
    /** 上次崩溃时已执行的阶段（来自 draw/.loadstage） */
    private var pendingDrawStage: String? = null
    private val backendSpinner: Spinner get() = modelsPage.backendSpinner
    private val loadButton: TextView get() = modelsPage.loadButton
    private lateinit var thinkCheck: CheckBox
    private val progressBar: ProgressBar get() = modelsPage.progressBar
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputEdit: EditText

    /** Pestaña de descargas (lista del catálogo + progreso). */
    private var downloadPage: DownloadPage? = null
    private lateinit var sendButton: TextView
    private lateinit var jumpButton: TextView

    // ---- scroll state (delegado a ChatScrollManager) ----
    private var chatScrollManager: ChatScrollManager? = null

    // ---- state ----
    private var currentTab = 0
    private var drawerOpen = false
    private val drawerWidthDp = 280

    override fun onCreate(savedInstanceState: Bundle?) {
        // 最早挂上崩溃钩子：连主题解析 / 建界面阶段的异常也能落盘（崩在 buildUi 里否则没日志）
        crashHandler.install()
        // 主题必须在 setContentView 之前定好（系统栏/窗口底色来自 style，界面颜色来自 pal）
        themeManager.loadThemeMode(chatPrefs())
        themeManager.applyThemeInPlace()
        setTheme(if (themeManager.pal.isDark) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        markwonFull = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        // 流式渲染器故意不装表格插件：表格是两遍异步布局（先测列宽再重排），
        // 边生成边重渲染会让气泡高度反复跳变（上下抽搯）。表格放到生成结束后一次渲染。
        markwonStream = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        sessionManager.load()
        // Convert SessionManager's top-level session types to MainActivity's nested types
        sessions.clear()
        sessions.addAll(sessionManager.allSessions().map { ms ->
            ChatSession(ms.id, ms.title).apply {
                ms.turns.forEach { mt ->
                    turns.add(QaTurn(
                        user = mt.user,
                        answer = mt.answer,
                        thought = mt.thought,
                        image = mt.image,
                        userImage = mt.userImage,
                        userFile = mt.userFile,
                    ).apply {
                        fileText = mt.fileText
                        fileStore = mt.fileStore
                    })
                }
            }
        })
        current = sessionManager.currentSession()?.let { c ->
            sessions.find { it.id == c.id } ?: run {
                val s = ChatSession(c.id, c.title)
                sessions.add(s)
                s
            }
        } ?: ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        buildUi()
    }

    /**
     * 原地换主题：重算配色 → 改系统栏颜色 → 用新配色把界面重建一遍。
     *
     * 不走 recreate()：Activity 重建会把 chat 引擎、绘图 native 上下文、ONNX 打标会话
     * 一起丢掉（用户感受就是"设置一动就重启"）。这里只重建 View，这些对象都留着。
     */
    private fun applyThemeInPlace() {
        themeManager.applyThemeInPlace()
            // 记住当前页：buildUi() 末尾会把界面切回来
            chatPrefs().edit().putInt("restoreTab", currentTab).apply()
            buildUi()
        }

        /** 状态栏 / 导航栏配色（跟随主题） */
    private fun applySystemBarColors() {
        window.statusBarColor = themeManager.pal.statusBar
        window.navigationBarColor = themeManager.pal.navBar
                // 导航栏图标：浅色底要深色图标，深色底反之
                var flags = window.decorView.systemUiVisibility
                flags = if (themeManager.pal.isDark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                window.decorView.systemUiVisibility = flags
            }

        /** manifest 里声明了 uiMode，系统切深浅色不会自动重建，这里手动跟上。 */
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            if (themeManager.onConfigurationChanged(newConfig)) {
                applyThemeInPlace()
            }
        }

    /** 崩溃自捕获：Java/ART 层异常（含 UnsatisfiedLinkError）写进文件，下次启动可见。 */
    private fun installCrashHandler() {
        crashHandler.install()
    }

    /** 读并清空崩溃日志（供模型页展示） */
    private fun takeCrashLog(): String? {
        return crashHandler.takeCrashLog()
    }

    /** 将 MainActivity 的 session 状态同步到 SessionManager */
    private fun syncSessionToManager() {
        val convertedSessions = sessions.map { s ->
            com.yumenode.ai.ChatSession(s.id, s.title).apply {
                s.turns.forEach { t ->
                    turns.add(com.yumenode.ai.QaTurn(
                        user = t.user,
                        answer = t.answer,
                        thought = t.thought,
                        image = t.image,
                        userImage = t.userImage,
                        userFile = t.userFile,
                    ).apply {
                        fileText = t.fileText
                        fileStore = t.fileStore
                    })
                }
            }
        }
        sessionManager.syncFrom(convertedSessions, convertedSessions.find { it.id == current?.id })
    }

    override fun onPause() {
        super.onPause()
        syncSessionToManager()
        sessionManager.save()
        drawPage?.persistAll()   // 绘图参数兜底落盘
    }

    override fun onDestroy() {
        super.onDestroy()
        syncSessionToManager()
        try { sessionManager.save() } catch (_: Throwable) {}
        scope.cancel()
        // 绘图引擎：只有「绘图 API 在跑」时才把句柄留给服务继续出图，否则正常释放（别白占内存）。
        // 顺序不能反——先断开 DrawCore 的引用，再真正 free。
        if (!ApiService.isRunning(ApiServer.Role.DRAW)) {
            DrawCore.release()
            try { drawPage?.release() } catch (_: Throwable) {}
        }
        if (!ApiService.isRunning(ApiServer.Role.CHAT)) {
            // 对话 API 没开 → 按老规矩把模型放掉
            try { engine?.close() } catch (_: Throwable) {}
            try { llamaModel?.close() } catch (_: Throwable) {}
            ChatCore.release()
        }
        // 对话 API 还开着：模型留给服务继续推理（关掉它会让 API 立刻不可用），
        // 这里只断开界面自己的引用
        engine = null
        llamaModel = null
        conversation = null
    }

    // ================= UI construction =================

    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            fitsSystemWindows = true
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }

        main.addView(buildAppBar(), matchWrap())

        // 四页内容：对话 / 模型 / 绘图 / 关于（底部菜单切换）
        tabChat = buildChatPage()
        // 注意顺序：DrawPage 必须先建好并赋给 drawPage，再建「模型」页。
        // 「模型」页构建时就地读取 drawPage 的状态（LoRA 列表 / 已复制绘图模型清单），
        // 顺序反了会拿到 null，每次启动都显示成「未安装」。
        // 复用已存在的 DrawPage：换主题时重建的是视图，native 上下文 / 已加载的模型
        // 都留在同一个实例里（否则换一次主题就要重新加载绘图模型）。
        val dpg = drawPage ?: DrawPage(this, scope, pal)
        dpg.setPalette(pal)
        drawPage = dpg
        modelsPage = ModelsPage(createModelsPageHost())
        tabModels = modelsPage.build()
        tabSettings = buildSettingsPage()
        downloadPage = DownloadPage(
            this, { ui.card() }, { ui.pageTitle(it) }, { ui.hintText(it) },
            onModelsChanged = { refreshSavedModels() },
            pal = pal,
        )
        tabDownload = downloadPage!!.build()
        // La pestaña de descargas se construye en DownloadPage
        // 绘图模型就绪 → 进入绘图模式（对话页输入即正面提示词）
        dpg.onPipelineReady = {
            // 回调可能来自 IO 线程，UI 操作统一回主线程
            runOnUiThread {
                dpg.llmLoaded = false
                updateThinkEnabled()
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
                refreshDrawApiUi()   // 绘图接口那边要跟着显示「绘图模型：xxx」
            }
        }
        dpg.onSaveImage = { bmp, name -> saveImageToGallery(bmp, name) }
        dpg.onStatus = { text, isError ->
            runOnUiThread { setStatus(text, if (isError) C_ERR else C_WARN) }
        }
        // 注意：不在启动时自动加载绘图模型——native 加载失败会直接崩掉启动流程。
        // 改为在「模型」页手动点「加载绘图模型」（见下方按钮）。
        // 绘图页内部用 ViewPager 做左右翻页，外层不能再套 ScrollView（横竖滑动会打架）
        tabDraw = runCatching { dpg.build() }.getOrElse { e ->
            // 兜底：绘图页构建失败也不能让整个 App 挂掉，把原因显示出来便于定位
            ScrollView(this).apply {
                setBackgroundColor(C_BG)
                addView(TextView(this@MainActivity).apply {
                    text = "绘图页初始化失败：\n" + android.util.Log.getStackTraceString(e)
                    textSize = 12f
                    setTextColor(C_ERR_TEXT)
                    setPadding(36, 36, 36, 36)
                    setTextIsSelectable(true)
                })
            }
        }
        val contentFrame = FrameLayout(this)
        contentFrame.addView(tabChat, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabModels, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabDraw, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabDownload, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        main.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        inputBar = buildInputBar()
        main.addView(inputBar, matchWrap())
        main.addView(buildBottomNav(), matchWrap())

        rootFrame.addView(main, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildDrawer(rootFrame)
        setContentView(rootFrame)
        applySystemBarColors()

        refreshSavedModels()
        restoreSession()
        switchTab(0)
        // 主题切换导致的重建：回到重建前那一页（一次性，正常启动不受影响）
        val restoreTab = chatPrefs().getInt("restoreTab", -1)
        if (restoreTab in 0..3) {
            chatPrefs().edit().remove("restoreTab").apply()
            switchTab(restoreTab)
        }
    }

    /** 对话页：消息列表 + 回到底部按钮 + 智能跟随滚动。 */
    private fun buildChatPage(): View {
        val chatWrap = FrameLayout(this)
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(chatContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // 用户上滑阅读时暂停自动跟随；此按钮一键回到底部
        jumpButton = TextView(this).apply {
            text = "↓"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 24)
            elevation = dp(4).toFloat()
            visibility = View.GONE
            isClickable = true
        }
        chatWrap.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chatWrap.addView(jumpButton, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(16), dp(16))
        })

        // 滚动管理委托给 ChatScrollManager（auto-follow / jump-to-bottom 按钮 / foco del input）
        chatScrollManager = ChatScrollManager(this, scrollView, jumpButton) { inputEdit }
        return chatWrap
    }

    private fun buildAppBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(3).toFloat()
        }

        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appBarMenu = ui.iconButton("☰") { openDrawer() }
        r1.addView(appBarMenu, wrapWrap())

        // Ícono de la app en el header, justo al lado del nombre
        val appIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        r1.addView(appIcon, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(8)
        })

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "Yumenode AI"
            textSize = 17f
            setTextColor(C_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        statusDot = View(this).apply { background = rounded(C_PRIMARY, 99) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
        statusTv = TextView(this).apply {
            text = getString(R.string.s_116)
            textSize = 11.5f
            setTextColor(C_SUBTEXT)
        }
        statusRow.addView(statusTv, wrapWrap())
        titleBox.addView(statusRow, matchWrap())

        r1.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        appBarPlus = ui.iconButton("＋") { newSession() }
        r1.addView(appBarPlus, wrapWrap())
        bar.addView(r1, matchWrap())
        return bar
    }

    /** 模型页：选择/加载模型 + 后端选择。 */

    /** 模型页：单个面板包一层可纵向滚动的 ScrollView */
    private fun modelsPane(inner: View) = ModelsHelpers.modelsPane(this, inner)

    /** "对话模型"子页签样式（比顶部页签再小一号，同绘图页的 结果/过程） */
    private fun modelsSubTab(t: String) = ModelsHelpers.modelsSubTab(this, t)

    /** 切换「对话模型」页内的子页：0=模型 1=API */
    private fun switchChatSubPage(p: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.switchSubPage(
            pane0 = chatModelPane,
            pane1 = chatApiPane,
            tab0 = chatSubTabModel,
            tab1 = chatSubTabApi,
            selected = p,
            pal = pal,
        )
        if (p == 1) refreshApiUi()
    }

    /** 切换「绘图模型」页内的子页：0=模型 1=API */
    private fun switchDrawSubPage(p: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.switchSubPage(
            pane0 = drawModelPane,
            pane1 = drawApiPane,
            tab0 = drawSubTabModel,
            tab1 = drawSubTabApi,
            selected = p,
            pal = pal,
        )
        if (p == 1) refreshDrawApiUi()
    }

    /** 模型页顶部页签样式（与绘图页一致） */
    private fun tabItem(t: String) = ModelsHelpers.tabItem(this, t)

    // ==================== API 服务（对话 / 绘图两套，结构完全一致） ====================

    /** 委托给 [ApiBlockBuilder]：真正建控件的活儿都在那边，
     * 这里只负责把 Activity 上的动作接过去。
     */
    private fun buildApiBlock(
        role: ApiServer.Role,
        extraAfterAddress: List<View> = emptyList(),
        extraNotes: List<View> = emptyList(),
    ): ApiBlockView = ApiBlockBuilder(
        activity = this,
        ui = ui,
        pal = pal,
        chatPrefs = chatPrefs(),
        onCopyAddress = { copyApiAddress(it) },
        onNewKey = { newApiKey(it) },
        onClearKey = { clearApiKey(it) },
        onToggle = { toggleApi(it) },
        onRefreshSurfaces = { refreshApiSurfaces() },
    ).build(role, extraAfterAddress, extraNotes)

    private fun apiPort(role: ApiServer.Role): Int =
        chatPrefs().getInt(ApiService.keyPort(role), ApiService.defaultPort(role))
            .coerceIn(1024, 65535)

    /** 刷新某个 API 区块：状态 / 地址 / key / 开关 / 统计 */
    private fun refreshApiBlock(role: ApiServer.Role, b: ApiBlockView?) {
        if (b == null) return
        val running = ApiService.isRunning(role)
        b.statusTv.text =
            if (running) getString(R.string.s_406, ApiService.addressOf(role) ?: "")
            else getString(R.string.s_405)
        b.statusTv.setTextColor(if (running) C_OK else C_SUBTEXT)
        b.addrTv.visibility = if (running) View.VISIBLE else View.GONE
        b.addrTv.text = if (running) getString(R.string.s_417) else ""
        val key = chatPrefs().getString(ApiService.keyToken(role), null)
        b.keyTv.text = getString(R.string.s_409,
            if (key.isNullOrBlank()) getString(R.string.s_410) else key)
        b.toggleBtn.text = getString(if (running) R.string.s_408 else R.string.s_407)
        b.toggleBtn.background = rounded(
            if (running) C_SOFT_BTN2 else C_PRIMARY, 12,
            strokeDp = if (running) 1 else 0, strokeColor = C_BORDER)
        b.toggleBtn.setTextColor(if (running) C_TEXT else Color.WHITE)
        val (n, last) = ApiService.statsSnapshot(role)
        b.statsTv.text = if (!running) "" else
            getString(R.string.s_413, n) + (last?.let { "\n" + getString(R.string.s_414, it) } ?: "")
    }

    private fun refreshApiUi() {
        // 「对话模型：xxx」——告诉用户这个接口到底在服务哪个模型
        if (::modelsPage.isInitialized) {
            val name = ChatCore.modelName
            chatApiModelTv.text =
                if (name != null) getString(R.string.s_436, name) else getString(R.string.s_437)
            chatApiModelTv.setTextColor(if (name != null) C_TEXT else C_SUBTEXT)
        }
        refreshApiBlock(ApiServer.Role.CHAT, chatApiBlock)
    }

    /** 绘图侧除了通用内容，还多一行「绘图模型：xxx」——那是句柄层面的状态，与服务无关 */
    private fun refreshDrawApiUi() {
        if (::modelsPage.isInitialized) {
            val name = DrawCore.modelName
            drawApiModelTv.text =
                if (name != null) getString(R.string.s_426, name) else getString(R.string.s_427)
            drawApiModelTv.setTextColor(if (name != null) C_TEXT else C_SUBTEXT)
        }
        refreshApiBlock(ApiServer.Role.DRAW, drawApiBlock)
    }

    /** 两个区块一起刷新：两者共用一个前台服务，一边变化常会牵动另一边 */
    private fun refreshApiSurfaces() {
        refreshApiUi()
        refreshDrawApiUi()
    }

    /** 复制某个服务的地址（点一下地址那行即可） */
    private fun copyApiAddress(role: ApiServer.Role) {
        val addr = ApiService.addressOf(role) ?: return
        runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ponko-api", addr))
            toast(getString(R.string.s_416))
        }
    }

    /** 生成一个新的 API key（形如 sk-ponko-xxxx） */
    private fun newApiKey(role: ApiServer.Role) {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val key = "sk-ponko-" + (1..32).map { chars.random() }.joinToString("")
        chatPrefs().edit().putString(ApiService.keyToken(role), key).apply()
        afterApiConfigChanged(role)
        toast(getString(R.string.s_418))
    }

    private fun clearApiKey(role: ApiServer.Role) {
        chatPrefs().edit().remove(ApiService.keyToken(role)).apply()
        afterApiConfigChanged(role)
        toast(getString(R.string.s_418))
    }

    /** 端口 / key 改完之后：该服务在跑就重启它，让新配置立刻生效（不用用户自己记得重启） */
    private fun afterApiConfigChanged(role: ApiServer.Role) {
        refreshApiSurfaces()
        if (ApiService.isRunning(role)) {
            ApiService.sync(this)
            android.os.Handler(mainLooper).postDelayed({ refreshApiSurfaces() }, 500)
        }
    }

    /**
     * 开关某一类 API。
     *
     * **不检查有没有加载模型** —— 服务只是把 HTTP 端口架起来，加载模型是各接口自己的事：
     * 没加载时对应接口返回 503 并说明原因。早期版本要求「先加载对话模型」，
     * 那是把可选前置条件错当成了拦路条件，逼着想开绘图接口的人去加一个无关的模型。
     */
    private fun toggleApi(role: ApiServer.Role) {
        val wasOn = ApiService.isRunning(role)
        chatPrefs().edit().putBoolean(ApiService.keyEnabled(role), !wasOn).apply()
        if (!wasOn && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Android 13+ 常驻通知需要运行时权限；不给也能跑，只是看不到通知
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ApiService.sync(this)
        android.os.Handler(mainLooper).postDelayed({ refreshApiSurfaces() }, 700)
    }

    /** 切换模型页的「对话模型 / 绘图模型 / 打标模型」 */
    private fun switchModelsTab(index: Int) {
        if (::modelsPage.isInitialized) modelsPager.setCurrentItem(index, true)
        highlightModelsTabs(index)
        when (index) {
            0 -> { refreshSavedModels(); visionManager?.refreshMmprojUi(); refreshApiUi() }
            1 -> { refreshDrawModels(); refreshLoraUi(); refreshDrawApiUi() }
            2 -> refreshTaggerUi()
        }
    }

    /** 同步模型页页签高亮；手势翻页时也由它更新 */
    private fun highlightModelsTabs(selected: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.highlightTabs(
            tabs = listOf(modelsTabChat, modelsTabDraw, modelsTabTag),
            selected = selected,
            pal = pal,
        )
    }

    /** 模型页的翻页适配器：直接复用已建好的三个面板，不重建 */
    private class ModelsPaneAdapter(private val panes: List<View>) : PagerAdapter() {
        override fun getCount() = panes.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val v = panes[position]
            (v.parent as? ViewGroup)?.removeView(v)
            container.addView(v)
            return v
        }

        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            (obj as? View)?.let { container.removeView(it) }
        }
    }

    /** 关于页：角色原图 + 说明。 */
    /** Pestaña de ajustes: vive en SettingsPage.kt */
    private fun buildSettingsPage(): View = SettingsPage(this).build()

    /** Crea el host exhibido a ModelsPage (ViewController/Page pattern) */
    private fun createModelsPageHost(): ModelsHost {
        return object : ModelsHost {
            override val activity: Activity get() = this@MainActivity
            override val filesDir: File get() = this@MainActivity.filesDir
            override val scope: CoroutineScope get() = this@MainActivity.scope
            override val ui: UiWidgets get() = this@MainActivity.ui
            override val palette: YumenodeTheme get() = this@MainActivity.pal
            override val chatParams: ChatParams get() = this@MainActivity.chatParams
            override val pendingDrawCrash: Boolean get() = this@MainActivity.pendingDrawCrash
            override val pendingDrawCrashStage: String? get() = this@MainActivity.pendingDrawStage
            override fun setStatus(text: String, color: Int) { this@MainActivity.setStatus(text, color) }
            override fun takeCrashLog(): String? = this@MainActivity.takeCrashLog()
            override fun toast(msg: String) { this@MainActivity.toast(msg) }
            override fun onPickModel() = this@MainActivity.pickModelFile()
            override fun onToggleLoad() = this@MainActivity.toggleLoad()
            override fun onResetChatParams() = this@MainActivity.resetChatParams()
            override fun onSaveChatParams() = this@MainActivity.saveChatParams()
            override fun onShowDrawTypeMenu() = this@MainActivity.showDrawTypeMenu()
            override fun onToggleDrawSlots() = this@MainActivity.toggleDrawSlots()
            override fun onDrawToggleClickRequested() = this@MainActivity.onDrawToggleClick()
            override fun onPickLoraSource() = this@MainActivity.pickLoraSource()
            override fun onPickLoraFile() = this@MainActivity.pickLoraFile()
            override fun onConfirmDeleteLora() = this@MainActivity.confirmDeleteLora()
            override fun onPickTaggerOnnx() = this@MainActivity.pickTaggerOnnx()
            override fun onPickTaggerCsv() = this@MainActivity.pickTaggerCsv()
            override fun onTaggerToggleClickRequested() = this@MainActivity.onTaggerToggleClick()
            override fun onSwitchChatSubPage(p: Int) = this@MainActivity.switchChatSubPage(p)
            override fun onSwitchDrawSubPage(p: Int) = this@MainActivity.switchDrawSubPage(p)
            override fun onSwitchModelsTab(p: Int) = this@MainActivity.switchModelsTab(p)
            override fun onRefreshApiUi() = this@MainActivity.refreshApiUi()
            override fun onRefreshDrawApiUi() = this@MainActivity.refreshDrawApiUi()
            override fun onRefreshLoraUi() = this@MainActivity.refreshLoraUi()
            override fun onRefreshDrawModels() = this@MainActivity.refreshDrawModels()
            override fun onRefreshTaggerUi() = this@MainActivity.refreshTaggerUi()
            override fun onRefreshSavedModels() = this@MainActivity.refreshSavedModels()
            override fun onLoadChatParamsIntoUi() = this@MainActivity.loadChatParamsIntoUi()
            override fun onBuildApiBlock(role: ApiServer.Role, extraAfterAddress: List<View>, extraNotes: List<View>): ApiBlockView = this@MainActivity.buildApiBlock(role, extraAfterAddress, extraNotes)
            override fun onSetDrawUseGpu(value: Boolean) { this@MainActivity.drawPage?.useGpu = value }
            override fun onPickMmprojFile() { this@MainActivity.visionManager?.pickMmprojFile() }
            override fun onRefreshMmprojUi() { this@MainActivity.visionManager?.refreshMmprojUi() }
            override fun onProgress(pct: Int?) {
                if (pct == null) {
                    this@MainActivity.progressBar.visibility = View.GONE
                } else {
                    this@MainActivity.progressBar.visibility = View.VISIBLE
                    this@MainActivity.progressBar.progress = pct
                }
            }
            override fun onVisionStatus(text: String, color: Int) { this@MainActivity.setStatus(text, color) }
            override fun onVisionError(msg: String) { this@MainActivity.toast(msg) }
            override fun onModelChanged() { if (this@MainActivity.llamaModel != null) this@MainActivity.toast(this@MainActivity.getString(R.string.s_290)) }
        }
    }

    // La pestaña de descargas vive en DownloadPage.kt


    /**
     * 设置页里的可折叠分区：返回可往里塞内容的容器。
     * 标题行（标题 + 箭头）点一下展开 / 收起。
     */
    /**
     * 可折叠分区。
     *
     * @param expanded 首次使用（还没有存过偏好）时的默认展开状态
     * @param prefKey  记住展开状态的偏好键；**null = 不持久化**（每次都回到 [expanded]）。
     *                 不传 key 的话用户合上分区、切走再回来就会弹回去——这正是「关于」页
     *                 之前的问题：状态只存在 `open` 这个内存变量里。
     */


    /** 关于页的可点击链接（点开系统浏览器） */


    // ---- 底部导航 ----

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_CARD)
            elevation = dp(6).toFloat()
        }
        val items = listOf(
            Triple("💬", getString(R.string.s_074), 0),
            Triple("🧠", getString(R.string.s_124), 1),
            Triple("🎨", getString(R.string.s_150), 2),
            Triple("⬇️", getString(R.string.s_454), 3),
            Triple("ℹ️", getString(R.string.s_048), 4),
        )
        for ((icon, label, idx) in items) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { switchTab(idx) }
            }
            val iconTv = TextView(this).apply { text = icon; textSize = 17f; gravity = Gravity.CENTER }
            val labelTv = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, 0)
            }
            item.addView(iconTv, matchWrap())
            item.addView(labelTv, matchWrap())
            navIcons += iconTv
            navLabels += labelTv
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return nav
    }

    private fun switchTab(index: Int) {
        currentTab = index
        tabChat.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabModels.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabDraw.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabDownload.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 4) View.VISIBLE else View.GONE
        // 切到「模型」页时刷新绘图模型 / LoRA 列表，避免导入后状态滞后
        if (index == 1) { refreshDrawModels(); refreshLoraUi(); refreshTaggerUi(); refreshApiUi(); refreshDrawApiUi() }
        // 切到下载页时刷新列表
        if (index == 3) { downloadPage?.refresh() }
        inputBar.visibility = if (index == 0) View.VISIBLE else View.GONE
        val isChat = index == 0
        appBarMenu.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        appBarPlus.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        for (i in navIcons.indices) {
            val c = if (i == currentTab) C_PRIMARY else C_SUBTEXT
            navIcons[i].setTextColor(c)
            navLabels[i].setTextColor(c)
        }
        if (index == 0) chatScrollManager?.scrollToBottom()
    }

    // ---- 左侧对话抽屉 ----

    private fun buildDrawer(rootFrame: FrameLayout) {
        drawerMask = View(this).apply {
            setBackgroundColor(C_SCRIM)
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { closeDrawer() }
        }
        rootFrame.addView(drawerMask, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        drawerPanel.addView(TextView(this).apply {
            text = getString(R.string.s_074)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(C_PRIMARY)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }, matchWrap())

        val sv = ScrollView(this)
        drawerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        sv.addView(drawerBody, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        drawerPanel.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        drawerPanel.addView(TextView(this).apply {
            text = "Yumenode AI"
            textSize = 11f
            setTextColor(C_SUBTEXT)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }, matchWrap())

        val w = dp(drawerWidthDp)
        rootFrame.addView(drawerPanel, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        drawerPanel.translationX = -w.toFloat()
    }

    private fun refreshDrawer() {
        drawerBody.removeAllViews()
        drawerBody.addView(ui.actionButton(getString(R.string.s_201)) { closeDrawer(); newSession() },
            matchWrap().apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

        for (s in sessions.sortedByDescending { it.id }) {
            val isCur = s === current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                isClickable = true
                if (isCur) setBackgroundColor(C_PRIMARY_SOFT)
            }
            row.addView(TextView(this).apply {
                text = s.title
                textSize = 14f
                setTextColor(if (isCur) C_PRIMARY else C_TEXT)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = getString(R.string.v_001, (s.turns.size))
                textSize = 11f
                setTextColor(C_SUBTEXT)
                setPadding(0, 0, dp(6), 0)
            }, wrapWrap())
            row.addView(TextView(this).apply {
                text = "🗑"
                textSize = 14f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                setOnClickListener { confirmDeleteSession(s) }
            }, wrapWrap())
            row.setOnClickListener { closeDrawer(); switchTo(s) }
            drawerBody.addView(row, matchWrap())
            drawerBody.addView(View(this).apply { setBackgroundColor(C_DIVIDER_LINE) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(16), 0, 0, 0)
                })
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        refreshDrawer()
        drawerMask.visibility = View.VISIBLE
        drawerMask.alpha = 0f
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.translationX = -dp(drawerWidthDp).toFloat()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
        drawerMask.animate().alpha(1f).setDuration(220).start()
        drawerOpen = true
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerPanel.animate().translationX(-dp(drawerWidthDp).toFloat()).setDuration(180)
            .withEndAction { drawerPanel.visibility = View.GONE }.start()
        drawerMask.animate().alpha(0f).setDuration(180)
            .withEndAction { drawerMask.visibility = View.GONE }.start()
        drawerOpen = false
    }

    private fun buildInputBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            setPadding(dp(10), dp(4), dp(10), dp(8))
            elevation = dp(6).toFloat()
        }

        thinkCheck = CheckBox(this).apply {
            text = getString(R.string.s_105)
            textSize = 12.5f
            setTextColor(C_TEXT)
            isChecked = chatPrefs().getBoolean(KEY_CHAT_THINK, true)
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
            setOnCheckedChangeListener { _, v -> chatPrefs().edit().putBoolean(KEY_CHAT_THINK, v).apply() }
        }
        bar.addView(thinkCheck, matchWrap())

        // 待发送图片预览条（横向可滚动，每张一个小缩略图 + 右上角 ×）
        attachPreviewStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(6))
        }
        attachPreviewBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachPreviewStrip.addView(attachPreviewBox)
        bar.addView(attachPreviewStrip, matchWrap())

        // 预览条的重建逻辑在 AttachmentHandler（增删附件后自动刷新）
        attachmentHandler.attachViews(attachPreviewStrip, attachPreviewBox)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        inputEdit = EditText(this).apply {
            hint = getString(R.string.s_180)
            textSize = 15f
            setTextColor(C_TEXT)
            setHintTextColor(C_HINT)
            background = rounded(C_SOFT_BTN, 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        row.addView(inputEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        attachButton = TextView(this).apply {
            text = "＋"
            textSize = 20f
            setTextColor(C_PRIMARY)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY_SOFT, 22)
            isClickable = true
            setOnClickListener { onAttachClick() }
        }
        row.addView(attachButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })

        sendButton = TextView(this).apply {
            text = "↑"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 22)
            isClickable = true
            setOnClickListener { onSendOrStop() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        bar.addView(row, matchWrap())
        return bar
    }

    // ================= 聊天发图（.litertlm 多模态） =================

    /** 探测 .litertlm 模型是否支持图像输入；任何异常都按「不支持」处理。 */
    private fun probeVision(path: String): Boolean = try {
        ModelInfo.from(path).use { it.inputModalities().vision }
    } catch (_: Throwable) {
        false
    }

    private fun onAttachClick() {
        // 生成过程中也允许先把附件备好（发送仍会被 busy 拦住）
        // 注意：这里只管「模型加载了没」——文本文件任何模型都能读，不该被「必须多模态」挡住
        if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
        showAttachDrawer()
    }

    /** 「＋」抽屉：贴着输入栏向上展开，可选拍照 / 图库。 */
    private fun showAttachDrawer() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_CARD, 14)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // 外面再套一层留 8dp 余量：给阴影和缩放动画留空间，避免被弹窗边界裁掉
        val outer = FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(box, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        fun mkItem(icon: String, label: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = "$icon  $label"
                textSize = 14.5f
                setTextColor(C_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                setOnClickListener { onClick() }
            }

        val popup = PopupWindow(outer, dp(150) + dp(16), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            // 透明背景：真正的外观画在 box 上，这样手动缩放动画才不会被窗口背景挡住
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // 收起：先播完动画再 dismiss
        fun close(action: () -> Unit) {
            box.animate().alpha(0f).scaleY(0.75f)
                .setDuration(120)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    popup.dismiss()
                    action()
                }
                .start()
        }

        box.addView(mkItem("📷", getString(R.string.s_270)) { close { takePhoto() } }, matchWrap())
        box.addView(mkItem("🖼", getString(R.string.s_271)) { close { attachmentHandler.pickChatImage() } }, matchWrap())
        box.addView(mkItem("📄", getString(R.string.s_295)) { close { attachmentHandler.pickChatFile() } }, matchWrap())

        // 先手动量一次，在 show 之前就把动画初值设好（否则会闪一帧全尺寸）
        box.measure(
            View.MeasureSpec.makeMeasureSpec(dp(150), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        box.pivotY = box.measuredHeight.toFloat()
        box.alpha = 0f
        box.scaleY = 0.72f

        // 向上展开：弹窗底边贴在输入栏上沿
        val loc = IntArray(2)
        inputBar.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        popup.showAtLocation(
            inputBar, Gravity.BOTTOM or Gravity.END, dp(10) - dp(8), screenH - loc[1] + dp(6)
        )

        // 入场动画：从底部往上长出来 + 淡入（不依赖 PopupWindow 的系统窗口动画）
        box.animate().alpha(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 安卓 9 及以下写相册/拍照需要运行时 WRITE_EXTERNAL_STORAGE；安卓 10+ 走 MediaStore，免权限，直接执行。 */
    private fun withLegacyStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingStorageAction = action
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM_STORAGE) return
        val act = pendingStorageAction
        pendingStorageAction = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) act?.invoke()
        else toast(getString(R.string.s_294))
    }

    private fun takePhoto() = withLegacyStorage { doTakePhoto() }

    private fun doTakePhoto() {
        val uri = try {
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ponko_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
            )
        } catch (_: Throwable) {
            null
        }
        if (uri == null) { toast(getString(R.string.s_277)); return }
        pendingCameraUri = uri
        try {
            startActivityForResult(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                },
                REQ_TAKE_PHOTO
            )
        } catch (_: Throwable) {
            toast(getString(R.string.s_274))
        }
    }





    /** Bitmap → JPEG 字节（作为 Content.ImageBytes 送给 LiteRT-LM）。 */
    private fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** 用户发的图片也落盘（JPEG），随会话一起恢复。 */
    private fun writeChatJpeg(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (_: Throwable) {
        false
    }

    /** 把待发文件的原始内容编码成 JSON 存进轮次，供「重新生成」还原附件 */
    /** 安装/更新时间（用来确认装的是哪一版） */
    override fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    override fun installedAt(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(pi.lastUpdateTime))
    } catch (_: Throwable) {
        ""
    }

    /**
     * 估算 LiteRT 会话的输入 token：系统提示 + 历史 + 本轮要发的内容。
     * 历史里之前发过的文件正文也还留在引擎的上下文里，所以一并算上。
     */
    private fun estimateConversationTokens(s: ChatSession, extra: String, dropLast: Boolean): Float =
        chatManager.estimateConversationTokens(
            session = s, extra = extra, dropLast = dropLast,
            canDrawFromChat = canDrawFromChat, drawToolPrompt = drawToolPrompt
        )

    /** 判断是不是「上下文塞不下」这类错误（LiteRT Status Code 3 / llama.cpp 的 context 满） */
    private fun isContextOverflow(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("too long") || m.contains("maximum number of tokens") ||
            m.contains("out of context") ||
            (m.contains("context") && (m.contains("full") || m.contains("exceed")))
    }

    // ================= 对话模型参数（模型页可调） =================

    /** 通用 SharedPreferences（chat/UI/API 设置共用） */
    fun chatPrefs() = getSharedPreferences("ponko", MODE_PRIVATE)

    /** 模型页输入即存盘（存到当前模型名下）；空值/非法值回落到默认 */
    private fun saveChatParams() {
        if (chatParams.uiLoading) return
        fun i(ed: EditText?, def: Int) = ed?.text?.toString()?.trim()?.toIntOrNull() ?: def
        fun f(ed: EditText?, def: Float) = ed?.text?.toString()?.trim()?.toFloatOrNull() ?: def
        chatParams.save(
            ctx = i(chatCtxEdit, ChatParams.DEF_CHAT_CTX),
            maxOut = i(chatMaxOutEdit, ChatParams.DEF_CHAT_MAXOUT),
            temp = f(chatTempEdit, ChatParams.DEF_CHAT_TEMP),
            topK = i(chatTopKEdit, ChatParams.DEF_CHAT_TOPK),
            topP = f(chatTopPEdit, ChatParams.DEF_CHAT_TOPP),
            repPenalty = f(chatRepEdit, ChatParams.DEF_CHAT_REP),
            budget = i(chatBudgetEdit, ChatParams.DEF_CHAT_BUDGET),
            seed = i(chatSeedEdit, ChatParams.DEF_CHAT_SEED),
        )
    }

    /** 把当前模型的参数回填到模型页的输入框（建界面 / 切模型时调） */
    private fun loadChatParamsIntoUi() {
        chatParams.setUiLoading(true)
        chatCtxEdit?.setText(chatParams.ctx().toString())
        chatMaxOutEdit?.setText(chatParams.maxOut().toString())
        chatTempEdit?.setText(chatParams.temp().toString())
        chatTopKEdit?.setText(chatParams.topK().toString())
        chatTopPEdit?.setText(chatParams.topP().toString())
        chatRepEdit?.setText(chatParams.repPenalty().toString())
        chatBudgetEdit?.setText(chatParams.budget().toString())
        chatSeedEdit?.setText(chatParams.seed().toString())
        chatParams.setUiLoading(false)
        val n = chatParams.ownerLabel()
        chatParamsOwnerTv?.text =
            if (n == null) getString(R.string.s_319) else getString(R.string.s_318, n)
    }

    /** 把当前模型的参数恢复成内置默认值（上下文等要重新加载模型才生效） */
    private fun resetChatParams() {
        chatParams.setUiLoading(true)
        chatParams.resetDefaults()
        chatParams.setUiLoading(false)
        loadChatParamsIntoUi()
        toast(getString(R.string.s_317))
    }

    /** 参数格：小标题 + 数字输入 + 说明；keep 用来把输入框存进字段 */
    private fun numField(label: String, def: String, hint: String, keep: (EditText) -> Unit): LinearLayout =
        UiHelpers.numField(
            context = this,
            label = label,
            value = def,
            hint = hint,
            keep = keep,
            onChanged = { saveChatParams() },
            palette = { pal },
            ui = ui,
        )

    /** 一行放两格参数 */
    private fun twoCols(a: View, b: View): LinearLayout =
        UiHelpers.twoCols(context = this, first = a, second = b)

    // ================= small view factories =================

    // ================= status =================

    private fun setStatus(text: String, color: Int) {
        statusTv.text = text
        statusDot.background = rounded(color, 99)
    }

    // ================= model loading =================

    /** 对话模型：选一个 .litertlm / .gguf 文件 */
    private fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_MODEL)
    }

    /** gguf 多模态：选一个 mmproj（视觉投影）文件，.gguf */
    // pickMmprojFile / copyMmprojToPrivate / refreshMmprojUi / mmprojChip / confirmDeleteMmproj
    // → extraídos a VisionManager

    /** 「选择绘图模型文件」→ 先挑类型（单文件 / 多文件 × 家族），再选文件。
     *  单文件 = 一个 all-in-one .gguf；多文件 = 主模型 + 若干配套文件（按家族给槽位模板）。 */
    private fun showDrawTypeMenu() {
        val single = listOf("sd15", "sdxl", "sd3", "flux", "qwen", "video", "other")
        val multi = listOf("sd3", "flux", "qwen", "hidream", "video", "other")
        val labels = ArrayList<String>()
        val picks = ArrayList<kotlin.Pair<String, String>>()
        for (kind in listOf("single", "multi")) {
            val fams = if (kind == "single") single else multi
            val kindLabel = getString(if (kind == "single") R.string.s_345 else R.string.s_346)
            for (fam in fams) {
                labels.add(kindLabel + " · " + getString(DrawPage.familyLabelRes(fam)))
                picks.add(kotlin.Pair(kind, fam))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_336))
            .setItems(labels.toTypedArray()) { _, which ->
                val dpg = drawPage ?: return@setItems
                val pick = picks[which]
                dpg.pendingKind = pick.first
                dpg.pendingFamily = pick.second
                dpg.pendingNewSet = true
                pendingSlotKey = null
                pickDrawModelFile()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 给当前模型集的某个槽位挑一个文件 */
    private fun pickDrawSlot(key: String) {
        pendingSlotKey = key
        pickDrawModelFile()
    }

    /** 当前模型集的槽位列表（多文件模型在这里逐个补文件） */
    private fun refreshDrawSlots() {
        val box = drawSlotsContainer ?: return
        val hdr = drawSlotsHeader ?: return
        box.removeAllViews()
        val dpg = drawPage ?: return
        val set = dpg.activeSetOrSelected()
        if (set == null) {
            hdr.visibility = View.GONE
            box.visibility = View.GONE
            return
        }
        val spec = DrawPage.familySlots(set.kind, set.family)
        val filled = spec.count { set.files[it.first] != null }
        val miss = spec.count { it.second && set.files[it.first] == null }
        hdr.visibility = View.VISIBLE
        hdr.text = getString(R.string.s_371, filled, spec.size) + if (drawSlotsExpanded) "  \u25be" else "  \u25b8"
        hdr.setTextColor(if (miss > 0) C_WARN else C_PRIMARY)
        if (!drawSlotsExpanded) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(ui.hintText(getString(R.string.s_364)), matchWrap().apply { topMargin = dp(4) })
        for (item in spec) {
            val key = item.first
            val required = item.second
            val f = set.files[key]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = getString(DrawPage.slotLabelRes(key)) + if (required) " *" else ""
                textSize = 12f
                setTextColor(if (f != null) C_TEXT else C_SUBTEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
            row.addView(TextView(this).apply {
                text = f?.name ?: getString(R.string.s_360)
                textSize = 11.5f
                maxLines = 2
                setTextColor(if (f != null) C_TEXT else C_SUBTEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.smallButton(getString(R.string.s_361)) { pickDrawSlot(key) })
            if (f != null) {
                row.addView(ui.smallButton(getString(R.string.s_362)) {
                    dpg.clearSlot(key)
                    refreshDrawSlots()
                    drawModelStatus?.text = dpg.modelSummary()
                })
            }
            box.addView(row, matchWrap().apply { topMargin = dp(6) })
        }
    }

    /** 展开 / 收起模型槽位面板（expand = null 表示切换） */
    private fun toggleDrawSlots(expand: Boolean? = null) {
        drawSlotsExpanded = expand ?: !drawSlotsExpanded
        refreshDrawSlots()
    }

    /** 绘图模型：选一个文件（单文件 all-in-one，或多文件里的某个槽位） */
    private fun pickDrawModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_DRAW_MODEL)
    }

    /** 本地 LoRA：选一个 .safetensors 文件 */
    private fun pickLoraFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_LORA)
    }

    /** 打标模型：选一个 .onnx 文件 */
    private fun pickTaggerOnnx() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_TAGGER_ONNX)
    }

    /** 标签表：选一个 .csv 文件 */
    private fun pickTaggerCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_TAGGER_CSV)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (drawPage?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK) {
            data?.data?.let { copyModelToPrivate(it) }
        }
        if (requestCode == REQ_PICK_MMPROJ && resultCode == RESULT_OK) {
            data?.data?.let { visionManager?.copyMmprojToPrivate(it) }
        }
        if (requestCode == AttachmentHandler.REQ_PICK_CHAT_IMAGE && resultCode == RESULT_OK) {
            val uris = ArrayList<Uri>()
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data?.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) return
            scope.launch {
                var added = 0
                for (u in uris) {
                    if (attachmentHandler.getPendingImages().size >= AttachmentHandler.MAX_ATTACH) { toast(getString(R.string.s_279)); break }
                    val bmp = withContext(Dispatchers.IO) { attachmentHandler.decodeChatImage(u) } ?: continue
                    val jpeg = withContext(Dispatchers.IO) { bitmapToJpeg(bmp) }
                    attachmentHandler.addPendingImage(bmp, jpeg)
                    added++
                }
                if (added == 0 && attachmentHandler.getPendingImages().isEmpty()) toast(getString(R.string.s_275))
            }
        }
        if (requestCode == REQ_TAKE_PHOTO) {
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (resultCode != RESULT_OK || uri == null) return
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { attachmentHandler.decodeChatImage(uri) }
                if (bmp == null) { toast(getString(R.string.s_275)); return@launch }
                val jpeg = withContext(Dispatchers.IO) { bitmapToJpeg(bmp) }
                attachmentHandler.addPendingImage(bmp, jpeg)
            }
        }
        if (requestCode == AttachmentHandler.REQ_PICK_FILE && resultCode == RESULT_OK) {
            val uris = ArrayList<Uri>()
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            scope.launch {
                var err: String? = null
                for (u in uris) {
                    if (attachmentHandler.getPendingFiles().size >= AttachmentHandler.MAX_FILES) { toast(getString(R.string.s_297)); break }
                    val r = withContext(Dispatchers.IO) { attachmentHandler.readTextFile(u) }
                    val f = r.file
                    if (f == null) {
                        if (err == null) err = r.error
                        continue
                    }
                    attachmentHandler.addPendingFile(f)
                }
                if (err != null) toast(err)
            }
        }
        if (requestCode == REQ_PICK_DRAW_MODEL && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            val slot = pendingSlotKey
            pendingSlotKey = null
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importSetFile(
                    uri,
                    slot,
                    onStage = { stage -> drawModelStatus?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    drawSetId = dpg.activeSetId()
                    val set = dpg.activeSetOrSelected()
                    drawModelStatus?.text = dpg.modelSummary()
                    setStatus(getString(R.string.s_161), C_OK)
                    if (set != null && set.kind == "multi" && slot == null) {
                        toast(getString(R.string.s_370))
                        // 新导入的多文件主模型：自动把槽位面板展开，方便接着补配套文件
                        drawSlotsExpanded = true
                    } else {
                        toast(getString(R.string.s_091))
                    }
                } else {
                    drawModelStatus?.text = err
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshDrawModels()
            }
        }
        if (requestCode == REQ_PICK_LORA && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importLoraFile(
                    uri,
                    onStage = { stage -> loraStatusTv?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    setStatus(getString(R.string.s_220), C_OK)
                    toast(getString(R.string.s_220))
                } else {
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshLoraUi()
            }
        }
        if (requestCode == REQ_TAGGER_ONNX && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importTaggerModel(
                    uri,
                    onStage = { stage -> taggerStatusTv?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    setStatus(getString(R.string.s_267), C_OK)
                    toast(getString(R.string.s_269))
                } else {
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshTaggerUi()
            }
        }
        if (requestCode == REQ_TAGGER_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importTaggerCsv(
                    uri,
                    onStage = { stage -> taggerStatusTv?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    setStatus(getString(R.string.s_268), C_OK)
                    toast(getString(R.string.s_269))
                } else {
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshTaggerUi()
            }
        }
    }

    /** Models can't be loaded from a content:// URI, so copy them to a real file first. */
    private fun copyModelToPrivate(uri: Uri) {
        setBusy(true)
        setStatus(getString(R.string.s_130), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val name = attachmentHandler.queryDisplayName(uri) ?: ("model_${System.currentTimeMillis()}.litertlm")
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.s_111))
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    input.use { ins ->
                        dest.outputStream().use { outs ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    withContext(Dispatchers.Main) { progressBar.progress = pct }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    modelPath = dest.absolutePath
                    modelInfoText.text = getString(R.string.v_002, (dest.name), (UiHelpers.fmtSize(dest.length())))
                    setStatus(getString(R.string.s_093), C_WARN)
                    refreshSavedModels()
                    loadChatParamsIntoUi()
                    toast(getString(R.string.v_003, (dest.name)))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_072), C_ERR)
                    toast(getString(R.string.v_004, (e.message)))
                }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; setBusy(false) }
            }
        }
    }



    // ================= saved models =================

    /** Scan filesDir/models and render a tappable list of already-copied models. */
    private fun refreshSavedModels() {
        savedContainer.removeAllViews()
        val dir = File(filesDir, "models")
        // El downloader guarda en models/gguf y models/litertlm (subcarpetas),
        // así que hay que escanear un nivel más o los modelos descargados no
        // aparecen en la lista.
        val files = listOf(dir, File(dir, "gguf"), File(dir, "litertlm"))
            .flatMap { d ->
                d.listFiles { f ->
                    f.isFile && (
                        f.name.endsWith(".litertlm", ignoreCase = true) ||
                            f.name.endsWith(".gguf", ignoreCase = true)
                        )
                }?.toList() ?: emptyList()
            }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            savedContainer.visibility = View.GONE
            return
        }
        savedContainer.visibility = View.VISIBLE
        savedContainer.addView(TextView(this).apply {
            text = getString(R.string.s_089)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }, matchWrap())

        for (f in files) {
            val sel = f.absolutePath == modelPath
            val isGguf = f.name.endsWith(".gguf", ignoreCase = true)
            // .gguf 可能是语言模型也可能是画图模型，读头部元数据判断
            val kind = if (isGguf) ggufKindCache.getOrPut(f.absolutePath) { GgufProbe.probe(f) }
            else null
            val isImageModel = kind == GgufProbe.Kind.IMAGE
            // Descarga interrumpida: el archivo existe pero no llega al 90% del
            // tamaño del catálogo. Se marca y no se deja seleccionar, porque
            // cargarlo solo produce un error opaco del motor.
            val expected = ModelDownloader.expectedSizeForFileName(f.name)
            val incomplete = ModelDownloader.isIncomplete(f)
            val pct = if (incomplete && expected != null)
                (f.length() * 100 / expected).toInt().coerceIn(0, 99) else 100
            val tag = when {
                incomplete -> "incompleto $pct%"
                isImageModel -> getString(R.string.s_151)
                isGguf -> "GGUF"
                else -> "LiteRT"
            }
            val chip = TextView(this).apply {
                text = "▶ ${f.name}　[$tag] ${UiHelpers.fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(
                    if (incomplete) C_ERR_TEXT
                    else if (isImageModel) C_IDLE
                    else if (sel) C_PRIMARY
                    else C_TEXT
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (sel) C_PRIMARY_SOFT else C_CHIP_BG, 10,
                    strokeDp = if (sel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener {
                when {
                    incomplete -> toast(
                        "Descarga incompleta ($pct%). Bórrala (mantén pulsado) y descárgala de nuevo."
                    )
                    isImageModel -> toast(getString(R.string.s_188))
                    else -> selectSavedModel(f)
                }
            }
            chip.setOnLongClickListener { confirmDeleteModel(f); true }
            savedContainer.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    // ================= 已复制的绘图模型 =================

    /** 绘图模型的二合一按钮：未加载 → 加载；已加载 → 卸载。 */
    private fun onDrawToggleClick() {
        val dpg = drawPage
        if (dpg == null) {
            toast(getString(R.string.s_167))
            return
        }
        if (dpg.isReady()) {
            dpg.unloadModel()
            updateThinkEnabled()
            drawModelStatus?.text = dpg.modelSummary()
            setStatus(getString(R.string.s_116), C_IDLE)
            toast(getString(R.string.s_160))
            refreshDrawToggle()
            refreshDrawApiUi()   // 模型卸载了，绘图接口那边要变回「尚未加载绘图模型」
            return
        }
        if (!dpg.hasModel()) {
            toast(getString(R.string.s_185))
            return
        }
        scope.launch {
            drawModelStatus?.text = getString(R.string.s_128)
            dpg.useGpu = (backendSpinner.selectedItem.toString() == "GPU")
            val err = dpg.loadSelectedSet()
            drawModelStatus?.text = if (err == null) dpg.modelSummary() else err
            if (err == null) {
                drawSetId = dpg.activeSetId()
                dpg.llmLoaded = false
                updateThinkEnabled()
                refreshDrawModels()
                setStatus(getString(R.string.s_162), C_OK)
                toast(getString(R.string.s_159))
            } else {
                setStatus(getString(R.string.s_156), C_ERR)
                toast(err)
            }
            refreshDrawToggle()
        }
    }

    /** 按「绘图模型是否已加载」更新二合一按钮的文案与配色。
     *  与对话模型保持一致：加载=蓝色实心；已加载=浅灰描边（避免误以为还是主操作）。 */
    private fun refreshDrawToggle() {
        val b = drawToggleBtn ?: return
        if (drawPage?.isReady() == true) {
            b.text = getString(R.string.s_063)
            b.background = rounded(C_SOFT_BTN2, 12,
                strokeDp = 1, strokeColor = C_BORDER)
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_061)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** 列出已有的绘图模型集（点击选用 · 长按删除）。单文件与多文件都在这。 */
    private fun refreshDrawModels() {
        refreshDrawToggle()
        val box = drawSavedContainer ?: return
        box.removeAllViews()
        val dpg = drawPage
        val sets = dpg?.listSets().orEmpty()
        if (sets.isEmpty()) {
            box.visibility = View.GONE
            refreshDrawSlots()
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = getString(R.string.s_090)
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())

        dpg?.ensureActiveSet()
        val activeId = dpg?.activeSetId()
        val loadedName = dpg?.currentMainName()
        for (s in sets) {
            val isSel = s.id == activeId || s.id == drawSetId
            val chip = TextView(this).apply {
                // 列表样式与旧版一致：文件名 + 角色 + 量化 + 体积（多文件集只多一个标记，不做分类/家族展示）
                val isMain = loadedName != null && dpg?.isReady() == true && loadedName == s.name
                val role = if (isMain) getString(R.string.s_099)
                else if (s.kind == DrawPage.KIND_MULTI) getString(R.string.s_346)
                else ""
                val suffix = if (role.isEmpty()) "" else "　[$role]"
                val q = s.mainFile()?.let { dpg?.quantOf(it) }
                val qTag = if (q != null) "　[$q]" else ""
                text = "▶ ${s.mainFile()?.name ?: s.name}$suffix$qTag　${UiHelpers.fmtSize(s.sizeBytes())}"
                textSize = 12.5f
                setTextColor(if (isSel) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (isSel) C_PRIMARY_SOFT else C_CHIP_BG, 10,
                    strokeDp = if (isSel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener { selectDrawSet(s) }
            chip.setOnLongClickListener { confirmDeleteDrawSet(s); true }
            box.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
        refreshDrawSlots()
    }

    /** 点击某个模型集 → 只标记为选用，不加载（与对话模型一致，需再点「加载绘图模型」）。 */
    private fun selectDrawSet(s: DrawPage.ModelSet) {
        drawPage?.selectSet(s)
        drawSetId = s.id
        drawModelStatus?.text = drawPage?.modelSummary()
        refreshDrawToggle()
        refreshDrawModels()
    }

    // ================= LoRA =================

    /** 刷新模型页的 LoRA 状态与列表 */
    private fun refreshLoraUi() {
        val dpg = drawPage
        loraStatusTv?.text = dpg?.loraSummary() ?: getString(R.string.s_117)
        val box = loraBox ?: return
        box.removeAllViews()
        val all = dpg?.listLoras().orEmpty()
        if (all.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(listHeader(getString(R.string.s_092)))
        val active = dpg?.currentLoraName()
        for (f in all) {
            box.addView(
                selectableChip(f, f.name == active, onLongClick = { confirmDeleteLora(f) }) {
                    dpg?.selectLora(f); refreshLoraUi()
                },
                matchWrap().apply { topMargin = dp(4) }
            )
        }
    }

    /** 选下载源：官方 / hf-mirror 镜像 */
    private fun pickLoraSource() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_041))
            .setItems(arrayOf(getString(R.string.s_028), getString(R.string.s_029))) { _, which ->
                startLoraDownload(useMirror = which == 0)
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun startLoraDownload(useMirror: Boolean) {
        val dpg = drawPage ?: return
        val src = if (useMirror) "hf-mirror.com" else "huggingface.co"
        loraStatusTv?.text = getString(R.string.v_007, (src))
        setStatus(getString(R.string.s_018), C_WARN)
        var lastPct = -2
        scope.launch {
            val err = dpg.downloadLora(useMirror) { done, total ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                if (pct != lastPct) {
                    lastPct = pct
                    runOnUiThread {
                        loraStatusTv?.text = if (pct >= 0)
                            getString(R.string.v_008, (src), (pct), (UiHelpers.fmtSize(done)), (UiHelpers.fmtSize(total)))
                        else
                            getString(R.string.v_009, (src), (UiHelpers.fmtSize(done)))
                    }
                }
            }
            runOnUiThread {
                if (err == null) {
                    toast(getString(R.string.s_020))
                    setStatus(getString(R.string.s_023), C_OK)
                } else {
                    toast(err)
                    setStatus(getString(R.string.s_019), C_ERR)
                }
                refreshLoraUi()
            }
        }
    }

    /** 删除单个 LoRA：target 为空时删当前选中的那个（不再一次清空全部）。 */
    private fun confirmDeleteLora(target: File? = null) {
        val dpg = drawPage ?: return
        val f = target ?: dpg.activeLora()
        if (f == null) {
            toast(getString(R.string.s_135))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_052))
            .setMessage(f.name + getString(R.string.s_026))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val ok = dpg.deleteLora(f)
                if (ok) toast(getString(R.string.v_010, 1)) else toast(getString(R.string.s_158))
                refreshLoraUi()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 长按删除一个模型集（连同它的全部文件）。 */
    private fun confirmDeleteDrawSet(s: DrawPage.ModelSet) {
        val dpg = drawPage ?: return
        if (dpg.isReady() && dpg.activeSetId() == s.id) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_056))
            .setMessage(getString(R.string.s_366, s.name, s.slotCount()))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val ok = dpg.deleteSet(s)
                if (drawSetId == s.id) drawSetId = null
                toast(if (ok) getString(R.string.v_012, s.name) else getString(R.string.s_053))
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun selectSavedModel(f: File) {
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        modelPath = f.absolutePath
        modelInfoText.text = getString(R.string.v_013, (f.name), (UiHelpers.fmtSize(f.length())))
        setStatus(getString(R.string.s_096), C_WARN)
        refreshSavedModels()
        // 参数是按模型分别保存的，切换模型时把该模型那套回填到输入框
        loadChatParamsIntoUi()
    }

    private fun confirmDeleteModel(f: File) {
        if (f.absolutePath == modelPath && (engine != null || llamaModel != null)) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_055))
            .setMessage(getString(R.string.v_014, (f.name), (UiHelpers.fmtSize(f.length()))))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                f.delete()
                if (modelPath == f.absolutePath) {
                    modelPath = null
                    modelInfoText.text = getString(R.string.s_120)
                }
                refreshSavedModels()
                loadChatParamsIntoUi()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun toggleLoad() {
        if (engine != null || llamaModel != null) {
            unloadModel()
            return
        }
        val path = modelPath ?: run { toast(getString(R.string.s_175)); return }
        val isGguf = path.endsWith(".gguf", ignoreCase = true)
        val backendName = backendSpinner.selectedItem.toString()
        val thinking = thinkCheck.isChecked
        setBusy(true)
        setStatus(getString(R.string.s_127), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        scope.launch(Dispatchers.IO) {
            try {
                if (isGguf) {
                    // llama.cpp：单 slot + cache_prompt。会话内 KV 前缀会被复用，长对话只需计算新增 token。
                    // 线程数走全局设置（「关于 → 设置」），与绘图、打标共用同一个值。
                    val cpus = InferConfig.threads(this@MainActivity)
                    val params = ModelParameters()
                        .setModel(path)
                        // 上下文长度与最大输出都从模型页读（默认 4096 / 2048）
                        .setCtxSize(chatParams.ctx())
                        .setPredict(chatParams.maxOut())
                        .setThreads(cpus)
                        .setThreadsBatch(cpus)
                        .setBatchSize(512)
                        .setParallel(1)
                        .setKeep(64)
                        .setGpuLayers(0)
                    // 多模态 gguf：挂上 mmproj（视觉投影）后模型才有图像输入能力
                    val mp = mmprojPath?.let { File(it) }?.takeIf { it.isFile }
                    if (mp != null) params.setMmproj(mp.absolutePath)
                    val m = LlamaModel(params)
                    val vok = try { m.supportsVision() } catch (_: Throwable) { false }
                    withContext(Dispatchers.Main) {
                        llamaModel = m
                        visionOk = vok
                        // 登记到进程级单例：对话 API 服务要复用同一个模型
                        ChatCore.attachGguf(m, path.substringAfterLast('/'), vok)
                        modelSupportsThinking = guessSupportsThinking(path.substringAfterLast('/'))
                        chatParams.markModelLoaded(path)
                        tagReturnIdx = 0
                        engine = null
                        conversation = null
                        convThinking = null
                        setStatus(getString(R.string.s_078), C_OK)
                        if (backendName == "GPU") toast(getString(R.string.s_016))
                    }
                } else {
                    // 后端：选 GPU 时先试 LiteRT 的 GPU 后端；设备/驱动不支持就自动退回 CPU
                    val gpuWanted = backendName == "GPU"
                    var usedBackendName = backendName
                    // 先读模型自带的 Capabilities：是否为多模态（图文）模型
                    val vok = probeVision(path)
                    // 坑：EngineConfig.visionBackend 默认是 null，运行时就不会创建 vision executor，
                    // 发图时直接报 "Vision executor should not be null, please TryLoadingVisionExecutor() first."；
                    // 所以探测到多模态时必须显式传入 visionBackend（并同时放开 maxNumImages）。
                    val cfg = EngineConfig(
                        modelPath = path,
                        // 上下文长度（token）：模型页可调；调大能装更多历史，但更吃内存
                        maxNumTokens = chatParams.ctx(),
                        backend = if (gpuWanted) Backend.GPU() else Backend.CPU(),
                        visionBackend = if (vok) (if (gpuWanted) Backend.GPU() else Backend.CPU()) else null,
                        maxNumImages = if (vok) AttachmentHandler.MAX_ATTACH else null,
                        cacheDir = cacheDir.absolutePath,
                    )
                    val eng: Engine = try {
                        val g = Engine(cfg)
                        g.initialize()
                        g
                    } catch (e: Throwable) {
                        if (!gpuWanted) throw e
                        // GPU 后端在这台机器上用不了 → 退回 CPU，别让用户用不了模型
                        withContext(Dispatchers.Main) { toast(getString(R.string.s_335)) }
                        usedBackendName = "CPU"
                        val cpu = Engine(cfg.copy(backend = Backend.CPU(), visionBackend = if (vok) Backend.CPU() else null))
                        cpu.initialize()
                        cpu
                    }
                    val conv = eng.createConversation(inferenceParamsBuilder.configFor(current, thinking))
                    withContext(Dispatchers.Main) {
                        engine = eng
                        llamaModel = null
                        ChatCore.attachLiteRt(eng, path.substringAfterLast('/'), vok)
                            modelSupportsThinking = probeThinking(path)
                        chatParams.markModelLoaded(path)
                        tagReturnIdx = 0
                        conversation = conv
                        convThinking = thinking
                        visionOk = vok
                        convDrawCapable = canDrawFromChat
                        setStatus(getString(R.string.v_015, (usedBackendName)), C_OK)
                    }
                }
                withContext(Dispatchers.Main) {
                    loadButton.text = getString(R.string.s_062)
                    loadButton.background = rounded(C_SOFT_BTN2, 12,
                        strokeDp = 1, strokeColor = C_BORDER)
                    loadButton.setTextColor(C_TEXT)
                    // 加载语言模型 → 对话页回到聊天（drawMode 由「有无语言模型」自动决定）
                    drawPage?.llmLoaded = true
                    updateThinkEnabled()
                    addSystemHint(getString(R.string.s_077))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_058), C_ERR)
                    toast(getString(R.string.v_016, (e.message)))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                    setBusy(false)
                }
            }
        }
    }

    private fun unloadModel() {
        try { engine?.close() } catch (_: Throwable) {}
        try { llamaModel?.close() } catch (_: Throwable) {}
        ChatCore.release()
        engine = null
        llamaModel = null
        chatParams.markModelUnloaded()
        modelSupportsThinking = true
        tagReturnIdx = 0
        conversation = null
        convThinking = null
        visionOk = false
        drawPage?.llmLoaded = false
        loadButton.text = getString(R.string.s_060)
        loadButton.background = rounded(C_PRIMARY, 12)
        loadButton.setTextColor(Color.WHITE)
        setStatus(getString(R.string.s_079), C_IDLE)
        addSystemHint(getString(R.string.s_080))
        // 语言模型卸载后，若绘图模型还在，对话页会自动变回出图模式
        updateThinkEnabled()
    }

    // ================= chat =================

    /** Send button doubles as a stop button: while generating it shows a square ■. */
    private fun onSendOrStop() {
        val job = genJob
        if (job != null && job.isActive) {
            // 关键：先让原生侧真正停下来。只取消协程 Flow 的话，LiteRT / llama.cpp 的
            // native 推理线程还在跑，下一次发送会撞上同一份上下文直接卡死。
            stopRequested = true
            runCatching { conversation?.cancelProcess() }
            runCatching { ggufIterator?.cancel() }
            // 绘图（MNN / sd.cpp）也要打断：否则 native 还在跑，界面已停
            drawPage?.cancel()
            job.cancel()
            // 不能只依赖协程的 finally 来复位：native 推理不响应协程取消时 finally 会迟迟不执行，
            // busy 一直卡在 true，之后每条消息都被「正在生成中」挡回来。这里主动复位。
            genJob = null
            if (busy) setBusy(false)
            setStoppingUi(false)
            // cancelProcess() 之后 LiteRT 的 Conversation 可能已不可用：
            // 标记下一条消息发送前用历史静默重建，否则中断后再也发不出内容。
            if (conversation != null) convNeedsRebuild = true
            toast(getString(R.string.s_083))
        } else {
            doSend()
        }
    }

    private fun setStoppingUi(stop: Boolean) {
        if (stop) {
            sendButton.text = "■"
            sendButton.textSize = 16f
            sendButton.background = rounded(C_ERR, 22)
        } else {
            sendButton.text = "↑"
            sendButton.textSize = 19f
            sendButton.background = rounded(C_PRIMARY, 22)
        }
        sendButton.isEnabled = true
        sendButton.alpha = 1f
    }

    // ---- 多对话管理 ----

    /** 新建一个对话（旧对话保留在列表中）。 */
    private fun newSession() {
        if (busy) { toast(getString(R.string.s_144)); return }
        sessionManager.save()
        val s = ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        sessions += s
        current = s
        restoreSession()
        sessionManager.save()
        toast(getString(R.string.s_095))
    }

    private fun switchTo(s: ChatSession) {
        if (s === current) return
        if (busy) { toast(getString(R.string.s_144)); return }
        sessionManager.save()
        current = s
        restoreSession()
        sessionManager.save()
    }

    private fun confirmDeleteSession(s: ChatSession) {
        if (sessions.size <= 1) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.s_139))
                .setMessage(getString(R.string.s_187))
                .setPositiveButton(getString(R.string.s_136)) { _, _ ->
                    s.turns.clear()
                    s.title = getString(R.string.s_109)
                    restoreSession()
                    sessionManager.save()
                    if (drawerOpen) refreshDrawer()
                    toast(getString(R.string.s_101))
                }
                .setNegativeButton(getString(R.string.s_066), null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_054))
            .setMessage(s.title)
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val wasCurrent = s === current
                sessions.remove(s)
                if (wasCurrent) { current = sessions.last(); restoreSession() }
                sessionManager.save()
                if (drawerOpen) refreshDrawer()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    // ---- 会话切换/重建 ----

    /**
     * GGUF 推理参数：带上对话历史 + cache_prompt(true) + 固定 slot。
     * llama.cpp 会把本轮 prompt 与 slot 里已缓存的 KV 做前缀匹配，只计算新增部分 ——
     * 这是长对话不重复 prefill 的关键。
     */
    // buildInferenceParams / buildMultimodalParams / configFor → InferenceParamsBuilder

    // buildInferenceParams / buildMultimodalParams / configFor → InferenceParamsBuilder

    /**
     * 思考开关变化后重建会话。
     * LiteRT-LM 的思考标记是在会话创建时写入提示模板的，
     * 会话跑起来后仅改 per-call 的 ThinkingConfig 不会生效（尤其是 关→开），
     * 所以必须按新模式重建会话；历史通过 initialMessages 保留。
     */
    private fun rebuildConversation(silent: Boolean = false, dropLast: Boolean = false) {
        val eng = engine ?: return
        val thinking = thinkCheck.isChecked
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = try {
            eng.createConversation(inferenceParamsBuilder.configFor(current, thinking, dropLast = dropLast))
        } catch (e: Throwable) {
            toast(getString(R.string.v_017, (e.message)))
            eng.createConversation(
                ConversationConfig(
                    thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = chatParams.thinkBudgetOrUnlimited())
                )
            )
        }
        convThinking = thinking
        convDrawCapable = canDrawFromChat
        if (!silent) {
            addSystemHint(
                if (thinking) getString(R.string.s_204)
                else getString(R.string.s_085)
            )
        }
    }

    /** 重建 LiteRT 会话（若模型已加载）并把聊天区重绘为当前对话的内容。 */
    private fun restoreSession(showHint: Boolean = true) {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        convThinking = null
        val eng = engine
        if (eng != null && llamaModel == null) {
            val thinking = thinkCheck.isChecked
            conversation = eng.createConversation(inferenceParamsBuilder.configFor(current, thinking))
            convThinking = thinking
            convDrawCapable = canDrawFromChat
        }
        chatContainer.removeAllViews()
        chatScrollManager?.resetForRebuild()
        if (current.turns.isEmpty()) {
            if (showHint) {
                addSystemHint(getString(R.string.s_032) +
                    getString(R.string.s_033))
            }
        } else {
            for (t in current.turns) renderTurn(t)
        }
        chatScrollManager?.scrollToBottom(force = true)
    }

    /**
     * 重新生成某一轮的回答。
     * 该轮之后的轮次依赖这轮的上下文，必须一并丢弃；然后把它重新送进去生成。
     */
    private fun regenerate(turn: QaTurn) {
        if (busy) {
            toast(getString(R.string.s_144))
            return
        }
        val idx = current.turns.indexOf(turn)
        if (idx < 0) return
        val text = turn.user
        // 忠实重发：这一轮带的图片与文件要一起还原，否则「重新生成」会退化成纯文字提问
        val imgNames = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val imgPairs = imgNames.mapNotNull { n ->
            readChatImage(n)?.let { bmp -> bmp to bitmapToJpeg(bmp) }
        }
        val fNames = turn.userFile?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val dropped = current.turns.size - idx - 1
        while (current.turns.size > idx) current.turns.removeAt(current.turns.size - 1)
        restoreSession(showHint = false)
        if (dropped > 0) toast(getString(R.string.v_018, (dropped)))
        attachmentHandler.clearPendingImages()
        attachmentHandler.clearPendingFiles()
        for (p in imgPairs) attachmentHandler.addPendingImage(p.first, p.second)
        // 文件：把上一轮的原始内容还原到附件条（走和正常发送完全一样的路径）
        val restored = attachmentHandler.restoreFiles(turn.fileStore)
        if (restored > 0) toast(getString(R.string.s_320, (restored)))
        else if (fNames.isNotEmpty()) toast(getString(R.string.s_321))
        inputEdit.setText(text)
        doSend()
    }

    /** 把一轮已有问答重绘到聊天区（历史回填）。 */
    /** 绘图模式下思考开关无意义（不经过对话模型），置灰禁用 */
    private fun updateThinkEnabled() {
        // 可能由 IO 线程上的回调触发；setEnabled 会驱动 Ripple 动画，必须回主线程
        runOnUiThread {
            val visible = modelSupportsThinking
            val enabled = visible && !drawMode
            thinkCheck.visibility = if (visible) View.VISIBLE else View.GONE
            thinkCheck.isEnabled = enabled
            thinkCheck.alpha = if (enabled) 1f else 0.45f
        }
    }

    /** Heurística por nombre para saber si un modelo admite reasoning/thinking.
     *  No hay API fiable en litertlm para 'supportsThinking' (Capabilities solo
     *  expone inputModalities().vision), así que se basa en el nombre del archivo.
     *  Familias razonantes conocidas: DeepSeek-R1, Qwen3, Kimi/K1, etc. */
    private fun guessSupportsThinking(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("r1") || n.contains("qwen3") || n.contains("thinking") ||
            n.contains("kimi") || n.contains("deepseek-reasoning")
    }

    /** Lee supportsThinking() real del modelo LiteRT (API 0.18+); si falla, usa la heurística. */
    private fun probeThinking(path: String): Boolean = try {
        ModelInfo.from(path).use {
            (it as? ModelInfo.Llm)?.supportsThinking()
                ?: guessSupportsThinking(path.substringAfterLast('/'))
        }
    } catch (_: Throwable) {
        guessSupportsThinking(path.substringAfterLast('/'))
    }

    private fun renderTurn(t: QaTurn) {
        val names = t.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val files = t.userFile?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        if (names.isEmpty()) {
            addUserBubble(t.user, emptyList(), files)
        } else {
            addUserBubble(t.user, names.mapNotNull { readChatImage(it) }, files)
        }
        val ai = addAiArea { regenerate(t) }
        val imgName = t.image
        if (imgName != null) {
            val bmp = readChatImage(imgName)
            if (bmp != null) attachImageBubble(ai, bmp, imgName) else ai.answer.text = getString(R.string.s_003)
        }
        if (t.thought.isNotEmpty()) {
            ai.thoughtBox.visibility = View.VISIBLE
            ai.thoughtBody.text = t.thought
            ai.thoughtHeader.text = getString(R.string.s_205)
        }
        if (t.answer.isNotEmpty()) {
            markwonFull.setMarkdown(ai.answer, t.answer)
        } else {
            ai.answer.text = getString(R.string.s_005)
            ai.answer.setTextColor(C_SUBTEXT)
        }
    }

    // ---- 持久化 ----

    private fun doDrawFromChat(prompt: String, dpg: DrawPage) {
        if (busy) { toast(getString(R.string.s_133)); return }
        val turn = QaTurn(prompt)
        current.turns += turn
        if (current.title == getString(R.string.s_109)) current.title = prompt.take(18)

        inputEdit.setText("")
        addUserBubble(prompt)
        chatScrollManager?.jumpToBottom()
        setBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_166), C_WARN)

        val ai = addAiArea()
        ai.regenButton.visibility = View.GONE
        markwonStream.setMarkdown(ai.answer, getString(R.string.s_202))

        genJob = scope.launch {
            // 气泡里每秒刷新一次进度（sd.cpp 每步回调 + 本地计时），
            // 否则整段生成期间只能看到静止的「🎨 正在生成图片…」
            var curStep = 0
            var totalStep = 0
            val drawStartedAt = System.currentTimeMillis()
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val sec = (System.currentTimeMillis() - drawStartedAt) / 1000
                    val stepInfo = if (totalStep > 0) getString(R.string.v_019, (curStep), (totalStep)) else ""
                    markwonStream.setMarkdown(ai.answer, getString(R.string.v_020, (stepInfo), (sec)))
                }
            }
            try {
                val img = dpg.generateImage(
                    prompt,
                    onProgress = { cur, total ->
                        curStep = cur
                        totalStep = total
                        runOnUiThread {
                            if (cur == total || cur % 2 == 0) setStatus(getString(R.string.v_021, (cur), (total)), C_WARN)
                        }
                    },
                    onPreview = { st, w, h, rgb -> dpg.previewFrame(st, w, h, rgb) },
                )
                ticker.cancel()
                val bmp = dpg.toBitmap(img)
                val name = "ponko_${System.currentTimeMillis()}.png"
                val saved = withContext(Dispatchers.IO) { writeChatImage(bmp, name) }
                turn.image = if (saved) name else null
                turn.answer = "🎨 seed=${img.seed}"
                markwonStream.setMarkdown(ai.answer, "")
                attachImageBubble(ai, bmp, name)
                setStatus(getString(R.string.s_153), C_OK)
            } catch (e: com.yumenode.ai.draw.GenerationCancelledException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
            } catch (e: CancellationException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                turn.answer = getString(R.string.v_022, (e.message))
                markwonStream.setMarkdown(ai.answer, getString(R.string.v_023, (e.message)))
                setStatus(getString(R.string.s_152), C_ERR)
            } finally {
                ticker.cancel()
                ai.regenButton.visibility = View.VISIBLE
                sessionManager.save()
                setBusy(false)
                setStoppingUi(false)
                chatScrollManager?.scrollToBottom()
            }
        }
    }

    /** 把图片写进应用私有目录，返回是否成功 */
    private fun writeChatImage(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun readChatImage(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(File(filesDir, "chatimg"), name).absolutePath)
    } catch (_: Throwable) {
        null
    }

    /** 在 AI 气泡里插入图片；点图弹确认框再存相册（以前点一下就存，容易误触） */
    /** 全屏看图：双指缩放、拖动、双击放大；未放大时点图关闭，右上角 ✕ 随时关闭。 */
    private fun showImageFullscreen(bmp: Bitmap) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ZoomImageView(this).apply { setImageBitmap(bmp) }
        iv.onTapAtFit = { dlg.dismiss() }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(0x66000000, 20)
            isClickable = true
            setOnClickListener { dlg.dismiss() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(close, FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(36)
                rightMargin = dp(16)
            })
        }
        dlg.setContentView(root)
        dlg.show()
    }

    /** 图片长按菜单：保存到相册 / 引用 / 发送至图生图 / 发送至 Tagger。 */
    private fun showImageMenu(bmp: Bitmap, name: String) {
        val items = arrayOf(
            getString(R.string.s_045),
            getString(R.string.s_291),
            getString(R.string.s_225),
            getString(R.string.s_238)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_293))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveImageToGallery(bmp, name)
                    1 -> quoteImage(bmp)
                    2 -> { drawPage?.sendImageToI2i(bmp); switchTab(2) }
                    else -> { drawPage?.sendImageToTagger(bmp); switchTab(2) }
                }
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 引用：把图片直接放进输入栏待发送附件（不落盘，等同于在输入栏选了这张图）。 */
    private fun quoteImage(bmp: Bitmap) {
        if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
        if (!visionOk) { toast(getString(R.string.s_272)); return }
        if (attachmentHandler.getPendingImages().size >= AttachmentHandler.MAX_ATTACH) { toast(getString(R.string.s_279)); return }
        attachmentHandler.addPendingImage(bmp, bitmapToJpeg(bmp))
        switchTab(0)
        toast(getString(R.string.s_292))
    }

    private fun attachImageBubble(ai: AiArea, bmp: Bitmap, name: String) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            isClickable = true
            isFocusable = false
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { showImageFullscreen(bmp) }
            setOnLongClickListener { showImageMenu(bmp, name); true }
        }
        ai.root.addView(iv, matchWrap())
        ai.root.addView(TextView(this).apply {
            text = getString(R.string.s_141)
            textSize = 11f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())
    }

    private fun saveImageToGallery(bmp: Bitmap, name: String) = withLegacyStorage { doSaveImageToGallery(bmp, name) }

    private fun doSaveImageToGallery(bmp: Bitmap, name: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/Yumenode AI"
                            )
                        }
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        true
                    } else false
                } catch (_: Throwable) {
                    false
                }
            }
            toast(if (ok) getString(R.string.s_084) else getString(R.string.s_046))
        }
    }

    private fun doSend() {
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
        setBusy(true)
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
                setBusy(false)
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

    private fun extractText(m: Message): String = ChatCore.messageText(m)

    /**
     * LiteRT 的流式片段语义在版本间不完全一致（可能是增量片段，也可能带累积内容）。
     * 统一兼容，避免出现「您好，有什么可以帮你？」被反复拼接的情况：
     *   - 与已累积内容完全相同     → 重复快照，丢弃
     *   - 新片段以已累积内容开头   → 全量快照，用新片段替换
     *   - 已累积内容以新片段结尾   → 尾部重复片段，丢弃（忽略首尾空白）
     *   - 其余                     → 按增量追加
     */
    private fun mergeStreamDelta(cur: String, delta: String): String = ChatCore.mergeStream(cur, delta)

    private class AiArea(
        val root: LinearLayout,
        val thoughtBox: LinearLayout,
        val thoughtHeader: TextView,
        val thoughtBody: TextView,
        val answer: TextView,
        val regenButton: TextView,
    )

    /** AI reply card: collapsible thinking section on top, Markdown answer below. */
    private fun addAiArea(onRegen: (() -> Unit)? = null): AiArea {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(C_CARD, 16)
            elevation = dp(1).toFloat()
        }

        val thoughtBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(C_THOUGHT_BG, 10)
            visibility = View.GONE
        }
        val thoughtHeader = TextView(this).apply {
            text = getString(R.string.s_205)
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
        }
        val thoughtBody = TextView(this).apply {
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
        }
        thoughtHeader.setOnClickListener {
            val show = thoughtBody.visibility != View.VISIBLE
            thoughtBody.visibility = if (show) View.VISIBLE else View.GONE
            thoughtHeader.text = if (show) getString(R.string.s_206) else getString(R.string.s_205)
            if (show) chatScrollManager?.scrollToBottom()
        }
        thoughtBox.addView(thoughtHeader, matchWrap())
        thoughtBox.addView(thoughtBody, matchWrap().apply { topMargin = dp(6) })
        wrap.addView(thoughtBox, matchWrap())

        val answer = TextView(this).apply {
            textSize = 16f
            setTextColor(C_TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            // 不给 isFocusable = false：那会把 setTextIsSelectable 设的焦点属性覆盖掉，
            // 导致长按无法选中复制。防键盘焦点抢夺由 renderAnswer/renderThought 里的焦点守卫负责。
        }
        wrap.addView(answer, matchWrap().apply { topMargin = dp(4) })

        // 「重新生成」：对回答不满意时，丢掉这一轮（及其后）的回答重问一次
        val regenButton = TextView(this).apply {
            text = getString(R.string.s_031)
            textSize = 12.5f
            setTextColor(C_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            isFocusable = false
            visibility = if (onRegen != null) View.VISIBLE else View.GONE
            setOnClickListener { onRegen?.invoke() }
        }
        wrap.addView(regenButton, matchWrap())

        chatContainer.addView(wrap, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        chatScrollManager?.scrollToBottom()
        return AiArea(wrap, thoughtBox, thoughtHeader, thoughtBody, answer, regenButton)
    }

    private fun addUserBubble(text: String, images: List<Bitmap> = emptyList(), files: List<String> = emptyList()) {
        if (files.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            for (nm in files) {
                strip.addView(TextView(this).apply {
                    // 注意要写 this.text：本函数的 text 参数会把 TextView.text 遮住
                    this.text = "📄 $nm"
                    textSize = 12f
                    setTextColor(C_TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = rounded(C_PRIMARY_SOFT, 12)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) })
            }
            chatContainer.addView(strip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        if (images.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val w = if (images.size == 1) dp(160) else dp(112)
            for (b in images) {
                strip.addView(ImageView(this).apply {
                    setImageBitmap(b)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    setOnClickListener { showImageFullscreen(b) }
                    setOnLongClickListener {
                        showImageMenu(b, "ponko_${System.currentTimeMillis()}.png"); true
                    }
                }, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                })
            }
            chatContainer.addView(strip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        if (text.isEmpty()) {
            chatScrollManager?.scrollToBottom()
            return
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(C_PRIMARY, 16)
            elevation = dp(1).toFloat()
        }
        chatContainer.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            leftMargin = dp(56)
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        chatScrollManager?.scrollToBottom()
    }

    private fun addSystemHint(text: String) {
        chatContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(C_SUBTEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, matchWrap())
        chatScrollManager?.scrollToBottom()
    }

    // ================= helpers =================



    private fun setBusy(b: Boolean) {
        busy = b
        loadButton.isEnabled = !b
        loadButton.alpha = if (b) 0.5f else 1f
        // 生成过程中不禁用输入框：可以照常打字（此时发送键是「停止」，只是不发送）
    }

    /** 导入/复制期间禁用「绘图模型」区的按钮，避免重复触发。 */
    private fun setDrawBusy(b: Boolean) {
        drawToggleBtn?.isEnabled = !b
        drawToggleBtn?.alpha = if (b) 0.5f else 1f
        drawImportBtn?.isEnabled = !b
        drawImportBtn?.alpha = if (b) 0.5f else 1f
        taggerOnnxBtn?.isEnabled = !b
        taggerOnnxBtn?.alpha = if (b) 0.5f else 1f
        taggerCsvBtn?.isEnabled = !b
        taggerCsvBtn?.alpha = if (b) 0.5f else 1f
        taggerLoadBtn?.isEnabled = !b
        taggerLoadBtn?.alpha = if (b) 0.5f else 1f
    }

    /** 忙碌时禁用「对话模型」区域的加载按钮。 */
    private fun setMainBusy(b: Boolean) {
        loadButton.isEnabled = !b
        loadButton.alpha = if (b) 0.5f else 1f
    }

    /** 刷新模型页的打标（Tagger）状态：状态行 + 可选列表（模型 / 标签表） */
    private fun refreshTaggerUi() {
        val dpg = drawPage
        taggerStatusTv?.text = dpg?.taggerSummary() ?: getString(R.string.s_245)
        dpg?.refreshStatus()
        refreshTaggerToggle()
        val box = taggerBox ?: return
        box.removeAllViews()
        val models = dpg?.listTaggerModels().orEmpty()
        val csvs = dpg?.listTaggerCsvs().orEmpty()
        if (models.isEmpty() && csvs.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val curM = dpg?.currentTaggerModelName()
        val curC = dpg?.currentTaggerCsvName()
        if (models.isNotEmpty()) {
            box.addView(listHeader(getString(R.string.s_252)))
            for (f in models) {
                box.addView(
                    selectableChip(f, f.name == curM) { dpg?.selectTaggerModel(f); refreshTaggerUi() },
                    matchWrap().apply { topMargin = dp(4) }
                )
            }
        }
        if (csvs.isNotEmpty()) {
            box.addView(listHeader(getString(R.string.s_253)))
            for (f in csvs) {
                box.addView(
                    selectableChip(f, f.name == curC) { dpg?.selectTaggerCsv(f); refreshTaggerUi() },
                    matchWrap().apply { topMargin = dp(4) }
                )
            }
        }
    }

    /** 按当前是否已加载切换按钮文案与配色（与「对话/绘图」一致：未加载=蓝底，已加载=浅底） */
    private fun refreshTaggerToggle() {
        val b = taggerLoadBtn ?: return
        if (drawPage?.taggerLoaded() == true) {
            b.text = getString(R.string.s_255)
            b.background = rounded(C_SOFT_BTN2, 12,
                strokeDp = 1, strokeColor = C_BORDER)
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_254)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** 加载 / 卸载打标模型（多个打标模型之间切换用） */
    private fun onTaggerToggleClick() = taggerUi.onTaggerToggleClick()

    /** 列表小标题 */
    private fun listHeader(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(C_SUBTEXT)
        setPadding(0, dp(6), 0, 0)
    }

    /** 可点选的条目（高亮当前选中）；onLongClick 可选（如长按删除这一项） */
    private fun selectableChip(
        f: File,
        selected: Boolean,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ) = TextView(this).apply {
        text = "▶ ${f.name}　${UiHelpers.fmtSize(f.length())}"
        textSize = 12.5f
        setTextColor(if (selected) C_PRIMARY else C_TEXT)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(if (selected) C_PRIMARY_SOFT else C_CHIP_BG, 10,
            strokeDp = if (selected) 1 else 0, strokeColor = C_PRIMARY)
        isClickable = true
        setOnClickListener { onClick() }
        if (onLongClick != null) {
            isLongClickable = true
            setOnLongClickListener { onLongClick(); true }
        }
    }

    private fun showDrawProgress(show: Boolean) {
        val bar = drawProgress ?: return
        if (show) {
            bar.visibility = View.VISIBLE
            bar.progress = 0
            bar.isIndeterminate = false
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun updateDrawProgress(done: Long, total: Long) {
        val bar = drawProgress ?: return
        if (total > 0) {
            bar.isIndeterminate = false
            bar.progress = ((done * 100 / total).toInt()).coerceIn(0, 100)
        } else {
            bar.isIndeterminate = true
        }
    }

}
