package com.wakeup.schedule.update

import com.wakeup.schedule.BuildConfig
import com.wakeup.schedule.core.UpdateInfo
import com.wakeup.schedule.core.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查：启动时 GET 一个 version.json，与本地 versionCode 对比。
 *
 * 部署方式（任选其一）：
 * 1. GitHub 仓库里的 version.json（raw 地址），APK 放 Releases
 * 2. 任意静态托管（对象存储、自己的服务器）
 * 把下面的 UPDATE_CHECK_URL 改成你的地址即可。
 *
 * 原则：检查失败一律静默跳过，绝不阻塞或打扰 App 正常使用。
 */
object UpdateChecker {

    const val UPDATE_CHECK_URL =
        "https://raw.githubusercontent.com/jinzhongxuan2-glitch/wakeup-schedule/main/version.json"

    private const val TIMEOUT_MS = 6000

    /** 有新版本时返回远端清单；无更新、检查失败、或该版本已被忽略时返回 null */
    suspend fun check(ignoredVersionCode: Int = 0): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(UPDATE_CHECK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val remote = UpdateManifest.parse(body) ?: return@withContext null
                if (!UpdateManifest.isNewer(remote, BuildConfig.VERSION_CODE)) return@withContext null
                if (remote.versionCode == ignoredVersionCode) return@withContext null
                remote
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
