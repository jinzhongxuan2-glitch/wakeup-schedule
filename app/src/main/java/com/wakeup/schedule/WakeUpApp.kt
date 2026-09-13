package com.wakeup.schedule

import android.app.Application
import com.wakeup.schedule.data.BackupManager
import com.wakeup.schedule.data.Repository
import com.wakeup.schedule.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class WakeUpApp : Application() {

    /** 应用级协程作用域：只做与进程同生命周期的事（如小部件刷新） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: Repository
        private set
    lateinit var backupManager: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        backupManager = BackupManager(repository)

        // 数据一变（增删改课程 / 课表 / 外观），自动刷新桌面小部件
        appScope.launch {
            repository.dataVersion.drop(1).collect {
                WidgetUpdater.refreshAll(this@WakeUpApp)
            }
        }
    }

    companion object {
        fun get(context: android.content.Context): WakeUpApp =
            context.applicationContext as WakeUpApp
    }
}
