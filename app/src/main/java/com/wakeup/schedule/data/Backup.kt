package com.wakeup.schedule.data

import com.google.gson.Gson
import com.wakeup.schedule.core.ShareCodec

/** 备份 JSON 结构（保持字段名稳定，便于跨版本兼容） */
data class BackupDto(
    val version: Int = 1,
    val name: String,
    val startDate: String,
    val totalWeeks: Int,
    val maxSections: Int,
    val sectionTimes: List<BackupSectionTime>,
    val courses: List<BackupCourse>,
    // v2 起附带外观（旧备份缺失时为 null，导入走默认值）
    val showWeekend: Boolean? = null,
    val blockAlpha: Float? = null,
    val bgType: Int? = null,
    val bgValue: String? = null
)

data class BackupSectionTime(val section: Int, val start: String, val end: String)

data class BackupCourse(
    val name: String,
    val teacher: String,
    val color: Int,
    val note: String,
    val slots: List<BackupSlot>
)

data class BackupSlot(
    val dayOfWeek: Int,
    val startSection: Int,
    val sectionCount: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int,
    val location: String
)

/** 课表的导出 / 导入（JSON 文件 + 分享口令） */
class BackupManager(private val repo: Repository) {

    private val gson = Gson()

    suspend fun export(tableId: Long): String? {
        val table = repo.getTable(tableId) ?: return null
        val db = AppDatabase.get(repo.context)
        val directCourses = db.courseDao().getByTable(tableId)
        val slotsByCourse = db.timeSlotDao().getByTable(tableId).groupBy { it.courseId }
        val courses = directCourses.map { c ->
            BackupCourse(
                name = c.name,
                teacher = c.teacher,
                color = c.color,
                note = c.note,
                slots = slotsByCourse[c.id].orEmpty().map {
                    BackupSlot(it.dayOfWeek, it.startSection, it.sectionCount, it.startWeek, it.endWeek, it.weekType, it.location)
                }
            )
        }
        val times = db.sectionTimeDao().getByTable(tableId).map { BackupSectionTime(it.section, it.start, it.end) }
        return gson.toJson(
            BackupDto(
                name = table.name,
                startDate = table.startDate,
                totalWeeks = table.totalWeeks,
                maxSections = table.maxSections,
                sectionTimes = times,
                courses = courses,
                showWeekend = table.showWeekend,
                blockAlpha = table.blockAlpha,
                bgType = table.bgType,
                bgValue = table.bgValue
            )
        )
    }

    /** 导入为新课表，返回新课表 id */
    suspend fun import(json: String): Long {
        val dto = gson.fromJson(json, BackupDto::class.java)
            ?: throw IllegalArgumentException("备份文件格式不正确")
        val tableId = repo.createTable(dto.name, dto.startDate, dto.totalWeeks, dto.maxSections)
        // 应用外观（若备份中包含）
        if (dto.showWeekend != null || dto.blockAlpha != null || dto.bgType != null) {
            repo.getTable(tableId)?.let { t ->
                repo.updateTable(
                    t.copy(
                        showWeekend = dto.showWeekend ?: true,
                        blockAlpha = dto.blockAlpha?.coerceIn(0.3f, 1f) ?: 0.95f,
                        bgType = dto.bgType ?: 0,
                        bgValue = dto.bgValue ?: ""
                    )
                )
            }
        }
        if (dto.sectionTimes.isNotEmpty()) {
            repo.saveSectionTimes(
                tableId,
                dto.sectionTimes.map { SectionTimeEntity(tableId, it.section, it.start, it.end) }
            )
        }
        dto.courses.forEach { c ->
            repo.saveCourse(
                CourseEntity(tableId = tableId, name = c.name, teacher = c.teacher, color = c.color, note = c.note),
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
        return tableId
    }

    /** 分享口令 = "WKUP:" + Base64(JSON) */
    suspend fun exportShareCode(tableId: Long): String? {
        val json = export(tableId) ?: return null
        return ShareCodec.encode(json)
    }

    suspend fun importShareCode(code: String): Long = import(ShareCodec.decode(code))
}
