package com.wakeup.schedule.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.data.TimeTableEntity
import com.wakeup.schedule.ui.theme.CourseColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 课表外观：显示周末 / 课程块透明度 / 背景（默认、纯色、相册图片） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(app: WakeUpApp, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var table by remember { mutableStateOf<TimeTableEntity?>(null) }

    var showWeekend by remember { mutableStateOf(true) }
    var blockAlpha by remember { mutableFloatStateOf(0.95f) }
    var bgType by remember { mutableIntStateOf(0) }
    var bgValue by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                app.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            bgType = 2
            bgValue = it.toString()
        }
    }

    LaunchedEffect(Unit) {
        val id = repo.prefs.currentTableId.first()
        repo.getTable(id)?.let {
            table = it
            showWeekend = it.showWeekend
            blockAlpha = it.blockAlpha
            bgType = it.bgType
            bgValue = it.bgValue
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表外观") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            // 布局
            Text("布局", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("显示周六、周日两列", modifier = Modifier.weight(1f))
                Switch(checked = showWeekend, onCheckedChange = { showWeekend = it })
            }

            Spacer(Modifier.height(16.dp))
            // 课程块透明度
            Text("课程块", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("不透明度：${(blockAlpha * 100).roundToInt()}%", fontSize = 14.sp)
            Slider(
                value = blockAlpha,
                onValueChange = { blockAlpha = it },
                valueRange = 0.3f..1f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourseColors.take(6).forEach { c ->
                    Box(
                        Modifier
                            .width(44.dp).height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.copy(alpha = blockAlpha))
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            // 背景
            Text("课表背景", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = bgType == 0, onClick = { bgType = 0 })
                Text("默认", modifier = Modifier.clickable { bgType = 0 })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = bgType == 1, onClick = { bgType = 1 })
                Text("纯色", modifier = Modifier.clickable { bgType = 1 })
            }
            if (bgType == 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BG_PRESETS.forEach { (name, color) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (bgValue.toIntOrNull() == color.toArgb())
                                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                    .clickable { bgValue = color.toArgb().toString() }
                            )
                            Text(name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = bgType == 2, onClick = { imagePicker.launch(arrayOf("image/*")) })
                Text("相册图片", modifier = Modifier.clickable { imagePicker.launch(arrayOf("image/*")) })
            }
            if (bgType == 2 && bgValue.isNotBlank()) {
                AsyncImage(
                    model = bgValue,
                    contentDescription = "背景预览",
                    modifier = Modifier
                        .padding(start = 32.dp)
                        .width(140.dp).height(90.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    val t = table ?: return@Button
                    scope.launch {
                        repo.updateTable(
                            t.copy(
                                showWeekend = showWeekend,
                                blockAlpha = blockAlpha,
                                bgType = bgType,
                                bgValue = if (bgType == 0) "" else bgValue
                            )
                        )
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
            Spacer(Modifier.height(8.dp))
            if (bgType != 0) {
                OutlinedButton(
                    onClick = {
                        bgType = 0
                        bgValue = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("恢复默认背景") }
            }
        }
    }
}

/** 背景纯色预设（浅色友好） */
private val BG_PRESETS = listOf(
    "米白" to Color(0xFFFFF8E7),
    "浅粉" to Color(0xFFFCE4EC),
    "浅蓝" to Color(0xFFE3F2FD),
    "浅绿" to Color(0xFFE8F5E9),
    "浅紫" to Color(0xFFF3E5F5),
    "浅灰" to Color(0xFFF5F5F5),
    "墨黑" to Color(0xFF263238),
    "藏青" to Color(0xFF1A237E)
)
