package com.wakeup.schedule.update

import com.wakeup.schedule.BuildConfig
import com.wakeup.schedule.core.UpdateInfo
import com.wakeup.schedule.core.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查：拉取 version.json，与本地 versionCode 对比。
 *
 * 为什么要有**多个地址**：
 * `raw.githubusercontent.com` 在国内是间歇性可达的（实测连测三次出现 200 / 200 / 连不上），
 * 只用它会导致「有时能检查到更新、有时完全没有反应」。所以按顺序尝试多个源，
 * 第一个「能取到内容」的即返回；只有取不到（网络/解析失败）才继续下一个。
 *
 * 注意：检查失败一律静默跳过，绝不能影响 App 正常使用。
 */
object UpdateChecker {

    private const val OWNER = "jinzhongxuan2-glitch"
    private const val REPO = "wakeup-schedule"
    private const val RAW = "https://raw.githubusercontent.com/$OWNER/$REPO/main/version.json"

    /** 首选地址（对外保留常量，便于排查时手工验证） */
    const val UPDATE_CHECK_URL = RAW

    /** 依次尝试的清单地址：原生 raw → jsDelivr CDN → 加速代理 */
    private val MANIFEST_URLS = listOf(
        RAW,
        "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@main/version.json",
        "https://ghfast.top/$RAW"
    )

    private const val TIMEOUT_MS = 6000

    /** 有新版本时返回远端清单；无更新、检查失败、或该版本已被忽略时返回 null */
    suspend fun check(ignoredVersionCode: Int = 0): UpdateInfo? = withContext(Dispatchers.IO) {
        for (url in MANIFEST_URLS) {
            val json = runCatching { fetch(url) }.getOrNull() ?: continue
            val remote = UpdateManifest.parse(json) ?: continue
            // 拿到了有效清单就做判断：有更新才返回，否则结束（不必再试其他源）
            if (!UpdateManifest.isNewer(remote, BuildConfig.VERSION_CODE)) return@withContext null
            if (remote.versionCode == ignoredVersionCode) return@withContext null
            return@withContext remote
        }
        null
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
