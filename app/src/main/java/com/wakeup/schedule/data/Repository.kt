package com.wakeup.schedule.data

import android.content.Context
import com.wakeup.schedule.core.ScheduleMath
import kotlinx.coroutines.flow.Flow
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

    suspend fun getTable(id: Long): TimeTableEntity? = tableDao.getById(id)

    suspend fun createTable(name: String, startDate: String, totalWeeks: Int, maxSections: Int): Long {
        val id = tableDao.insert(TimeTableEntity(name = name, startDate = startDate, totalWeeks = totalWeeks, maxSections = maxSections))
        sectionDao.insertAll(defaultSectionTimes(id, maxSections))
        return id
    }

    suspend fun updateTable(table: TimeTableEntity) = tableDao.update(table)

    /** 删除课表，返回删除后应切换到的新课表 id（没有则 -1） */
    suspend fun deleteTable(id: Long): Long {
        tableDao.deleteById(id)
        return tableDao.firstId() ?: -1L
    }

    suspend fun saveCourse(course: CourseEntity, slots: List<TimeSlotEntity>): Long {
        val courseId = if (course.id == 0L) {
            courseDao.insert(course)
        } else {
            courseDao.update(course)
            course.id
        }
        slotDao.deleteByCourse(courseId)
        slotDao.insertAll(slots.map { it.copy(id = 0, courseId = courseId) })
        return courseId
    }

    suspend fun deleteCourse(id: Long) = courseDao.deleteById(id)

    suspend fun getCourseWithSlots(courseId: Long): CourseWithSlots? {
        val c = courseDao.getById(courseId) ?: return null
        return CourseWithSlots(c, slotDao.getByCourse(courseId))
    }

    suspend fun saveSectionTimes(tableId: Long, items: List<SectionTimeEntity>) {
        sectionDao.insertAll(items)
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
