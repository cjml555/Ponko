// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Shared request codes, preference keys and agent caps.
 *
 * Single source of truth — previously duplicated with identical values across
 * MainActivity, VisionManager, DownloadPage, SettingsPage and ChatParams.
 * All are internal to the module; none are user-facing branding.
 */

// ---- Activity result request codes ----
internal const val REQ_PICK_MODEL = 1001
internal const val REQ_PICK_DRAW_MODEL = 1002
internal const val REQ_PICK_LORA = 1003
internal const val REQ_TAGGER_ONNX = 1004
internal const val REQ_TAGGER_CSV = 1005
internal const val REQ_TAKE_PHOTO = 1006
// REQ_PICK_CHAT_IMAGE and REQ_PICK_FILE now handled by AttachmentHandler
internal const val REQ_PICK_MMPROJ = 1008
internal const val REQ_PERM_STORAGE = 1009
/** Android 13+ 常驻通知权限（对话 API 用） */
internal const val REQ_NOTIF = 1020

// ---- Preference keys (collapsible sections + chat think) ----
internal const val KEY_UI_SETTINGS_OPEN = "uiSettingsOpen"
internal const val KEY_UI_ABOUT_OPEN = "uiAboutOpen"
/** Max images the tagger may see per turn (agent loop cap). */
internal const val MAX_TAG_ROUNDS = 2
// ---- think-mode toggle (global pref, model-independent) ----
internal const val KEY_CHAT_THINK = "chatThink"
