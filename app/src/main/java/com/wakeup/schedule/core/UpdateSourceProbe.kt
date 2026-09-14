package com.wakeup.schedule.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 单个下载源的测速采样 */
data class ProbeSample(
    val label: String,
    val url: String,
    val bytes: Long,
    val millis: Long
) {
    /** 字节/秒；采样失败时为 0 */
    val speedBps: Long get() = if (millis <= 0) 0 else bytes * 1000 / millis

    val failed: Boolean get() = bytes <= 0
}

/**
 * 下载源测速：**并发**探测各源吞吐，挑最快的那个再正式下载。
 *
 * 为什么需要：实测同一条通道速度会在几分钟内从 378 KB/s 掉到 4 KB/s，
 * 静态优先级（永远先试 CDN）经常会挑到一个当时很慢的源，让用户干等。
 * 探测窗口很短（默认 2.5 秒、单个源最多读 256 KB），并发跑完总共约 2.5 秒。
 *
 * 纯 JVM（只用 HttpURLConnection 与协程），决策逻辑可单元测试。
 */
object UpdateSourceProbe {

    /** 低于此速度视为「不可用」，宁可不选 */
    const val MIN_USABLE_BPS: Long = 20 * 1024L

    private const val WINDOW_MS = 2500L
    private const val MAX_BYTES = 256 * 1024

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 并发探测所有候选源。
     * @param candidates `显示名 to 地址`
     */
    suspend fun probeAll(
        candidates: List<Pair<String, String>>,
        windowMs: Long = WINDOW_MS,
        maxBytes: Int = MAX_BYTES
    ): List<ProbeSample> = coroutineScope {
        candidates.map { (label, url) ->
            async(Dispatchers.IO) { probeOne(label, url, windowMs, maxBytes) }
        }.awaitAll()
    }

    /**
     * 从探测结果中挑最快的源；都不达标（或全部失败）时返回 null，
     * 调用方据此退回「按默认顺序尝试」。
     */
    fun pickFastest(samples: List<ProbeSample>, minBps: Long = MIN_USABLE_BPS): ProbeSample? =
        samples.filter { !it.failed && it.speedBps >= minBps }
            .maxByOrNull { it.speedBps }

    /** 生成给用户看的测速摘要，例如：`CDN 320 KB/s ｜ 镜像 8 KB/s ｜ 官方 失败` */
    fun summary(samples: List<ProbeSample>): String =
        samples.joinToString(" ｜ ") { sample ->
            if (sample.failed) "${sample.label} 失败" else "${sample.label} ${fmtSpeed(sample.speedBps)}"
        }

    fun fmtSpeed(bps: Long): String = when {
        bps >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bps / 1024.0 / 1024.0)
        bps >= 1024 -> String.format(Locale.US, "%d KB/s", bps / 1024)
        else -> "$bps B/s"
    }

    private fun probeOne(label: String, url: String, windowMs: Long, maxBytes: Int): ProbeSample {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = (windowMs + 1500).toInt()
                instanceFollowRedirects = true
                // 只取开头一小段：支持 Range 的源直接返回分片，不支持的源我们读够就断开
                setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", UA)
            }
            val started = System.currentTimeMillis()
            var total = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (total < maxBytes) {
                    val remain = windowMs - (System.currentTimeMillis() - started)
                    if (remain <= 0) break
                    val n = try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        -1
                    }
                    if (n <= 0) break
                    total += n
                }
            }
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            ProbeSample(label, url, total, elapsed)
        } catch (e: Exception) {
            ProbeSample(label, url, 0, 1)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
