// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.io.File

/**
 * Construye la página completa de modelos (Chat / Draw / Tagger + ViewPager).
 *
 * Toda la lógica de UI vive aquí; [ModelsHost] proporciona los callbacks
 * y estado necesario. Las vistas resultantes se exponen como públicas
 * para que la Activity pueda acceder.
 */
class ModelsPage(private val host: ModelsHost) : ThemeAliases {

    private val activity: Activity get() = host.activity
    private val ui: UiWidgets get() = host.ui
    private val pal: YumenodeTheme get() = host.palette
    override val palette: YumenodeTheme get() = pal

    // ── vistas expuestas para MainActivity ──
    lateinit var backendSpinner: Spinner
    lateinit var modelInfoText: TextView
    lateinit var savedContainer: LinearLayout
    lateinit var loadButton: TextView
    lateinit var progressBar: ProgressBar
    lateinit var chatParamsOwnerTv: TextView
    lateinit var chatCtxEdit: EditText
    lateinit var chatMaxOutEdit: EditText
    lateinit var chatTempEdit: EditText
    lateinit var chatTopKEdit: EditText
    lateinit var chatTopPEdit: EditText
    lateinit var chatRepEdit: EditText
    lateinit var chatBudgetEdit: EditText
    lateinit var chatSeedEdit: EditText
    lateinit var chatApiModelTv: TextView
    lateinit var chatApiBlock: ApiBlockView
    lateinit var chatSubTabModel: TextView
    lateinit var chatSubTabApi: TextView
    lateinit var chatModelPane: View
    lateinit var chatApiPane: View
    lateinit var drawModelStatus: TextView
    lateinit var drawImportBtn: TextView
    lateinit var drawProgress: ProgressBar
    lateinit var drawSavedContainer: LinearLayout
    lateinit var slotsHeader: TextView
    lateinit var drawSlotsContainer: LinearLayout
    lateinit var drawToggleBtn: TextView
    lateinit var drawApiModelTv: TextView
    lateinit var drawApiBlock: ApiBlockView
    lateinit var drawSubTabModel: TextView
    lateinit var drawSubTabApi: TextView
    lateinit var drawModelPane: View
    lateinit var drawApiPane: View
    lateinit var taggerStatusTv: TextView
    lateinit var taggerOnnxBtn: TextView
    lateinit var taggerCsvBtn: TextView
    lateinit var taggerBox: LinearLayout
    lateinit var taggerLoadBtn: TextView
    lateinit var modelsPager: ViewPager
    lateinit var modelsTabChat: TextView
    lateinit var modelsTabDraw: TextView
    lateinit var modelsTabTag: TextView
    var loraStatusTv: TextView? = null
        private set
    var loraBox: LinearLayout? = null
        private set

    private fun dp(p: Int): Int = UiHelpers.dp(p, activity)
    private fun getString(resId: Int): String = activity.getString(resId)
    private fun matchWrap(topMargin: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }

    private fun wrapWrap(leftMargin: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.leftMargin = dp(leftMargin) }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG_ALT)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        // ================= 运行方式（对话 / 绘图共用） =================
        val card = ui.sectionCard()
        card.addView(ui.pageTitle(getString(R.string.s_181)))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = getString(R.string.s_069)
            textSize = 13f
            setTextColor(C_TEXT)
        }, wrapWrap())
        backendSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("CPU", "GPU")
            )
            setSelection(0)
        }
        row.addView(backendSpinner, wrapWrap(leftMargin = 6))
        backendSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                host.onSetDrawUseGpu(position == 1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        card.addView(row, matchWrap(topMargin = 10))
        card.addView(
            ui.hintText(getString(R.string.s_075)),
            matchWrap(topMargin = 4)
        )

        // ================= 对话模型 =================
        val chatCard = ui.sectionCard()
        chatCard.addView(ui.pageTitle(getString(R.string.s_076)))
        chatCard.addView(ui.hintText(getString(R.string.s_122)))

        modelInfoText = TextView(activity).apply {
            text = getString(R.string.s_120)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        chatCard.addView(modelInfoText, matchWrap(topMargin = 6))

        chatCard.addView(
            ui.actionButton(getString(R.string.s_189)) { host.onPickModel() },
            matchWrap(topMargin = 8)
        )

        savedContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chatCard.addView(savedContainer, matchWrap(topMargin = 6))

        // ---- gguf 多模态：视觉投影文件（mmproj）----
        chatCard.addView(ui.divider(), matchWrap(topMargin = 10))
        chatCard.addView(ui.subTitle(getString(R.string.s_280)), matchWrap(topMargin = 10))
        chatCard.addView(ui.hintText(getString(R.string.s_281)), matchWrap(topMargin = 4))
        val mmprojStatusTv = TextView(activity).apply {
            text = getString(R.string.s_283)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        chatCard.addView(mmprojStatusTv, matchWrap(topMargin = 6))
        val mmprojContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chatCard.addView(mmprojContainer, matchWrap(topMargin = 6))
        VisionManager(
            ctx = activity,
            scope = host.scope,
            mmprojContainer = mmprojContainer,
            mmprojStatusTv = mmprojStatusTv,
            onProgress = { pct -> host.onProgress(pct) },
            onStatus = { text, color -> host.onVisionStatus(text, color) },
            onError = { host.onVisionError(it) },
            onModelChanged = { host.onModelChanged() },
        )
        chatCard.addView(
            ui.actionButton(getString(R.string.s_282)) { host.onPickMmprojFile() },
            matchWrap(topMargin = 8)
        )
        host.onRefreshMmprojUi()

        // 加载按钮放在对话参数之上（参数按模型分别保存，先选/先加载模型更顺手）
        loadButton = ui.actionButton(getString(R.string.s_060)) { host.onToggleLoad() }
        chatCard.addView(loadButton, matchWrap(topMargin = 8))

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            indeterminateTintList = android.content.res.ColorStateList.valueOf(C_PRIMARY)
            progressTintList = android.content.res.ColorStateList.valueOf(C_PRIMARY)
        }
        chatCard.addView(progressBar, matchWrap(topMargin = 8))

        // ---- 对话参数：上下文/采样等，模型页直接可调 ----
        chatCard.addView(ui.divider(), matchWrap(topMargin = 12))
        chatCard.addView(ui.subTitle(getString(R.string.s_303)), matchWrap(topMargin = 10))
        chatCard.addView(ui.hintText(getString(R.string.s_304)), matchWrap(topMargin = 4))
        chatParamsOwnerTv = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(C_PRIMARY)
        }
        chatCard.addView(chatParamsOwnerTv, matchWrap(topMargin = 4))
        chatCard.addView(
            UiHelpers.twoCols(
                context = activity,
                first = UiHelpers.numField(
                    activity, getString(R.string.s_305), host.chatParams.ctx().toString(),
                    getString(R.string.s_313), { chatCtxEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
                second = UiHelpers.numField(
                    activity, getString(R.string.s_306), host.chatParams.maxOut().toString(),
                    getString(R.string.s_315), { chatMaxOutEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
            ),
            matchWrap(topMargin = 8)
        )
        chatCard.addView(
            UiHelpers.twoCols(
                context = activity,
                first = UiHelpers.numField(
                    activity, getString(R.string.s_307), host.chatParams.temp().toString(), "",
                    { chatTempEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
                second = UiHelpers.numField(
                    activity, getString(R.string.s_308), host.chatParams.topK().toString(), "",
                    { chatTopKEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
            ),
            matchWrap(topMargin = 8)
        )
        chatCard.addView(
            UiHelpers.twoCols(
                context = activity,
                first = UiHelpers.numField(
                    activity, getString(R.string.s_309), host.chatParams.topP().toString(), "",
                    { chatTopPEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
                second = UiHelpers.numField(
                    activity, getString(R.string.s_310), host.chatParams.repPenalty().toString(),
                    getString(R.string.s_315), { chatRepEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
            ),
            matchWrap(topMargin = 8)
        )
        chatCard.addView(
            UiHelpers.twoCols(
                context = activity,
                first = UiHelpers.numField(
                    activity, getString(R.string.s_311), host.chatParams.budget().toString(),
                    getString(R.string.s_316), { chatBudgetEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
                second = UiHelpers.numField(
                    activity, getString(R.string.s_312), host.chatParams.seed().toString(),
                    getString(R.string.s_314), { chatSeedEdit = it }, { host.onSaveChatParams() }, { pal }, ui
                ),
            ),
            matchWrap(topMargin = 8)
        )
        chatCard.addView(
            ui.smallButton(getString(R.string.s_249)) { host.onResetChatParams() },
            matchWrap(topMargin = 8)
        )

        // ================= 对话 API =================
        chatApiModelTv = TextView(activity).apply {
            textSize = 12f
            setTextColor(C_TEXT)
            setPadding(0, dp(6), 0, 0)
        }
        chatApiBlock = host.onBuildApiBlock(
            role = ApiServer.Role.CHAT,
            extraAfterAddress = listOf(chatApiModelTv),
            extraNotes = emptyList(),
        )
        val apiCard = chatApiBlock.card

        chatSubTabModel = ModelsHelpers.modelsSubTab(activity, getString(R.string.s_419))
        chatSubTabApi = ModelsHelpers.modelsSubTab(activity, getString(R.string.s_420))
        chatSubTabModel.setOnClickListener { host.onSwitchChatSubPage(0) }
        chatSubTabApi.setOnClickListener { host.onSwitchChatSubPage(1) }
        val chatSubBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.surface)
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        chatSubBar.addView(
            chatSubTabModel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        chatSubBar.addView(
            chatSubTabApi,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        chatModelPane = ModelsHelpers.modelsPane(activity, chatCard)
        chatApiPane = ModelsHelpers.modelsPane(activity, apiCard)
        val chatPane = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // 与上面那行拉开距离
        chatPane.addView(chatSubBar, matchWrap(topMargin = 12))
        chatPane.addView(
            chatModelPane,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        chatPane.addView(
            chatApiPane,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        host.onRefreshApiUi()
        host.onLoadChatParamsIntoUi()

        // ================= 绘图模型 =================
        val drawCard = ui.sectionCard()
        drawCard.addView(ui.pageTitle(getString(R.string.s_155)))
        drawCard.addView(
            ui.hintText(getString(R.string.s_030)),
            matchWrap(topMargin = 4)
        )
        drawModelStatus = TextView(activity).apply {
            text = getString(R.string.s_113)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        drawCard.addView(drawModelStatus, matchWrap(topMargin = 6))
        if (host.pendingDrawCrash) {
            drawCard.addView(TextView(activity).apply {
                text = getString(R.string.s_035) + (host.pendingDrawCrashStage ?: getString(R.string.s_197))
                textSize = 11f
                setTextColor(C_ERR)
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }
        host.takeCrashLog()?.let { log ->
            drawCard.addView(TextView(activity).apply {
                text = getString(R.string.s_038) + log.takeLast(1200)
                textSize = 10.5f
                setTextColor(C_ERR)
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }

        drawImportBtn = ui.actionButton(getString(R.string.s_190)) { host.onShowDrawTypeMenu() }
        drawCard.addView(drawImportBtn, matchWrap(topMargin = 8))

        drawProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(C_PRIMARY)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(C_PRIMARY)
        }
        drawCard.addView(drawProgress, matchWrap(topMargin = 8))

        drawSavedContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        drawCard.addView(drawSavedContainer, matchWrap(topMargin = 6))

        // 多文件模型：槽位列表折叠
        slotsHeader = TextView(activity).apply {
            textSize = 13f
            setTextColor(C_PRIMARY)
            setPadding(dp(2), dp(8), dp(2), dp(2))
            isClickable = true
            visibility = View.GONE
        }
        slotsHeader.setOnClickListener { host.onToggleDrawSlots() }
        drawCard.addView(slotsHeader, matchWrap())
        drawSlotsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        drawCard.addView(drawSlotsContainer, matchWrap(topMargin = 2))

        drawToggleBtn = ui.actionButton(getString(R.string.s_061)) { host.onDrawToggleClickRequested() }
        drawCard.addView(drawToggleBtn, matchWrap(topMargin = 8))
        drawCard.addView(
            ui.smallButton(getString(R.string.s_123)) {
                val f = File(host.filesDir, "draw/.loadstage")
                val log =
                    if (f.exists()) runCatching { f.readText() }.getOrDefault(getString(R.string.s_198))
                    else getString(R.string.s_196)
                val body = log.takeLast(8000)
                val tv = TextView(activity).apply {
                    text = body
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val sc = ScrollView(activity).apply { addView(tv) }
                AlertDialog.Builder(activity)
                    .setTitle(getString(R.string.s_157))
                    .setView(sc)
                    .setPositiveButton(getString(R.string.s_071)) { dialog, _ ->
                        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ponko-draw-log", body))
                        host.toast(getString(R.string.s_087))
                    }
                    .setNeutralButton(getString(R.string.s_136)) { dialog, _ ->
                        runCatching { f.delete() }
                        host.toast(getString(R.string.s_112))
                    }
                    .setNegativeButton(getString(R.string.s_049), null)
                    .show()
            },
            matchWrap(topMargin = 10)
        )

        // ---- LoRA ----
        drawCard.addView(ui.divider(), matchWrap(topMargin = 14))
        drawCard.addView(ui.subTitle(getString(R.string.s_021)), matchWrap(topMargin = 12))
        drawCard.addView(
            ui.hintText(getString(R.string.s_017)),
            matchWrap(topMargin = 4)
        )
        val lStatus = TextView(activity).apply {
            text = getString(R.string.s_117)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        loraStatusTv = lStatus
        drawCard.addView(lStatus, matchWrap(topMargin = 6))
        loraBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        drawCard.addView(loraBox, matchWrap(topMargin = 6))
        drawCard.addView(
            ui.actionButton(getString(R.string.s_039)) { host.onPickLoraSource() },
            matchWrap(topMargin = 8)
        )
        drawCard.addView(
            ui.actionButton(getString(R.string.s_219)) { host.onPickLoraFile() },
            matchWrap(topMargin = 8)
        )
        // TAESD: mini autoencoder para decodificar mucho mas rapido en CPU
        drawCard.addView(
            ui.actionButton(getString(R.string.s_548)) { host.onPickTaesdSource() },
            matchWrap(topMargin = 8)
        )
        drawCard.addView(
            ui.smallButton(getString(R.string.s_051)) { host.onConfirmDeleteLora() },
            matchWrap(topMargin = 10)
        )

        // ---- 绘图 API ----
        drawApiModelTv = TextView(activity).apply {
            textSize = 12f
            setTextColor(C_TEXT)
            setPadding(0, dp(6), 0, 0)
        }
        drawApiBlock = host.onBuildApiBlock(
            role = ApiServer.Role.DRAW,
            extraAfterAddress = listOf(drawApiModelTv),
            extraNotes = listOf(ui.hintText(getString(R.string.s_425))),
        )
        val drawApiCard = drawApiBlock.card

        drawSubTabModel = ModelsHelpers.modelsSubTab(activity, getString(R.string.s_419))
        drawSubTabApi = ModelsHelpers.modelsSubTab(activity, getString(R.string.s_420))
        drawSubTabModel.setOnClickListener { host.onSwitchDrawSubPage(0) }
        drawSubTabApi.setOnClickListener { host.onSwitchDrawSubPage(1) }
        val drawSubBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.surface)
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        drawSubBar.addView(
            drawSubTabModel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        drawSubBar.addView(
            drawSubTabApi,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        drawModelPane = ModelsHelpers.modelsPane(activity, drawCard)
        drawApiPane = ModelsHelpers.modelsPane(activity, drawApiCard)
        val drawPane = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        drawPane.addView(drawSubBar, matchWrap(topMargin = 12))
        drawPane.addView(
            drawModelPane,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        drawPane.addView(
            drawApiPane,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        host.onRefreshDrawApiUi()

        // ================= 打标模型 =================
        val taggerCard = ui.sectionCard()
        taggerCard.addView(ui.pageTitle(getString(R.string.s_241)))
        taggerCard.addView(
            ui.hintText(getString(R.string.s_242)),
            matchWrap(topMargin = 4)
        )
        val tStatus = TextView(activity).apply {
            text = getString(R.string.s_245)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        taggerStatusTv = tStatus
        taggerCard.addView(tStatus, matchWrap(topMargin = 6))
        taggerOnnxBtn = ui.actionButton(getString(R.string.s_243)) { host.onPickTaggerOnnx() }
        taggerCard.addView(taggerOnnxBtn, matchWrap(topMargin = 8))
        taggerCsvBtn = ui.actionButton(getString(R.string.s_244)) { host.onPickTaggerCsv() }
        taggerCard.addView(taggerCsvBtn, matchWrap(topMargin = 8))
        taggerBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        taggerCard.addView(taggerBox, matchWrap(topMargin = 6))
        taggerLoadBtn = ui.actionButton(getString(R.string.s_254)) { host.onTaggerToggleClickRequested() }
        taggerCard.addView(taggerLoadBtn, matchWrap(topMargin = 8))

        // ---- 顶部常驻「运行方式」+ 页签 + 左右翻页 ----
        header.addView(card)
        root.addView(header)

        val tabBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_CARD)
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        modelsTabChat = ModelsHelpers.tabItem(activity, getString(R.string.s_076))
        modelsTabDraw = ModelsHelpers.tabItem(activity, getString(R.string.s_155))
        modelsTabTag = ModelsHelpers.tabItem(activity, getString(R.string.s_241))
        modelsTabChat.setOnClickListener { host.onSwitchModelsTab(0) }
        modelsTabDraw.setOnClickListener { host.onSwitchModelsTab(1) }
        modelsTabTag.setOnClickListener { host.onSwitchModelsTab(2) }
        for (t in listOf(modelsTabChat, modelsTabDraw, modelsTabTag)) {
            tabBar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabBar)

        modelsPager = ViewPager(activity).apply {
            adapter = ModelsPaneAdapter(
                listOf(chatPane, drawPane, ModelsHelpers.modelsPane(activity, taggerCard))
            )
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    // El highlight lo gestiona switchModelsTab
                }
            })
        }
        root.addView(
            modelsPager,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        host.onRefreshLoraUi()
        host.onRefreshDrawModels()
        host.onRefreshTaggerUi()
        host.onSwitchChatSubPage(0)
        host.onSwitchDrawSubPage(0)
        host.onSwitchModelsTab(0)
        return root
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
            // No-op: las vistas se reutilizan; nunca se destruyen
        }
    }
}
