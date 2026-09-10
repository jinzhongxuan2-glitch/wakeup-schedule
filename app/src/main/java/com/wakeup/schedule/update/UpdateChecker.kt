package com.wakeup.schedule.update

import com.google.gson.Gson
import com.wakeup.schedule.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 远端版本信息（version.json 的结构） */
data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
    /** 是否强制更新（预留字段） */
    val force: Boolean = false
)

/**
 * 应用内更新检查：启动时 GET 一个 version.json，与本地 versionCode 对比。
 *
 * 部署方式（任选其一）：
 * 1. GitHub Releases：把 version.json 放到仓库可公开访问的 raw 地址 / pages
 * 2. 任意静态托管（对象存储、自己的服务器）
 * 然后把下面的 UPDATE_CHECK_URL 改成你的地址即可。
 */
object UpdateChecker {

    const val UPDATE_CHECK_URL =
        "https://raw.githubusercontent.com/jinzhongxuan2-glitch/wakeup-schedule/main/version.json"

    private const val TIMEOUT_MS = 5000

    /** 有更新时返回远端版本信息；无更新或检查失败返回 null */
    suspend fun check(): RemoteVersion? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(UPDATE_CHECK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // 防缓存
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val remote = Gson().fromJson(body, RemoteVersion::class.java) ?: return@withContext null
                if (remote.versionCode > BuildConfig.VERSION_CODE) remote else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
