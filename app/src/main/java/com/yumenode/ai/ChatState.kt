// Yumenode AI · (c) 2026 visitor257 · MIT License
// ChatState: data classes for chat sessions, shared between MainActivity and SessionManager.
package com.yumenode.ai

/** One turn in a conversation thread. */
data class QaTurn(
    val user: String,
    var answer: String = "",
    var thought: String = "",
    /** 绘图轮次：生成图在本地的文件名（filesDir/chatimg/ 下），非绘图轮为 null */
    var image: String? = null,
    /** 用户这一轮发出的图片文件名（filesDir/chatimg/ 下），无图片为 null */
    var userImage: String? = null,
    /** 用户这一轮发出的文件名（| 分隔），无文件为 null */
    var userFile: String? = null,
) {
    /** 本轮文件的正文块（含表头与结尾指令）：进本次推理、不进对话历史 */
    var fileText: String? = null
    /** 本轮文件的原始内容（JSON：文件名/字节数/正文/是否截断），用于「重新生成」还原附件 */
    var fileStore: String? = null
}

/** 一个独立对话，拥有自己的完整历史。 */
class ChatSession(var id: Long, var title: String) {
    val turns = mutableListOf<QaTurn>()
}
