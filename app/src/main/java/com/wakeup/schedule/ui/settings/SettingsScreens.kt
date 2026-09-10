package com.wakeup.schedule.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.data.SectionTimeEntity
import com.wakeup.schedule.data.TimeTableEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 课表设置：名称 / 开学日期 / 总周数 / 每日节数 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableSettingsScreen(app: WakeUpApp, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var table by remember { mutableStateOf<TimeTableEntity?>(null) }

    var name by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var weeks by remember { mutableStateOf("") }
    var sections by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val id = repo.prefs.currentTableId.first()
        repo.getTable(id)?.let {
            table = it
            name = it.name
            startDate = it.startDate
            weeks = it.totalWeeks.toString()
            sections = it.maxSections.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("课表名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                label = { Text("学期开始日期（第一周周一，yyyy-MM-dd）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = weeks,
                onValueChange = { weeks = it.filter(Char::isDigit) },
                label = { Text("总周数") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sections,
                onValueChange = { sections = it.filter(Char::isDigit) },
                label = { Text("每日节数") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val t = table ?: return@Button
                    val w = weeks.toIntOrNull()?.coerceIn(1, 30) ?: t.totalWeeks
                    val s = sections.toIntOrNull()?.coerceIn(1, 16) ?: t.maxSections
                    val validDate = runCatching { java.time.LocalDate.parse(startDate) }.isSuccess
                    if (name.isNotBlank() && validDate) {
                        scope.launch {
                            repo.updateTable(t.copy(name = name.trim(), startDate = startDate, totalWeeks = w, maxSections = s))
                            repo.ensureSectionTimes(t.id, s)
                            onBack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
        }
    }
}

/** 上课时间设置：编辑每节课的上下课时间 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSettingsScreen(app: WakeUpApp, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    var tableId by remember { mutableStateOf(-1L) }
    var timeItems by remember { mutableStateOf(listOf<SectionTimeEntity>()) }

    LaunchedEffect(Unit) {
        val id = repo.prefs.currentTableId.first()
        tableId = id
        val table = repo.getTable(id)
        if (table != null) {
            repo.ensureSectionTimes(id, table.maxSections)
            timeItems = repo.sectionTimesFlow(id).first()
            if (timeItems.size < table.maxSections) {
                // 补足缺省
                val defaults = com.wakeup.schedule.data.Repository.defaultSectionTimes(id, table.maxSections)
                timeItems = defaults.map { d -> timeItems.firstOrNull { it.section == d.section } ?: d }
            }
        }
    }

    fun update(section: Int, start: String? = null, end: String? = null) {
        timeItems = timeItems.map {
            if (it.section == section) it.copy(start = start ?: it.start, end = end ?: it.end) else it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上课时间") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(timeItems, key = { it.section }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "第${t.section}节",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(64.dp)
                        )
                        OutlinedTextField(
                            value = t.start,
                            onValueChange = { update(t.section, start = it) },
                            label = { Text("上课") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = t.end,
                            onValueChange = { update(t.section, end = it) },
                            label = { Text("下课") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        repo.saveSectionTimes(tableId, timeItems)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("保存") }
        }
    }
}

/** 全局设置：深色模式 / 时间轴显示 / 关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(app: WakeUpApp, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val darkMode by repo.prefs.darkMode.collectAsState(initial = 0)
    val showTime by repo.prefs.showSectionTime.collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("外观", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { repo.prefs.setDarkMode(mode) } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = darkMode == mode, onClick = { scope.launch { repo.prefs.setDarkMode(mode) } })
                    Text(label)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("时间轴显示上下课时间", modifier = Modifier.weight(1f))
                Switch(
                    checked = showTime,
                    onCheckedChange = { scope.launch { repo.prefs.setShowSectionTime(it) } }
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("关于", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("WakeUp课表 · 逆向还原版", fontWeight = FontWeight.Medium)
            Text(
                "以 WakeUp课程表 官方文档为蓝本重写的开源课表应用：周视图、单双周、多课表、导入导出、桌面小部件。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 管理课表：切换 / 删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableManageScreen(app: WakeUpApp, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val tables by repo.tables.collectAsState(initial = emptyList())
    val currentId by repo.prefs.currentTableId.collectAsState(initial = -1L)
    var deleteTarget by remember { mutableStateOf<TimeTableEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理课表") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            items(tables, key = { it.id }) { t ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable {
                            scope.launch {
                                repo.prefs.setCurrentTable(t.id)
                                onBack()
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (t.id == currentId) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${t.startDate} 开学 · ${t.totalWeeks}周 · 每天${t.maxSections}节" +
                                    if (t.id == currentId) " · 当前" else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { deleteTarget = t }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除课表", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除课表") },
            text = { Text("将删除「${t.name}」及其全部课程，且不可恢复。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteTable(t.id)
                        deleteTarget = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
