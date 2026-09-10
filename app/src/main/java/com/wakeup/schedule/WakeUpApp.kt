package com.wakeup.schedule

import android.app.Application
import com.wakeup.schedule.data.BackupManager
import com.wakeup.schedule.data.Repository

class WakeUpApp : Application() {

    lateinit var repository: Repository
        private set
    lateinit var backupManager: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        backupManager = BackupManager(repository)
    }

    companion object {
        fun get(context: android.content.Context): WakeUpApp =
            context.applicationContext as WakeUpApp
    }
}
