// Yumenode AI · (c) 2026 visitor257 · MIT License
// CrashHandler: Java/ART 层异常捕获（含 UnsatisfiedLinkError），写进文件供次启动查看。
package com.yumenode.ai

import android.content.Context
import java.io.File

class CrashHandler(private val context: Context) {

    /** 安装全局未捕获异常钩子。建议在 Application#onCreate 或 Activity#onCreate 最前调用。 */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val dump = "${java.util.Date()}\nthread=${t.name}\n" + android.util.Log.getStackTraceString(e)
            // 内部目录（界面里能读回）
            try {
                File(context.filesDir, "crash.txt").writeText(dump)
            } catch (_: Exception) {}
            // 外部目录（/sdcard/Android/data/com.yumenode.ai/files/crash.txt，插 USB 用 MTP 就能取）
            try {
                val extDir = context.getExternalFilesDir(null)
                if (extDir != null) {
                    File(extDir, "crash.txt").writeText(dump)
                }
            } catch (_: Exception) {}
        }
    }

    /** 读取并清空崩溃日志（供模型页展示） */
    fun takeCrashLog(): String? {
        val f = File(context.filesDir, "crash.txt")
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return s
    }
}