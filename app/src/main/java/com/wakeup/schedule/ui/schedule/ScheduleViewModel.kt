package com.wakeup.schedule.ui.schedule

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.core.CsvScheduleParser
import com.wakeup.schedule.core.ScheduleMath
import com.wakeup.schedule.data.CourseEntity
import com.wakeup.schedule.data.Repository
import com.wakeup.schedule.data.SectionTimeEntity
import com.wakeup.schedule.data.TimeSlotEntity
import com.wakeup.schedule.data.TimeTableEntity
import com.wakeup.schedule.ui.theme.CourseColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ScheduleUiState(
    val table: TimeTableEntity? = null,
    val allTables: List<TimeTableEntity> = emptyList(),
    val currentWeek: Int = 1,
    val courses: List<CourseEntity> = emptyList(),
    val slots: List<TimeSlotEntity> = emptyList(),
    val sectionTimes: List<SectionTimeEntity> = emptyList(),
    val showSectionTime: Boolean = true,
    val loading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(private val app: WakeUpApp) : ViewModel() {

    private val repo: Repository = app.repository
    val toast = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ScheduleUiState> =
        combine(repo.prefs.currentTableId, repo.tables, repo.prefs.showSectionTime) { currentId, tables, showTime ->
            Triple(currentId, tables, showTime)
        }.flatMapLatest { (currentId, tables, showTime) ->
            val table = tables.firstOrNull { it.id == currentId } ?: tables.firstOrNull()
            if (table == null) {
                flowOf(ScheduleUiState(loading = false, showSectionTime = showTime))
            } else {
                combine(
                    repo.coursesFlow(table.id),
                    repo.slotsFlow(table.id),
                    repo.sectionTimesFlow(table.id)
                ) { courses, slots, times ->
                    ScheduleUiState(
                        table = table,
                        allTables = tables,
                        currentWeek = repo.currentWeek(table),
                        courses = courses,
                        slots = slots,
                        sectionTimes = times,
                        showSectionTime = showTime,
                        loading = false
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    fun switchTable(id: Long) = viewModelScope.launch { repo.prefs.setCurrentTable(id) }

    /** 把「今天」设置为第 week 周（自动反推学期开始日期），与 WakeUp 修改当前周行为一致 */
    fun setCurrentWeek(week: Int) = viewModelScope.launch {
        val t = uiState.value.table ?: return@launch
        val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        repo.updateTable(t.copy(startDate = monday.minusWeeks((week - 1).toLong()).toString()))
    }

    fun createTable(name: String, startDate: String, totalWeeks: Int, maxSections: Int) =
        viewModelScope.launch {
            val id = repo.createTable(name, startDate, totalWeeks, maxSections)
            repo.prefs.setCurrentTable(id)
        }

    fun deleteCourse(id: Long) = viewModelScope.launch { repo.deleteCourse(id) }

    fun deleteTable(id: Long) = viewModelScope.launch {
        val newId = repo.deleteTable(id)
        val current = uiState.value.table?.id
        if (current == id && newId > 0) repo.prefs.setCurrentTable(newId)
        if (newId <= 0) repo.prefs.setCurrentTable(-1L)
    }

    fun exportJson(uri: android.net.Uri) = viewModelScope.launch {
        val tableId = uiState.value.table?.id ?: return@launch
        val json = app.backupManager.export(tableId)
        if (json == null) {
            toast.value = "导出失败"
            return@launch
        }
        writeText(uri, json, "已导出当前课表备份")
    }

    /** 导出全部课表（含外观、节次时间）为单个备份文件 */
    fun exportAllJson(uri: android.net.Uri) = viewModelScope.launch {
        val json = runCatching { app.backupManager.exportAll() }.getOrNull()
        if (json == null) {
            toast.value = "导出失败"
            return@launch
        }
        writeText(uri, json, "已导出全部课表备份")
    }

    private fun writeText(uri: android.net.Uri, text: String, okMessage: String) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.onSuccess { toast.value = okMessage }
                .onFailure { toast.value = "导出失败：${it.message}" }
        }
    }

    fun importJson(uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val json = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("无法读取文件")
            val ids = app.backupManager.importAll(json)
            if (ids.isEmpty()) error("备份里没有可导入的课表")
            repo.prefs.setCurrentTable(ids.last())
            ids.size
        }.onSuccess { count ->
            toast.value = if (count > 1) "已导入 $count 张课表" else "导入成功"
        }.onFailure { toast.value = it.message ?: "导入失败" }
    }

    /** 撤销删除：把快照恢复成同一门课（时间段一并恢复） */
    fun restoreCourse(course: CourseEntity, slots: List<TimeSlotEntity>) = viewModelScope.launch {
        repo.saveCourse(course.copy(id = 0), slots.map { it.copy(id = 0, courseId = 0) })
        toast.value = "已恢复「${course.name}」"
    }

    fun exportShareCode(onResult: (String?) -> Unit) = viewModelScope.launch {
        val tableId = uiState.value.table?.id ?: return@launch
        onResult(app.backupManager.exportShareCode(tableId))
    }

    fun importShareCode(code: String) = viewModelScope.launch {
        runCatching { app.backupManager.importShareCode(code) }
            .onSuccess {
                repo.prefs.setCurrentTable(it)
                toast.value = "导入成功"
            }
            .onFailure { toast.value = it.message ?: "口令无效" }
    }

    /** 从 Excel/CSV 模板导入：创建新课表并切换 */
    fun importCsv(uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val text = app.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("无法读取文件")
            val result = CsvScheduleParser.parse(text)
            if (result.courses.isEmpty()) error("没有解析到任何课程，请检查模板格式")
            val stamp = java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val tableId = repo.createTable(
                "CSV导入 $stamp",
                ScheduleMath.startDateForCurrentWeek(LocalDate.now(), 1).toString(),
                20, 12
            )
            result.courses.forEachIndexed { i, c ->
                repo.saveCourse(
                    CourseEntity(
                        tableId = tableId,
                        name = c.name,
                        teacher = c.teacher,
                        color = CourseColors[i % CourseColors.size].toArgb()
                    ),
                    c.slots.map {
                        TimeSlotEntity(
                            courseId = 0,
                            dayOfWeek = it.dayOfWeek,
                            startSection = it.startSection,
                            sectionCount = it.sectionCount,
                            startWeek = it.startWeek,
                            endWeek = it.endWeek,
                            weekType = it.weekType,
                            location = it.location
                        )
                    }
                )
            }
            repo.prefs.setCurrentTable(tableId)
            result
        }.onSuccess { r ->
            toast.value = "成功导入 ${r.courses.size} 门课程" +
                if (r.errors.isNotEmpty()) "，${r.errors.size} 行被跳过" else ""
        }.onFailure { toast.value = it.message ?: "CSV 导入失败" }
    }

    fun consumeToast() {
        toast.value = null
    }

    companion object {
        fun factory(app: WakeUpApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ScheduleViewModel(app) as T
        }
    }
}
