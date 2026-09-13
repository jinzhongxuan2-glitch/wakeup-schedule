package com.wakeup.schedule.ui.edit

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.core.ScheduleMath
import com.wakeup.schedule.data.CourseEntity
import com.wakeup.schedule.data.TimeSlotEntity
import com.wakeup.schedule.data.WeekType
import com.wakeup.schedule.ui.theme.CourseColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

private val DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private class SlotEdit(
    dayOfWeek: Int = 1,
    startSection: Int = 1,
    sectionCount: Int = 2,
    startWeek: Int = 1,
    endWeek: Int = 20,
    weekType: Int = WeekType.ALL,
    location: String = ""
) {
    var dayOfWeek by mutableIntStateOf(dayOfWeek)
    var startSection by mutableIntStateOf(startSection)
    var sectionCount by mutableIntStateOf(sectionCount)
    var startWeek by mutableIntStateOf(startWeek)
    var endWeek by mutableIntStateOf(endWeek)
    var weekType by mutableIntStateOf(weekType)
    var location by mutableStateOf(location)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditScreen(app: WakeUpApp, courseId: Long, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var tableId by remember { mutableStateOf(-1L) }
    var totalWeeks by remember { mutableIntStateOf(20) }
    var maxSections by remember { mutableIntStateOf(12) }

    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var colorIdx by remember { mutableIntStateOf(0) }
    val slots = remember { mutableStateListOf<SlotEdit>() }
    // 同一课表下其它课程的时间段，用于冲突检测（课程名 + 时间段）
    var otherSlots by remember { mutableStateOf(listOf<Pair<String, TimeSlotEntity>>()) }

    LaunchedEffect(Unit) {
        var currentTableId = repo.prefs.currentTableId.first()
        var table = repo.getTable(currentTableId)
        if (table == null) {
            // 自愈：没有课表时先建一张默认课表，避免课程挂到不存在的 tableId 上变成孤儿数据
            currentTableId = repo.createTable(
                name = "我的课表",
                startDate = ScheduleMath.startDateForCurrentWeek(LocalDate.now(), 1).toString(),
                totalWeeks = 20,
                maxSections = 12
            )
            repo.prefs.setCurrentTable(currentTableId)
            table = repo.getTable(currentTableId)
        }
        if (table != null) {
            tableId = table.id
            totalWeeks = table.totalWeeks
            maxSections = table.maxSections
            otherSlots = repo.getTableSlotPairs(table.id, excludeCourseId = if (courseId > 0) courseId else -1L)
        }
        if (courseId > 0) {
            repo.getCourseWithSlots(courseId)?.let { cw ->
                name = cw.course.name
                teacher = cw.course.teacher
                note = cw.course.note
                tableId = cw.course.tableId
                val idx = CourseColors.indexOfFirst { it.toArgb() == cw.course.color }
                colorIdx = if (idx >= 0) idx else 0
                cw.slots.forEach {
                    slots.add(
                        SlotEdit(
                            it.dayOfWeek, it.startSection, it.sectionCount,
                            it.startWeek, it.endWeek, it.weekType, it.location
                        )
                    )
                }
            }
        }
        if (slots.isEmpty()) slots.add(SlotEdit(endWeek = totalWeeks))
        loaded = true
    }

    fun save() {
        if (name.isBlank()) {
            scope.launch { snackbar.showSnackbar("请填写课程名称") }
            return
        }
        if (slots.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请至少添加一个时间段") }
            return
        }
        scope.launch {
            repo.saveCourse(
                CourseEntity(
                    id = if (courseId > 0) courseId else 0,
                    tableId = tableId,
                    name = name.trim(),
                    teacher = teacher.trim(),
                    color = CourseColors[colorIdx].toArgb(),
                    note = note.trim()
                ),
                slots.map {
                    TimeSlotEntity(
                        courseId = 0,
                        dayOfWeek = it.dayOfWeek,
                        startSection = it.startSection,
                        sectionCount = it.sectionCount,
                        startWeek = it.startWeek.coerceAtMost(it.endWeek),
                        endWeek = it.endWeek.coerceAtLeast(it.startWeek),
                        weekType = it.weekType,
                        location = it.location.trim()
                    )
                }
            )
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (courseId > 0) "编辑课程" else "添加课程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { if (loaded) save() }) {
                        Icon(Icons.Filled.Check, "保存", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text("教师（选填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text("课程颜色", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CourseColors.forEachIndexed { i, c ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(
                                if (i == colorIdx) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { colorIdx = i }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上课时间段", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { slots.add(SlotEdit(endWeek = totalWeeks)) }) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加时间段")
                }
            }

            // 冲突提示：与同课表下其它课程时间重叠（已考虑起止周与单双周）
            val conflictNotes = buildList {
                slots.forEach { s ->
                    val a = ScheduleMath.SlotKey(s.dayOfWeek, s.startSection, s.sectionCount, s.startWeek, s.endWeek, s.weekType)
                    otherSlots.forEach { (otherName, other) ->
                        val b = ScheduleMath.SlotKey(other.dayOfWeek, other.startSection, other.sectionCount, other.startWeek, other.endWeek, other.weekType)
                        val weeks = ScheduleMath.overlappingWeeks(a, b, totalWeeks)
                        if (weeks.isNotEmpty()) {
                            add("与「$otherName」冲突：${DAYS[s.dayOfWeek - 1]} 第${a.startSection}-${a.endSection}节，第${weeks.first()}-${weeks.last()}周")
                        }
                    }
                }
            }.distinct()
            if (conflictNotes.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "时间冲突提醒（共 ${conflictNotes.size} 处）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        conflictNotes.forEach {
                            Text("· $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            slots.forEachIndexed { index, slot ->
                SlotCard(
                    slot = slot,
                    totalWeeks = totalWeeks,
                    maxSections = maxSections,
                    canDelete = slots.size > 1,
                    onDelete = { slots.removeAt(index) }
                )
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SlotCard(
    slot: SlotEdit,
    totalWeeks: Int,
    maxSections: Int,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间段", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, "删除时间段", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 星期
            Text("星期", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    FilterChip(
                        selected = slot.dayOfWeek == d,
                        onClick = { slot.dayOfWeek = d },
                        label = { Text(DAYS[d - 1], fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // 节次
            Text("节次（开始节 + 连上节数）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..maxSections).forEach { s ->
                        FilterChip(
                            selected = slot.startSection == s,
                            onClick = {
                                slot.startSection = s
                                if (slot.startSection + slot.sectionCount - 1 > maxSections) {
                                    slot.sectionCount = (maxSections - s + 1).coerceAtLeast(1)
                                }
                            },
                            label = { Text("$s", fontSize = 12.sp) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..4).forEach { c ->
                    FilterChip(
                        selected = slot.sectionCount == c,
                        onClick = {
                            val max = (maxSections - slot.startSection + 1).coerceAtLeast(1)
                            slot.sectionCount = c.coerceAtMost(max)
                        },
                        label = { Text("连上${c}节", fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // 周数
            Text("周数：第 ${slot.startWeek} - ${slot.endWeek} 周", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = false, onClick = { slot.startWeek = (slot.startWeek - 1).coerceAtLeast(1) }, label = { Text("起-1") })
                FilterChip(selected = false, onClick = { slot.startWeek = (slot.startWeek + 1).coerceAtMost(slot.endWeek) }, label = { Text("起+1") })
                FilterChip(selected = false, onClick = { slot.endWeek = (slot.endWeek - 1).coerceAtLeast(slot.startWeek) }, label = { Text("止-1") })
                FilterChip(selected = false, onClick = { slot.endWeek = (slot.endWeek + 1).coerceAtMost(totalWeeks) }, label = { Text("止+1") })
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(WeekType.ALL to "每周", WeekType.ODD to "单周", WeekType.EVEN to "双周").forEach { (t, label) ->
                    FilterChip(
                        selected = slot.weekType == t,
                        onClick = { slot.weekType = t },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = slot.location,
                onValueChange = { slot.location = it },
                label = { Text("上课地点（选填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
