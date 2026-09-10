package com.wakeup.schedule.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 课表（可多个，配置互相独立） */
@Entity(tableName = "timetables")
data class TimeTableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 学期第一周周一，格式 yyyy-MM-dd */
    val startDate: String,
    val totalWeeks: Int = 20,
    val maxSections: Int = 12,
    val createdAt: Long = System.currentTimeMillis(),
    // ---- 外观（v2 新增） ----
    /** 是否显示周末两列 */
    val showWeekend: Boolean = true,
    /** 课程块不透明度 0.3~1.0 */
    val blockAlpha: Float = 0.95f,
    /** 背景类型：0 默认 / 1 纯色 / 2 图片 */
    val bgType: Int = 0,
    /** bgType=1 时为 ARGB 颜色值字符串；bgType=2 时为图片 Uri */
    val bgValue: String = ""
)

/** 一门课程（可含多个上课时间段） */
@Entity(
    tableName = "courses",
    foreignKeys = [ForeignKey(
        entity = TimeTableEntity::class,
        parentColumns = ["id"],
        childColumns = ["tableId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tableId")]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableId: Long,
    val name: String,
    val teacher: String = "",
    /** 课程颜色 ARGB */
    val color: Int,
    val note: String = ""
)

/** 课程的一个上课时间段 */
@Entity(
    tableName = "time_slots",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("courseId")]
)
data class TimeSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    /** 1=周一 … 7=周日 */
    val dayOfWeek: Int,
    val startSection: Int,
    val sectionCount: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    /** 0=每周, 1=单周, 2=双周 */
    val weekType: Int = 0,
    val location: String = ""
)

/** 每节课的上下课时间（每张课表独立） */
@Entity(tableName = "section_times", primaryKeys = ["tableId", "section"])
data class SectionTimeEntity(
    val tableId: Long,
    val section: Int,
    /** HH:mm */
    val start: String,
    val end: String
)

/** 课程 + 时间段 聚合 */
data class CourseWithSlots(
    val course: CourseEntity,
    val slots: List<TimeSlotEntity>
)

/** 周类型工具 */
object WeekType {
    const val ALL = 0
    const val ODD = 1
    const val EVEN = 2

    fun matches(type: Int, week: Int): Boolean = when (type) {
        ODD -> week % 2 == 1
        EVEN -> week % 2 == 0
        else -> true
    }

    fun label(type: Int): String = when (type) {
        ODD -> "单周"
        EVEN -> "双周"
        else -> "每周"
    }
}
