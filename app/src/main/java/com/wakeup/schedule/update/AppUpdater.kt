package com.wakeup.schedule.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 用系统 DownloadManager 下载 APK，完成后弹系统安装器。
 * 注意：相同签名才能覆盖安装（这正是 release 必须使用固定 keystore 的原因）。
 */
object AppUpdater {

    private const val APK_NAME = "wakeup-update.apk"

    fun downloadAndInstall(context: Context, apkUrl: String, versionName: String) {
        val appContext = context.applicationContext
        val dest = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        if (dest.exists()) dest.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("WakeUp课表 $versionName")
            setDescription("正在下载新版本…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // 监听下载完成 → 调起安装
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                runCatching { appContext.unregisterReceiver(this) }
                install(appContext, dest)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun install(context: Context, apk: File) {
        if (!apk.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
