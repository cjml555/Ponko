// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.content.res.Configuration

/**
 * 应用配色表：浅色 / 深色两套。
 *
 * 界面里所有颜色都从这里取（MainActivity 的 C_* 与 DrawPage 的 pal），
 * 所以新增控件时不要再写死色值，否则深色模式下会留下白块。
 *
 * 主题模式存在 ponko 偏好里：0=跟随系统 1=浅色 2=深色（切换后 Activity 重建生效）。
 */
data class YumenodeTheme(
    val bg: Int,             // 页面/滚动区背景
    val bgAlt: Int,          // 页面背景（略深一点的那种）
    val surface: Int,        // 卡片、顶栏、输入栏等「白面」
    val fieldBg: Int,        // 输入框、预览区等凹陷面
    val softBtn: Int,        // 次级按钮 / 缩略图底
    val softBtn2: Int,       // 次级按钮（描边款）
    val border: Int,         // 描边
    val chipBg: Int,         // 未选中的条目底
    val divider: Int,        // 卡片内细分隔线（半透明）
    val dividerLine: Int,    // 抽屉等处的实色 1px 分隔线
    val text: Int,           // 主文字
    val subText: Int,        // 次要文字
    val hint: Int,           // 输入框占位
    val primary: Int,        // 主色（按钮底 / 强调文字 / 顶栏）
    val primarySoft: Int,    // 主色的浅底（选中态）
    val onPrimary: Int,      // 主色上的文字
    val thoughtBg: Int,      // 思考过程气泡底
    val thoughtText: Int,    // 思考过程文字
    val ok: Int,
    val warn: Int,
    val err: Int,
    val idle: Int,
    val errText: Int,        // 错误正文（比 err 深一点）
    val stop: Int,           // 生成中「停止」按钮
    val mediaBg: Int,        // 图片缩略图底
    val scrim: Int,          // 抽屉遮罩
    val switchTrack: Int,    // 滑块开关：轨道底
    val switchThumb: Int,    // 滑块开关：滑块
    val statusBar: Int,      // 状态栏（顶栏同色）
    val navBar: Int,         // 导航栏
    val isDark: Boolean,
) {
    /** Aplica un acento de la [AccentPalette], recalculando los derivados. */
    fun withAccent(index: Int): YumenodeTheme {
        val i = index.coerceIn(0, AccentPalette.size - 1)
        val c = if (isDark) AccentPalette.dark[i] else AccentPalette.light[i]
        val tint = if (isDark) blend(c, bg, 0.18f) else blend(c, 0xFFFFFFFF.toInt(), 0.12f)
        val on = if (luminance(c) > 0.62f) 0xFF001018.toInt() else 0xFFFFFFFF.toInt()
        return copy(
            primary = c,
            primarySoft = tint,
            onPrimary = on,
            switchThumb = c,
            statusBar = if (isDark) statusBar else c,
        )
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    companion object {
        fun light() = YumenodeTheme(
            bg = 0xFFF2F4F8.toInt(),
            bgAlt = 0xFFF5F6F8.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            fieldBg = 0xFFF2F3F5.toInt(),
            softBtn = 0xFFF3F5F9.toInt(),
            softBtn2 = 0xFFF6F7FA.toInt(),
            border = 0xFFDBE0EA.toInt(),
            chipBg = 0xFFF7F8FB.toInt(),
            divider = 0x1F000000,
            dividerLine = 0xFFEEF0F5.toInt(),
            text = 0xFF1C202A.toInt(),
            subText = 0xFF7A8394.toInt(),
            hint = 0xFFAAAAAA.toInt(),
            primary = 0xFF2F6BFF.toInt(),
            primarySoft = 0xFFEDF1FF.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            thoughtBg = 0xFFF5F7FB.toInt(),
            thoughtText = 0xFF6E7686.toInt(),
            ok = 0xFF22B26E.toInt(),
            warn = 0xFFF59E0B.toInt(),
            err = 0xFFE55252.toInt(),
            idle = 0xFF9AA4B5.toInt(),
            errText = 0xFFCC3333.toInt(),
            stop = 0xFFD9534F.toInt(),
            mediaBg = 0xFFEDEDED.toInt(),
            scrim = 0x66000000,
            switchTrack = 0xFFF1F3F7.toInt(),
            switchThumb = 0xFFFFFFFF.toInt(),
            statusBar = 0xFF2F6BFF.toInt(),
            navBar = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        fun dark() = YumenodeTheme(
            // ── 1. Fondos: Azul Noche Absoluto (#020814/#0A0D1A) + Azul Profundo de Red (#1A237E) ──
            bg = 0xFF020814.toInt(),              // página / scroll (fondo más oscuro)
            bgAlt = 0xFF0A0D1A.toInt(),           // fondo secundario
            surface = 0xFF1A237E.toInt(),         // tarjetas, top bar, input bar
            fieldBg = 0xFF0D1733.toInt(),         // inputs / previews cóncavos
            softBtn = 0xFF121E42.toInt(),         // botón secundario (sólido)
            softBtn2 = 0xFF14224A.toInt(),        // botón secundario (outline)
            border = 0xFF2E4A8C.toInt(),          // bordes / strokes (tinte del Azul Profundo)
            chipBg = 0xFF111F44.toInt(),          // chip no seleccionado
            divider = 0x20FFFFFF.toInt(),         // divisor semitransparente
            dividerLine = 0xFF1A2752.toInt(),     // línea 1px
            thoughtBg = 0xFF121E42.toInt(),       // burbuja de razonamiento
            mediaBg = 0xFF0A0D1A.toInt(),         // base de miniaturas
            scrim = 0x99020814.toInt(),           // overlay del drawer
            // ── 2. Texto ──
            text = 0xFFE6E9EF.toInt(),            // principal
            subText = 0xFF8A96B4.toInt(),          // secundario
            hint = 0xFF6A7DA9.toInt(),            // placeholder
            thoughtText = 0xFF9BA7C4.toInt(),     // texto de razonamiento
            errText = 0xFFFF8A8A.toInt(),         // texto de error
            // ── 3. Acento: Cyan eléctrico (#00F0FF), Magenta neón (#FF007F)
            //    (Violeta #9D4EDD está reservado para glow gráfico — futura capa visual) ──
            primary = 0xFF00F0FF.toInt(),         // cyan — acento / top bar / botones
            primarySoft = 0xFF002A33.toInt(),     // tinte muy oscuro de cyan (seleccionado)
            onPrimary = 0xFF001018.toInt(),       // texto sobre cyan (casi negro)
            switchThumb = 0xFF00F0FF.toInt(),     // thumb del switch = cyan
            switchTrack = 0xFF0D1733.toInt(),     // track del switch
            stop = 0xFFFF007F.toInt(),            // magenta — energía / punto caliente
            statusBar = 0xFF020814.toInt(),       // barra de estado (inmersiva)
            navBar = 0xFF020814.toInt(),          // barra de navegación
            // semáforos funcionales (reconocibles)
            ok = 0xFF35C57F.toInt(),
            warn = 0xFFF0A22E.toInt(),
            err = 0xFFFF6B6B.toInt(),
            idle = 0xFF7E8798.toInt(),
            isDark = true,
        )

        /** OLED: negro puro (#000000) para paneles OLED; ahorra batería y evita el gris elevado. */
        fun oled() = dark().copy(
            bg = 0xFF000000.toInt(),
            bgAlt = 0xFF000000.toInt(),
            surface = 0xFF000000.toInt(),
            fieldBg = 0xFF080808.toInt(),
            softBtn = 0xFF0E0E0E.toInt(),
            softBtn2 = 0xFF101010.toInt(),
            border = 0xFF2A2A2A.toInt(),
            chipBg = 0xFF0A0A0A.toInt(),
            divider = 0x14FFFFFF,
            dividerLine = 0xFF1A1A1A.toInt(),
            thoughtBg = 0xFF0A0A0A.toInt(),
            mediaBg = 0xFF000000.toInt(),
            scrim = 0xCC000000.toInt(),
            switchTrack = 0xFF0A0A0A.toInt(),
            statusBar = 0xFF000000.toInt(),
            navBar = 0xFF000000.toInt(),
        )

        /** 主题模式：0=跟随系统 1=浅色 2=深色 3=OLED */
        fun resolve(ctx: Context, mode: Int): YumenodeTheme = when (mode) {
            1 -> light()
            2 -> dark()
            3 -> oled()
            else -> if (systemDark(ctx)) dark() else light()
        }

        fun systemDark(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/** Acentos seleccionables (el índice se guarda en prefs como "accentIndex"). */
object AccentPalette {
    val keys = arrayOf("cyan", "magenta", "lime", "violet", "amber")
    val dark = intArrayOf(
        0xFF00F0FF.toInt(), 0xFFFF2E9A.toInt(), 0xFF7CFF6B.toInt(),
        0xFFA78BFA.toInt(), 0xFFFFC24B.toInt(),
    )
    val light = intArrayOf(
        0xFF007C8C.toInt(), 0xFFC4146A.toInt(), 0xFF2E8B3C.toInt(),
        0xFF6D4AE0.toInt(), 0xFFA86A00.toInt(),
    )
    val size get() = keys.size
}
