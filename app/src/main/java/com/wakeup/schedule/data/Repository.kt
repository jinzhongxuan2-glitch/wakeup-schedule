package com.wakeup.schedule.data

import android.content.Context
import androidx.room.withTransaction
import com.wakeup.schedule.core.ScheduleMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** 聚合某个课表在指定周次的全部课程块 */
data class WeekSchedule(
    val table: TimeTableEntity,
    val week: Int,
    val items: List<CourseBlock>,
    val sectionTimes: List<SectionTimeEntity>
)

/** 铺到网格上的一个课程块 */
data class CourseBlock(
    val course: CourseEntity,
    val slot: TimeSlotEntity
)

class Repository(val context: Context) {

    private val db = AppDatabase.get(context)
    private val tableDao = db.timeTableDao()
    private val courseDao = db.courseDao()
    private val slotDao = db.timeSlotDao()
    private val sectionDao = db.sectionTimeDao()
    val prefs = Prefs(context)

    val tables: Flow<List<TimeTableEntity>> = tableDao.getAllFlow()

    /**
     * 数据版本号：任何写操作都会 +1。
     * 桌面小部件订阅它来在数据变更后自动刷新（无需在每个调用点手动触发）。
     */
    private val _dataVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val dataVersion: kotlinx.coroutines.flow.StateFlow<Long> = _dataVersion

    private fun bump() {
        _dataVersion.value += 1
    }

    fun coursesFlow(tableId: Long): Flow<List<CourseEntity>> = courseDao.getByTableFlow(tableId)
    fun slotsFlow(tableId: Long): Flow<List<TimeSlotEntity>> = slotDao.getByTableFlow(tableId)
    fun sectionTimesFlow(tableId: Long): Flow<List<SectionTimeEntity>> = sectionDao.getByTableFlow(tableId)

    /** 计算今天属于第几周（1-based），不在学期内也会被夹取到 1..totalWeeks */
    fun currentWeek(table: TimeTableEntity, today: LocalDate = LocalDate.now()): Int =
        ScheduleMath.currentWeek(LocalDate.parse(table.startDate), today, table.totalWeeks)

    /** 某天在学期内属于第几周 */
    fun weekOfDate(table: TimeTableEntity, date: LocalDate): Int =
        ScheduleMath.currentWeek(LocalDate.parse(table.startDate), date, table.totalWeeks)

    /** 某一周周一的日期 */
    fun mondayOfWeek(table: TimeTableEntity, week: Int): LocalDate =
        ScheduleMath.mondayOfWeek(LocalDate.parse(table.startDate), week)

    fun blockVisibleInWeek(slot: TimeSlotEntity, week: Int): Boolean =
        ScheduleMath.visibleInWeek(week, slot.startWeek, slot.endWeek, slot.weekType)

    /**
     * 首启初始化：没有课表时写入内置示例课表，并修正当前课表偏好。
     * 由 UI 层在 IO 协程里调用（不要把这种活儿放在主线程）。
     */
    suspend fun ensureInitialData() {
        if (prefs.sampleLoaded.first()) return
        val existing = tables.first()
        if (existing.isEmpty()) {
            val id = SampleData.load(this)
            prefs.setCurrentTable(id)
        } else {
            prefs.setCurrentTable(existing.first().id)
        }
        prefs.setSampleLoaded(true)
    }

    suspend fun getTable(id: Long): TimeTableEntity? = tableDao.getById(id)

    suspend fun createTable(name: String, startDate: String, totalWeeks: Int, maxSections: Int): Long {
        val id = tableDao.insert(
            TimeTableEntity(
                name = name,
                // 开学日必须是周一，否则整个学期的周次都会偏移
                startDate = ScheduleMath.normalizeToMonday(startDate),
                totalWeeks = totalWeeks,
                maxSections = maxSections
            )
        )
        sectionDao.insertAll(defaultSectionTimes(id, maxSections))
        bump()
        return id
    }

    suspend fun updateTable(table: TimeTableEntity) {
        tableDao.update(table.copy(startDate = ScheduleMath.normalizeToMonday(table.startDate)))
        bump()
    }

    /** 删除课表，返回删除后应切换到的新课表 id（没有则 -1） */
    suspend fun deleteTable(id: Long): Long {
        tableDao.deleteById(id)
        bump()
        return tableDao.firstId() ?: -1L
    }

    /**
     * 保存课程 + 覆盖其时间段。
     * 放在一个事务里：中途失败不会留下「课程已改、时间段还是旧的」的半成品数据。
     */
    suspend fun saveCourse(course: CourseEntity, slots: List<TimeSlotEntity>): Long {
        val courseId = db.withTransaction {
            val id = if (course.id == 0L) {
                courseDao.insert(course)
            } else {
                courseDao.update(course)
                course.id
            }
            slotDao.deleteByCourse(id)
            if (slots.isNotEmpty()) {
                slotDao.insertAll(slots.map { it.copy(id = 0, courseId = id) })
            }
            id
        }
        bump()
        return courseId
    }

    suspend fun deleteCourse(id: Long) {
        courseDao.deleteById(id)
        bump()
    }

    suspend fun getCourseWithSlots(courseId: Long): CourseWithSlots? {
        val c = courseDao.getById(courseId) ?: return null
        return CourseWithSlots(c, slotDao.getByCourse(courseId))
    }

    /** 课表下所有时间段，附带课程名（排除某门课，用于编辑时的冲突检测） */
    suspend fun getTableSlotPairs(tableId: Long, excludeCourseId: Long = -1L): List<Pair<String, TimeSlotEntity>> {
        val names = courseDao.getByTable(tableId).associate { it.id to it.name }
        return slotDao.getByTable(tableId)
            .filter { it.courseId != excludeCourseId }
            .mapNotNull { slot -> names[slot.courseId]?.let { it to slot } }
    }

    suspend fun saveSectionTimes(tableId: Long, items: List<SectionTimeEntity>) {
        sectionDao.insertAll(items)
        bump()
    }

    suspend fun ensureSectionTimes(tableId: Long, maxSections: Int) {
        if (sectionDao.countForTable(tableId) == 0) {
            sectionDao.insertAll(defaultSectionTimes(tableId, maxSections))
        }
    }

    /** 今日课程（桌面小部件用，同步查询） */
    fun getTodayBlocksSync(tableId: Long, today: LocalDate = LocalDate.now()): List<CourseBlock> {
        val table = runCatching {
            kotlinx.coroutines.runBlocking { tableDao.getById(tableId) }
        }.getOrNull() ?: return emptyList()
        val week = currentWeek(table, today)
        if (weekOfDate(table, today) != week) return emptyList()
        val day = today.dayOfWeek.value
        val courses = kotlinx.coroutines.runBlocking { courseDao.getByTable(tableId) }
        val slots = kotlinx.coroutines.runBlocking { slotDao.getByTable(tableId) }
        val byCourse = courses.associateBy { it.id }
        return slots.filter { it.dayOfWeek == day && blockVisibleInWeek(it, week) }
            .mapNotNull { s -> byCourse[s.courseId]?.let { CourseBlock(it, s) } }
            .sortedBy { it.slot.startSection }
    }

    companion object {
        /** 默认 12 节时间 */
        fun defaultSectionTimes(tableId: Long, count: Int = 12): List<SectionTimeEntity> {
            val base = listOf(
                "08:00" to "08:45", "08:55" to "09:40",
                "10:05" to "10:50", "11:00" to "11:45",
                "14:00" to "14:45", "14:55" to "15:40",
                "16:05" to "16:50", "17:00" to "17:45",
                "19:00" to "19:45", "19:55" to "20:40",
                "20:50" to "21:35", "21:45" to "22:30"
            )
            return (1..count).map { i ->
                val t = base.getOrElse(i - 1) { "—" to "—" }
                SectionTimeEntity(tableId, i, t.first, t.second)
            }
        }
    }
}
