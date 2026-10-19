// Yumenode AI · (c) 2026 visitor257 · MIT License
// SessionManager: handles sessions.json persistence for chat conversations.
// Decoupled from the UI — just load/save JSON, keep sessions list.
package com.yumenode.ai

import android.content.Context
import android.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionManager(private val context: Context) {

    private val sessions = mutableListOf<ChatSession>()
    private var current: ChatSession? = null

    fun load() {
        sessions.clear()
        var idx = 0
        try {
            val f = File(context.filesDir, "sessions.json")
            if (f.exists()) {
                val root = JSONObject(f.readText())
                idx = root.optInt("current", 0)
                val arr = root.optJSONArray("sessions")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val s = ChatSession(
                            o.optLong("id", System.currentTimeMillis() + i),
                            o.optString("title"),
                        )
                        val ts = o.optJSONArray("turns")
                        if (ts != null) {
                            for (j in 0 until ts.length()) {
                                val t = ts.getJSONObject(j)
                                val turn = QaTurn(
                                    user = t.nextLine("u"),
                                    answer = t.nextLine("a"),
                                    thought = t.nextLine("th"),
                                    image = t.nextLine("img").ifEmpty { null },
                                    userImage = t.nextLine("uimg").ifEmpty { null },
                                    userFile = t.nextLine("ufile").ifEmpty { null },
                                )
                                // 文件正文也读回来，这样重启后「重新生成」也能带上文件
                                turn.fileText = t.nextLine("utext").ifEmpty { null }
                                turn.fileStore = t.nextLine("fstore").ifEmpty { null }
                                s.turns.add(turn)
                            }
                        }
                        sessions.add(s)
                    }
                }
            }
        } catch (_: Throwable) {}
        if (sessions.isEmpty()) sessions.add(ChatSession(System.currentTimeMillis(), "New chat"))
        current = sessions.getOrElse(idx) { sessions.first() }
    }

    fun save() {
        try {
            val arr = JSONArray()
            for (s in sessions) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("title", s.title)
                val ts = JSONArray()
                for (t in s.turns) {
                    val tObj = JSONObject()
                    tObj.put("u", t.user)
                    tObj.put("a", t.answer)
                    tObj.put("th", t.thought)
                    tObj.put("img", t.image ?: "")
                    tObj.put("uimg", t.userImage ?: "")
                    tObj.put("ufile", t.userFile ?: "")
                    tObj.put("utext", t.fileText ?: "")
                    tObj.put("fstore", t.fileStore ?: "")
                    ts.put(tObj)
                }
                o.put("turns", ts)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("current", sessions.indexOf(current))
            root.put("sessions", arr)
            File(context.filesDir, "sessions.json").writeText(root.toString())
        } catch (_: Throwable) {}
    }

    fun allSessions(): List<ChatSession> = sessions.toList()
    fun currentSession(): ChatSession? = current
    fun setCurrent(s: ChatSession) { current = s }
    fun addSession(s: ChatSession) { sessions.add(s); current = s }
    fun removeSession(s: ChatSession) {
        val idx = sessions.indexOf(s)
        if (idx >= 0) {
            sessions.removeAt(idx)
            if (current == s) {
                current = sessions.getOrElse(idx) { sessions.firstOrNull() }
            }
        }
    }

    /** Sync MainActivity's sessions/current into SessionManager before saving. */
    fun syncFrom(mainActivitySessions: List<ChatSession>, mainActivityCurrent: ChatSession?) {
        sessions.clear()
        sessions.addAll(mainActivitySessions)
        current = mainActivityCurrent
    }

    /** Helper to get a string from JSONObject, returning default if missing. */
    private fun JSONObject.nextLine(key: String): String = if (this.has(key)) this.optString(key) else ""
}