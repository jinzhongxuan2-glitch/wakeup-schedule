package com.wakeup.schedule.core

import com.google.gson.Gson

/**
 * 远端更新清单（version.json）的纯逻辑解析，可单元测试。
 *
 * 示例：
 * ```json
 * {
 *   "versionCode": 4,
 *   "versionName": "1.2.1",
 *   "apkUrl": "https://github.com/<user>/<repo>/releases/latest/download/app-release.apk",
 *   "apkUrlMirror": "https://<第三方加速>/https://github.com/.../app-release.apk",
 *   "notes": "更新说明",
 *   "force": false
 * }
 * ```
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val apkUrl: String,
    /** 可选的备用下载地址：GitHub 直连不通时用（例如国内加速镜像） */
    val apkUrlMirror: String = "",
    val notes: String = "",
    val force: Boolean = false
)

object UpdateManifest {

    private val gson = Gson()

    /**
     * Gson 走反射，**会绕过 Kotlin 的默认值**（缺字段会直接给 null），
     * 所以这里先用全可空的 DTO 接，校验通过后再构造强类型的 [UpdateInfo]。
     */
    private data class Dto(
        val versionCode: Int? = null,
        val versionName: String? = null,
        val apkUrl: String? = null,
        val apkUrlMirror: String? = null,
        val notes: String? = null,
        val force: Boolean? = null
    )

    /** 解析失败或字段不完整时返回 null（调用方据此静默跳过更新检查） */
    fun parse(json: String): UpdateInfo? {
        val dto = runCatching { gson.fromJson(json, Dto::class.java) }.getOrNull() ?: return null
        val code = dto.versionCode ?: return null
        val url = dto.apkUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (code <= 0) return null

        return UpdateInfo(
            versionCode = code,
            versionName = dto.versionName?.trim().orEmpty(),
            apkUrl = url,
            apkUrlMirror = dto.apkUrlMirror?.trim().orEmpty(),
            notes = dto.notes.orEmpty(),
            force = dto.force ?: false
        )
    }

    fun isNewer(remote: UpdateInfo, localVersionCode: Int): Boolean =
        remote.versionCode > localVersionCode

    /**
     * 下载地址候选列表（按尝试顺序）。
     * 每个元素是 `显示名 to 地址`，供失败后切换来源重试用。
     */
    fun downloadCandidates(remote: UpdateInfo): List<Pair<String, String>> = buildList {
        add("GitHub 官方" to remote.apkUrl)
        if (remote.apkUrlMirror.isNotBlank() && remote.apkUrlMirror != remote.apkUrl) {
            add("镜像加速" to remote.apkUrlMirror)
        }
    }
}
