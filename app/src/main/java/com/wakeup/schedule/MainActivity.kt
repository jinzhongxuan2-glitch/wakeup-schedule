package com.wakeup.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wakeup.schedule.ui.AppRoot
import com.wakeup.schedule.ui.theme.WakeUpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = WakeUpApp.get(this)

        setContent {
            val darkPref by app.repository.prefs.darkMode.collectAsState(initial = 0)
            val dark = when (darkPref) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            // 首启播种（写示例课表）放到 IO 协程，主线程只负责显示一次加载态
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { app.repository.ensureInitialData() }
                ready = true
            }

            WakeUpTheme(darkTheme = dark) {
                if (ready) {
                    AppRoot(app)
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
