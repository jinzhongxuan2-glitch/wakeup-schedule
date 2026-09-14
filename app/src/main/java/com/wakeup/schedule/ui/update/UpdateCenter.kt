package com.wakeup.schedule.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/**
 * 应用内更新的完整交互流程。
 *
 * 设计原则（针对「点了没反应」的教训）：
 * 1. 不用广播等下载结果，改为**轮询** DownloadManager，进度与失败原因都拿得到
 * 2. 任何失败都必须显示可读原因，绝不静默返回
 * 3. 提供多条出路：换下载源重试、用浏览器下载、去授权安装权限
 * 4. 下载成功的包若没装成，下次进入可「继续安装」，不必重新下载
 */
@Composable
fun UpdateCenter(app: WakeUpApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = app.repository.prefs

    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dialogVisible by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableStateOf<AppUpdater.Progress?>(null) }
    var statusText by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var candidateIndex by remember { mutableIntStateOf(0) }
    var pendingInstall by remember { mutableStateOf<File?>(null) }

    // 启动检查：拉 version.json；同时看看上次有没有下好却没装成的包
    LaunchedEffect(Unit) {
        val ignored = prefs.ignoredVersionCode.first()
        // 记住上次成功的下载来源（默认镜像：实测比 GitHub 直连快 10 倍）
        candidateIndex = withContext(Dispatchers.IO) { prefs.updateSourceIndex.first() }
        info = UpdateChecker.check(ignored)
        pendingInstall = withContext(Dispatchers.IO) { AppUpdater.existingVerifiedApk(context) }
    }

    // 轮询下载状态（替代不可靠的下载完成广播）
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        while (true) {
            val state = withContext(Dispatchers.IO) { AppUpdater.query(context, id) }
            progress = state
            when (state) {
                is AppUpdater.Progress.Success -> {
                    val file = AppUpdater.apkFile(context)
                    // 带上远端声明的版本号一起校验：能挡住「镜像缓存返回旧版本」这种情况
                    val verify = withContext(Dispatchers.IO) {
                        AppUpdater.verifyApk(context, file, info?.versionCode)
                    }
                    downloadId = null
                    if (verify.isFailure) {
                        failure = verify.message
                    } else {
                        // 记住这个来源，下次优先用它
                        withContext(Dispatchers.IO) { prefs.setUpdateSourceIndex(candidateIndex) }
                        pendingInstall = file
                        statusText = "下载完成，正在调起安装…"
                        val err = AppUpdater.install(context, file)
                        if (err == null) {
                            dialogVisible = false
                        } else {
                            failure = err
                        }
                    }
                    break
                }
                is AppUpdater.Progress.Failed -> {
                    downloadId = null
                    failure = state.message
                    break
                }
                is AppUpdater.Progress.Running -> {
                    statusText = if (state.percent >= 0) {
                        "正在下载 ${state.percent}%（${mb(state.downloaded)} / ${mb(state.total)}）"
                    } else {
                        "正在下载…已接收 ${mb(state.downloaded)}"
                    }
                    delay(400)
                }
                is AppUpdater.Progress.Pending -> {
                    statusText = state.reason
                    delay(400)
                }
            }
        }
    }

    fun startDownload(index: Int) {
        val target = info ?: return
        val candidates = UpdateManifest.downloadCandidates(target)
        if (candidates.isEmpty()) {
            failure = "更新清单里没有可用的下载地址"
            return
        }
        val (label, url) = candidates[index.coerceIn(0, candidates.size - 1)]
        failure = null
        candidateIndex = index
        dialogVisible = true
        statusText = "正在从「$label」下载…"
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                try {
                    AppUpdater.enqueue(context, url)
                } catch (e: Exception) {
                    -1L
                }
            }
            if (id > 0) downloadId = id
            else failure = "无法开始下载（$label）：系统下载服务拒绝了这个地址，可改用浏览器下载"
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
                        startDownload(0)
                    }
                }) {
                    Text(if (pendingInstall != null) "立即安装" else "立即更新")
                }
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

    // ===== 下载 / 失败 / 安装对话框 =====
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
                        if (p is AppUpdater.Progress.Running && p.percent >= 0) {
                            LinearProgressIndicator(
                                progress = { p.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(statusText, fontSize = 13.sp, lineHeight = 19.sp)
                    } else {
                        Text(err, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "可以依次尝试：换下载源、改用浏览器下载；" +
                                "若手机已开 VPN，切到 GitHub 官方源通常也能通。" +
                                "若是权限问题，去设置里允许本应用「安装未知应用」后再点重试。",
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
                        Button(
                            onClick = { startDownload(if (candidateIndex == 0) 1 else 0) },
                            enabled = (info?.let { UpdateManifest.downloadCandidates(it).size } ?: 0) > 1,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (candidateIndex == 0) "换镜像源重试" else "改用 GitHub 直连")
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                val url = info?.let { UpdateManifest.downloadCandidates(it)
                                    .getOrNull(candidateIndex)?.second } ?: info?.apkUrl.orEmpty()
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
                    TextButton(onClick = {
                        downloadId?.let { AppUpdater.cancel(context, it) }
                        downloadId = null
                        dialogVisible = false
                        failure = null
                    }) { Text("取消下载") }
                }
            },
            dismissButton = {
                if (err != null) {
                    TextButton(onClick = {
                        startDownload(candidateIndex)
                    }) { Text("重试") }
                }
            }
        )
    }
}

private fun mb(bytes: Long): String =
    if (bytes <= 0) "0 MB" else String.format("%.1f MB", bytes / 1024.0 / 1024.0)
