// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * Centralized theme management for Yumenode AI app.
 *
 * Encapsulates theme state, palette resolution, and system bar coloring.
 * The Activity delegates to this manager for all theme-related operations.
 */
class ThemeManager(
    private val context: Context,
    private val onThemeApplied: () -> Unit = {}
) {

    /**
     * Current theme mode: 0 = follow system, 1 = light, 2 = dark.
     */
    var themeMode: Int = 0
        private set

    /**
     * Current palette derived from the selected theme mode.
     */
    var pal: YumenodeTheme = YumenodeTheme.light()
        private set

    // ── Color aliases from palette ──
    val primary: Int get() = pal.primary
    val primarySoft: Int get() = pal.primarySoft
    val bg: Int get() = pal.bg
    val bgAlt: Int get() = pal.bgAlt
    val surface: Int get() = pal.surface
    val fieldBg: Int get() = pal.fieldBg
    val softBtn: Int get() = pal.softBtn
    val softBtn2: Int get() = pal.softBtn2
    val border: Int get() = pal.border
    val chipBg: Int get() = pal.chipBg
    val divider: Int get() = pal.divider
    val dividerLine: Int get() = pal.dividerLine
    val text: Int get() = pal.text
    val subText: Int get() = pal.subText
    val hint: Int get() = pal.hint
    val thoughtBg: Int get() = pal.thoughtBg
    val thoughtText: Int get() = pal.thoughtText
    val statusBar: Int get() = pal.statusBar
    val navBar: Int get() = pal.navBar
    val ok: Int get() = pal.ok
    val warn: Int get() = pal.warn
    val err: Int get() = pal.err
    val idle: Int get() = pal.idle
    val errText: Int get() = pal.errText
    val scrim: Int get() = pal.scrim

    // ── Palette accessors for SettingsHost interface ──
    val surfaceAlt get() = bgAlt
    val card get() = surface

    // ── Theme application ──
    /**
     * Apply the current theme mode, resolving the palette and triggering UI rebuild.
     */
    fun applyThemeInPlace() {
        pal = YumenodeTheme.resolve(context, themeMode)
        onThemeApplied()
    }

    /**
     * Handle configuration changes (e.g., system dark mode toggle).
     * Returns true if theme was changed.
     */
    fun onConfigurationChanged(newConfig: Configuration): Boolean {
        if (themeMode != 0) return false
        val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (dark != pal.isDark) {
            applyThemeInPlace()
            return true
        }
        return false
    }

    /** Load theme mode from preferences. */
    fun loadThemeMode(prefs: SharedPreferences) {
        themeMode = prefs.getInt("themeMode", 0)
    }

    /** Save theme mode to preferences. */
    fun saveThemeMode(prefs: SharedPreferences, mode: Int) {
        prefs.edit().putInt("themeMode", mode).apply()
        themeMode = mode
    }

    /** Set theme mode, persist to prefs, and apply it. */
    fun setThemeMode(context: Context, mode: Int) {
        themeMode = mode
        val prefs = context.getSharedPreferences("ponko_prefs", 0)
        prefs.edit().putInt("themeMode", mode).apply()
        applyThemeInPlace()
    }

    companion object {
        const val THEME_MODE_DEFAULT = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2
    }
}