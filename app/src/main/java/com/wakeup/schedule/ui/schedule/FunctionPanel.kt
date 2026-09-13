package com.wakeup.schedule.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeup.schedule.data.TimeTableEntity
import com.wakeup.schedule.ui.Routes
import com.wakeup.schedule.ui.theme.CourseColors

/** 底部「更多功能」面板：周数滑竿 + 课表切换器 + 功能按钮区 */
@Composable
fun FunctionPanel(
    state: ScheduleUiState,
    pagerWeek: Int,
    onJumpWeek: (Int) -> Unit,
    onSwitchTable: (Long) -> Unit,
    onCreateTable: (String, String, Int, Int) -> Unit,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val table = state.table ?: return
    var showCreate by remember { mutableStateOf(false) }
    // 拖动时只更新本地值，松手才翻页 —— 否则每一帧都触发 animateScrollToPage，卡顿明显
    var draggingWeek by remember(pagerWeek) { mutableStateOf<Int?>(null) }
    val shownWeek = draggingWeek ?: pagerWeek

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        // ===== 周数滑竿（跟随当前页面周） =====
        Text(
            text = "第 $shownWeek 周 / 共 ${table.totalWeeks} 周" +
                if (shownWeek == state.currentWeek) "（本周）" else "",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = shownWeek.toFloat(),
            onValueChange = { draggingWeek = it.toInt().coerceIn(1, table.totalWeeks) },
            onValueChangeFinished = {
                draggingWeek?.let { onJumpWeek(it) }
                draggingWeek = null
            },
            valueRange = 1f..table.totalWeeks.toFloat(),
            steps = (table.totalWeeks - 2).coerceAtLeast(0)
        )

        // ===== 课表切换器 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课表", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { showCreate = true }) { Text("新建") }
            TextButton(onClick = { onNavigate(Routes.TABLE_MANAGE) }) { Text("管理") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.allTables, key = { it.id }) { t ->
                TableChip(
                    table = t,
                    selected = t.id == table.id,
                    onClick = {
                        onSwitchTable(t.id)
                        onDismiss()
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ===== 功能按钮区 =====
        Row(Modifier.fillMaxWidth()) {
            PanelButton(Icons.Outlined.Schedule, "上课时间", Modifier.weight(1f)) { onNavigate(Routes.TIME_SETTINGS) }
            PanelButton(Icons.Outlined.Tune, "课表设置", Modifier.weight(1f)) { onNavigate(Routes.TABLE_SETTINGS) }
            PanelButton(Icons.Outlined.Palette, "课表外观", Modifier.weight(1f)) { onNavigate(Routes.APPEARANCE) }
            PanelButton(Icons.Outlined.FormatListBulleted, "已添课程", Modifier.weight(1f)) { onNavigate(Routes.COURSE_LIST) }
        }
        Row(Modifier.fillMaxWidth()) {
            PanelButton(Icons.Outlined.DateRange, "管理课表", Modifier.weight(1f)) { onNavigate(Routes.TABLE_MANAGE) }
            PanelButton(Icons.Outlined.Settings, "全局设置", Modifier.weight(1f)) { onNavigate(Routes.GLOBAL_SETTINGS) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }

    if (showCreate) {
        CreateTableDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, date, weeks, sections ->
                onCreateTable(name, date, weeks, sections)
                showCreate = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun TableChip(table: TimeTableEntity, selected: Boolean, onClick: () -> Unit) {
    val bg = CourseColors[(table.id % CourseColors.size).toInt().let { if (it < 0) 0 else it }]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .then(
                    if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = table.name.take(2),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = table.name,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun CreateTableDialog(onDismiss: () -> Unit, onConfirm: (String, String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("新课表") }
    var startDate by remember { mutableStateOf(java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()) }
    var weeks by remember { mutableStateOf("20") }
    var sections by remember { mutableStateOf("12") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建课表") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("课表名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("学期开始日期（第一周周一，yyyy-MM-dd）") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = weeks,
                        onValueChange = { weeks = it.filter(Char::isDigit) },
                        label = { Text("总周数") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = sections,
                        onValueChange = { sections = it.filter(Char::isDigit) },
                        label = { Text("每日节数") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val w = weeks.toIntOrNull()?.coerceIn(1, 30) ?: 20
                val s = sections.toIntOrNull()?.coerceIn(1, 16) ?: 12
                val validDate = runCatching { java.time.LocalDate.parse(startDate) }.isSuccess
                if (name.isNotBlank() && validDate) onConfirm(name.trim(), startDate, w, s)
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
