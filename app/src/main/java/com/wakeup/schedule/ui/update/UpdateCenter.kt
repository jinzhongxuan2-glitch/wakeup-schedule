package com.wakeup.schedule.ui.update

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.core.UpdateInfo
import com.wakeup.schedule.core.UpdateManifest
import com.wakeup.schedule.update.AppUpdater
import com.wakeup.schedule.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 应用内更新的完整交互流程。
 *
 * 设计原则（逐条来自实测踩坑）：
 * 1. 不依赖下载完成广播，**轮询** DownloadManager（Android 13+ 广播收不到）
 * 2. 进度条**自绘**：Material3 的 LinearProgressIndicator 在 0% 时会在轨道右端画
 *    「停止指示点」，看起来像进度从右往左，自己画就完全可控
 * 3. 显示**实时速度**与已下载/总大小，让用户知道是在下还是卡住了
 * 4. **看门狗自动换源**：停滞或速度过低时自动切到下一个源（CDN → 镜像 → 官方）
 * 5. 任何失败都必须给出可读原因与出路（换源 / 浏览器下载 / 去授权）
 */
@Composable
fun UpdateCenter(app: WakeUpApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = app.repository.prefs

    /** 停滞判定：8 秒没有任何字节增长 */
    val stallMs = 8_000L
    /** 速度过低判定：开始 12 秒后仍低于 15 KB/s */
    val slowGraceMs = 12_000L
    val slowBps = 15 * 1024L

    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dialogVisible by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableStateOf<AppUpdater.Progress?>(null) }
    var statusText by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var candidateIndex by remember { mutableIntStateOf(0) }
    var pendingInstall by remember { mutableStateOf<File?>(null) }
    var speedBps by remember { mutableLongStateOf(0L) }
    var sourceLabel by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val ignored = prefs.ignoredVersionCode.first()
        candidateIndex = withContext(Dispatchers.IO) { prefs.updateSourceIndex.first() }
        info = UpdateChecker.check(ignored)
        pendingInstall = withContext(Dispatchers.IO) { AppUpdater.existingVerifiedApk(context) }
    }

    fun candidates(): List<Pair<String, String>> =
        info?.let { UpdateManifest.downloadCandidates(it) }.orEmpty()

    fun startDownload(index: Int) {
        val list = candidates()
        if (list.isEmpty()) {
            failure = "更新清单里没有可用的下载地址"
            return
        }
        val realIndex = index.coerceIn(0, list.size - 1)
        val (label, url) = list[realIndex]
        candidateIndex = realIndex
        sourceLabel = label
        failure = null
        speedBps = 0
        switching = false
        dialogVisible = true
        statusText = "正在连接「$label」…"
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                try {
                    AppUpdater.enqueue(context, url)
                } catch (e: Exception) {
                    -1L
                }
            }
            if (id > 0) {
                downloadId = id
            } else {
                failure = "无法开始下载（$label）：系统下载服务拒绝了该地址，可改用浏览器下载"
            }
        }
    }

    /** 当前源太慢/停滞/失败 → 自动切下一个源 */
    fun failoverToNext(reason: String) {
        val list = candidates()
        val oldLabel = sourceLabel
        downloadId?.let { AppUpdater.cancel(context, it) }
        downloadId = null
        if (candidateIndex + 1 >= list.size) {
            failure = "所有下载源都不理想（$reason）。\n" +
                "建议：点「用浏览器下载」；或开启 VPN 后选 GitHub 官方源重试。"
            speedBps = 0
            return
        }
        val nextIndex = candidateIndex + 1
        val nextLabel = list[nextIndex].first
        startDownload(nextIndex)
        // startDownload 内部会重置 switching，这里在其后设置，保证提示可见
        switching = true
        statusText = "「$oldLabel」$reason，已自动切换到「$nextLabel」…"
    }

    // 轮询下载状态 + 速度采样 + 看门狗
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        var lastBytes = 0L
        var lastChangeAt = startedAt
        var lastSampleAt = startedAt
        var lastSampleBytes = 0L

        while (true) {
            val state = withContext(Dispatchers.IO) { AppUpdater.query(context, id) }
            progress = state
            val now = System.currentTimeMillis()

            when (state) {
                is AppUpdater.Progress.Success -> {
                    val file = AppUpdater.apkFile(context)
                    val verify = withContext(Dispatchers.IO) {
                        AppUpdater.verifyApk(context, file, info?.versionCode)
                    }
                    downloadId = null
                    if (verify.isFailure) {
                        // 校验失败（可能是镜像缓存旧版/被替换）→ 换源再试，并保留原因提示
                        if (candidateIndex + 1 < candidates().size) {
                            val reason = verify.message
                            failoverToNext("校验未通过：$reason")
                        } else {
                            failure = verify.message
                        }
                    } else {
                        withContext(Dispatchers.IO) { prefs.setUpdateSourceIndex(candidateIndex) }
                        pendingInstall = file
                        statusText = "下载完成，正在调起安装…"
                        val err = AppUpdater.install(context, file)
                        if (err == null) dialogVisible = false else failure = err
                    }
                    break
                }

                is AppUpdater.Progress.Failed -> {
                    downloadId = null
                    // 失败也尝试换源（除非已经是最后一个），并保留失败原因
                    if (candidateIndex + 1 < candidates().size) {
                        failoverToNext("下载失败：${state.message}")
                    } else {
                        failure = state.message
                    }
                    break
                }

                is AppUpdater.Progress.Running -> {
                    if (state.downloaded != lastBytes) {
                        lastBytes = state.downloaded
                        lastChangeAt = now
                    }
                    if (now - lastSampleAt >= 1200) {
                        speedBps = ((state.downloaded - lastSampleBytes) * 1000) / (now - lastSampleAt)
                        lastSampleAt = now
                        lastSampleBytes = state.downloaded
                    }
                    statusText = buildString {
                        append("来源：$sourceLabel")
                        if (state.total > 0) {
                            append("　${fmtMb(state.downloaded)} / ${fmtMb(state.total)}")
                        } else {
                            append("　已接收 ${fmtMb(state.downloaded)}")
                        }
                        if (speedBps > 0) append("　${fmtSpeed(speedBps)}")
                    }

                    val stalled = now - lastChangeAt > stallMs
                    val tooSlow = now - startedAt > slowGraceMs && speedBps in 1 until slowBps
                    if (stalled || tooSlow) {
                        failoverToNext(if (stalled) "已停滞" else "速度过慢")
                        break
                    }
                    delay(500)
                }

                is AppUpdater.Progress.Pending -> {
                    statusText = "来源：$sourceLabel　等待开始…"
                    if (now - startedAt > 15_000) {
                        failoverToNext("无法开始传输")
                        break
                    }
                    delay(600)
                }
            }
        }
    }

    // ===== 版本提示 =====
    val current = info
    if (current != null && !dialogVisible) {
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text("发现新版本 ${current.versionName.ifBlank { current.versionCode.toString() }}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        current.notes.ifBlank { "有新版本可用。下载完成后会自动调起安装，数据不会丢失。" },
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    if (pendingInstall != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "已有一个下载完成的安装包，可直接安装，无需重新下载。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ready = pendingInstall
                    if (ready != null) {
                        val err = AppUpdater.install(context, ready)
                        if (err == null) info = null else {
                            failure = err
                            dialogVisible = true
                        }
                    } else {
                        startDownload(candidateIndex)
                    }
                }) { Text(if (pendingInstall != null) "立即安装" else "立即更新") }
            },
            dismissButton = {
                Row {
                    if (!current.force) {
                        TextButton(onClick = {
                            scope.launch { prefs.setIgnoredVersionCode(current.versionCode) }
                            info = null
                        }) { Text("忽略此版本") }
                    }
                    TextButton(onClick = { info = null }) { Text("下次再说") }
                }
            }
        )
    }

    // ===== 下载 / 失败 / 安装 =====
    if (dialogVisible) {
        val err = failure
        AlertDialog(
            onDismissRequest = { /* 强制走按钮，避免误触丢失状态 */ },
            title = {
                Text(
                    when {
                        err != null -> "更新未完成"
                        pendingInstall != null -> "准备安装"
                        else -> "正在下载更新"
                    }
                )
            },
            text = {
                Column {
                    if (err == null) {
                        val p = progress
                        val percent = if (p is AppUpdater.Progress.Running && p.total > 0) {
                            (p.downloaded * 100 / p.total).toInt()
                        } else 0
                        UpdateProgressBar(
                            percent = percent,
                            indeterminate = p !is AppUpdater.Progress.Running || p.total <= 0
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(statusText, fontSize = 13.sp, lineHeight = 19.sp)
                        if (switching) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "提示：国内建议「CDN 加速」；若开着 VPN，可切「GitHub 官方」。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    } else {
                        Text(err, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "国内网络下「CDN 加速」通常最快；若开着 VPN，可试「GitHub 官方」；" +
                                "实在不行点「用浏览器下载」。若是权限问题，去设置允许本应用「安装未知应用」后重试。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                    }
                }
            },
            confirmButton = {
                if (err != null) {
                    Column(Modifier.fillMaxWidth()) {
                        val list = candidates()
                        if (list.size > 1) {
                            Button(
                                onClick = { startDownload((candidateIndex + 1) % list.size) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("换源重试（下一个：${list[(candidateIndex + 1) % list.size].first}）")
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                val url = candidates().getOrNull(candidateIndex)?.second
                                    ?: info?.apkUrl.orEmpty()
                                AppUpdater.openInBrowser(context, url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("用浏览器下载") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { AppUpdater.openInstallPermissionSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("打开「安装未知应用」设置") }
                    }
                } else {
                    Row {
                        val list = candidates()
                        if (list.size > 1) {
                            TextButton(onClick = { startDownload((candidateIndex + 1) % list.size) }) {
                                Text("换源")
                            }
                        }
                        TextButton(onClick = {
                            downloadId?.let { AppUpdater.cancel(context, it) }
                            downloadId = null
                            dialogVisible = false
                            failure = null
                            speedBps = 0
                        }) { Text("取消下载") }
                    }
                }
            },
            dismissButton = {
                if (err != null) {
                    TextButton(onClick = { startDownload(candidateIndex) }) { Text("重试") }
                }
            }
        )
    }
}

/**
 * 自绘进度条：明确从左往右填充。
 * 不用 Material3 的 LinearProgressIndicator —— 它在 0% 时会在轨道右端画一个
 * 「停止指示点」，视觉上像进度从右往左，容易误导。
 */
@Composable
private fun UpdateProgressBar(percent: Int, indeterminate: Boolean, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor)
    ) {
        if (indeterminate) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val trackWidth = maxWidth
                val transition = rememberInfiniteTransition(label = "indeterminate")
                val fraction by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1100, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "slide"
                )
                Box(
                    Modifier
                        .offset(x = trackWidth * fraction * 0.7f)
                        .width(trackWidth * 0.3f)
                        .fillMaxHeight()
                        .background(fillColor)
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((percent.coerceIn(0, 100)) / 100f)
                    .background(fillColor)
            )
        }
    }
}

private fun fmtMb(bytes: Long): String =
    if (bytes <= 0) "0 MB" else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

private fun fmtSpeed(bps: Long): String = when {
    bps >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bps / 1024.0 / 1024.0)
    bps >= 1024 -> String.format(Locale.US, "%d KB/s", bps / 1024)
    else -> "$bps B/s"
}
