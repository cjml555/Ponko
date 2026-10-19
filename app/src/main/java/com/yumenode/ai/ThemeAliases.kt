// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Color-alias properties shared across UI classes.
 *
 * Previously copy-pasted (with identical `palette.<prop>` bodies) across
 * MainActivity (23 aliases), SettingsPage (4), ModelsPage (6), UiWidgets (6),
 * ChatImageHandler (3) and DownloadPage (4). This consolidates them into a single
 * source of truth; adding a new color means editing one interface + YumenodeTheme.
 *
 * Implementing classes supply `override val palette: YumenodeTheme`.
 * For live theme refresh (UiWidgets), the palette getter re-reads the provider.
 */
internal interface ThemeAliases {
    val palette: YumenodeTheme

    val C_PRIMARY get() = palette.primary
    val C_PRIMARY_SOFT get() = palette.primarySoft
    val C_BG get() = palette.bg
    val C_BG_ALT get() = palette.bgAlt
    val C_CARD get() = palette.surface
    val C_FIELD_BG get() = palette.fieldBg
    val C_SOFT_BTN get() = palette.softBtn
    val C_SOFT_BTN2 get() = palette.softBtn2
    val C_BORDER get() = palette.border
    val C_CHIP_BG get() = palette.chipBg
    val C_DIVIDER get() = palette.divider
    val C_DIVIDER_LINE get() = palette.dividerLine
    val C_TEXT get() = palette.text
    val C_SUBTEXT get() = palette.subText
    val C_HINT get() = palette.hint
    val C_THOUGHT_BG get() = palette.thoughtBg
    val C_THOUGHT_TEXT get() = palette.thoughtText
    val C_OK get() = palette.ok
    val C_WARN get() = palette.warn
    val C_ERR get() = palette.err
    val C_IDLE get() = palette.idle
    val C_ERR_TEXT get() = palette.errText
    val C_SCRIM get() = palette.scrim
}