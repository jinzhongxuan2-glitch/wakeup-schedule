package com.wakeup.schedule.core

import java.util.Base64

/**
 * 分享口令编解码："WKUP:" + Base64(JSON)。
 * 用 java.util.Base64（Android API 26+ 与 JVM 单测均可用）。
 */
object ShareCodec {

    const val PREFIX = "WKUP:"

    fun encode(json: String): String =
        PREFIX + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    /** 解码，非法口令抛 IllegalArgumentException */
    fun decode(code: String): String {
        val trimmed = code.trim()
        require(trimmed.startsWith(PREFIX)) { "不是有效的课表分享口令" }
        val body = trimmed.removePrefix(PREFIX)
        return try {
            String(Base64.getDecoder().decode(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("口令内容损坏，无法解析")
        }
    }
}
