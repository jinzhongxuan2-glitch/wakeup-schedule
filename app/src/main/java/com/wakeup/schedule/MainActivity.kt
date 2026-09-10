package com.wakeup.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wakeup.schedule.ui.AppRoot
import com.wakeup.schedule.ui.theme.WakeUpTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = WakeUpApp.get(this)

        // 首次启动：写入示例课表
        runBlocking {
            val loaded = app.repository.prefs.sampleLoaded.first()
            if (!loaded) {
                val existing = app.repository.tables.firstOrNull().orEmpty()
                if (existing.isEmpty()) {
                    val id = com.wakeup.schedule.data.SampleData.load(app.repository)
                    app.repository.prefs.setCurrentTable(id)
                } else {
                    app.repository.prefs.setCurrentTable(existing.first().id)
                }
                app.repository.prefs.setSampleLoaded(true)
            }
        }

        enableEdgeToEdge()
        setContent {
            val darkPref by app.repository.prefs.darkMode.collectAsState(initial = 0)
            val dark = when (darkPref) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            WakeUpTheme(darkTheme = dark) {
                AppRoot(app)
            }
        }
    }
}
