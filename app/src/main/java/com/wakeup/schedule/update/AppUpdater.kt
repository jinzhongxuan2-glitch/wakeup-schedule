package com.wakeup.schedule.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内更新：下载 APK 并调起系统安装器。
 *
 * 刻意**不依赖 `ACTION_DOWNLOAD_COMPLETE` 广播**：
 * 该广播由跨进程的系统组件发出，Android 13+ 用非导出接收器注册会静默收不到，
 * 用导出接收器又容易被后台限制影响。改为主动轮询 [DownloadManager.query]，
 * 既能拿到进度，也能拿到失败原因（HTTP 错误、断网、无存储空间…）。
 *
 * 另一条铁律：所有失败路径都必须返回可读原因，禁止静默 return ——
 * 用户「点了没反应」正是静默失败造成的。
 */
object AppUpdater {

    private const val APK_NAME = "wakeup-update.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"

    // ---------------- 下载 ----------------

    sealed interface Progress {
        data class Pending(val reason: String) : Progress

        data class Running(val downloaded: Long, val total: Long) : Progress {
            /** 0..100；总大小未知时返回 -1 */
            val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt() else -1
        }

        data class Failed(val message: String) : Progress

        data object Success : Progress
    }

    /** 下载落盘位置：优先外部私有目录，不可用时退回内部目录（FileProvider 两处都配了映射） */
    fun apkFile(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return if (external != null) File(external, APK_NAME) else File(context.filesDir, APK_NAME)
    }

    /** 已下载好但还没安装成功的包（用于「继续安装」），无效则返回 null */
    fun existingVerifiedApk(context: Context): File? {
        val file = apkFile(context)
        return if (file.exists() && !verifyApk(context, file).isFailure) file else null
    }

    fun enqueue(context: Context, url: String): Long {
        val dest = apkFile(context)
        if (dest.exists()) dest.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("WakeUp课表 更新包")
            setDescription("正在下载新版本…")
            setMimeType(APK_MIME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun query(context: Context, downloadId: Long): Progress {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching {
            dm.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull() ?: return Progress.Failed("无法查询下载状态")

        cursor.use { c ->
            if (!c.moveToFirst()) return Progress.Failed("下载任务已消失（可能被系统清理），请重试")

            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> Progress.Success
                DownloadManager.STATUS_RUNNING -> Progress.Running(downloaded, total)
                DownloadManager.STATUS_PENDING -> Progress.Pending("排队等待中…")
                DownloadManager.STATUS_PAUSED -> Progress.Running(downloaded, total)
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val uri = runCatching {
                        c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                    }.getOrNull().orEmpty()
                    Progress.Failed(describeFailure(reason, uri))
                }
                else -> Progress.Pending("状态未知（$status）")
            }
        }
    }

    fun cancel(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.remove(downloadId) }
    }

    /** 把 DownloadManager 的失败码翻译成人话 */
    private fun describeFailure(reason: Int, url: String): String {
        val base = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "找不到存储设备"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "数据传输中断（网络不稳定或被拦截）"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回错误（可能被网络阻断，或下载地址已失效）"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
            DownloadManager.ERROR_CANNOT_RESUME -> "断点续传失败，请重试"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
            DownloadManager.ERROR_FILE_ERROR -> "写入文件失败"
            else -> "下载失败（错误码 $reason）"
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        return if (host.isNullOrBlank()) base else "$base｜来源：$host"
    }

    // ---------------- 校验与安装 ----------------

    data class VerifyResult(val isFailure: Boolean, val message: String = "")

    /**
     * 校验下载下来的文件是不是**本应用的有效安装包**。
     * GitHub 异常时可能返回 HTML 错误页，直接丢给安装器会毫无反应，所以必须校验。
     */
    fun verifyApk(context: Context, file: File): VerifyResult {
        if (!file.exists()) return VerifyResult(true, "安装包不存在")
        if (file.length() < 1024) return VerifyResult(true, "下载文件不完整（${file.length()} 字节）")

        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_ACTIVITIES)
        }.getOrNull() ?: return VerifyResult(true, "下载到的不是有效安装包（可能被网络拦截或下载中断）")

        if (info.packageName != context.packageName) {
            return VerifyResult(true, "安装包包名不匹配（${info.packageName}）")
        }
        return VerifyResult(false)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳转「安装未知应用」授权页 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 调起系统安装器。
     * @return 失败原因；null 表示已成功调起
     */
    fun install(context: Context, file: File): String? {
        if (!file.exists()) return "安装包不存在，请重新下载"
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return "需要先允许「安装未知应用」，已在设置页打开开关，授权后回来重试"
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return "无法共享安装包文件（FileProvider 配置异常）"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "系统里没有可用的安装器（部分定制系统会移除）"
        } catch (e: SecurityException) {
            "缺少安装权限：${e.message}"
        }
    }

    /** 兜底：交给浏览器下载（浏览器可能走系统代理/VPN，能绕开 App 自身的网络限制） */
    fun openInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
