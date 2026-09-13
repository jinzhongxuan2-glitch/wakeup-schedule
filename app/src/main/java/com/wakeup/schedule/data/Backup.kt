package com.wakeup.schedule.data

import com.wakeup.schedule.core.BackupCodec
import com.wakeup.schedule.core.ShareCodec
import kotlinx.coroutines.flow.first

/** 课表的导出 / 导入（单课表备份、全量备份、分享口令） */
class BackupManager(private val repo: Repository) {

    private val db = AppDatabase.get(repo.context)

    // ---------- 导出 ----------

    /** 导出单张课表 */
    suspend fun export(tableId: Long): String? {
        val dto = tableToDto(tableId) ?: return null
        return BackupCodec.encodeTable(dto)
    }

    /** 导出全部课表（含各自的外观、节次时间） */
    suspend fun exportAll(): String {
        // 一次性取快照，避免订阅
        val all = db.timeTableDao().getAllFlow().first()
        val dtos = all.mapNotNull { tableToDto(it.id) }
        return BackupCodec.encodeBundle(dtos)
    }

    private suspend fun tableToDto(tableId: Long): BackupCodec.TableDto? {
        val table = repo.getTable(tableId) ?: return null
        val courses = db.courseDao().getByTable(tableId)
        val slotsByCourse = db.timeSlotDao().getByTable(tableId).groupBy { it.courseId }
        val times = db.sectionTimeDao().getByTable(tableId)
            .map { BackupCodec.SectionTimeDto(it.section, it.start, it.end) }

        return BackupCodec.TableDto(
            name = table.name,
            startDate = table.startDate,
            totalWeeks = table.totalWeeks,
            maxSections = table.maxSections,
            sectionTimes = times,
            courses = courses.map { c ->
                BackupCodec.CourseDto(
                    name = c.name,
                    teacher = c.teacher,
                    color = c.color,
                    note = c.note,
                    slots = slotsByCourse[c.id].orEmpty().map {
                        BackupCodec.SlotDto(
                            it.dayOfWeek, it.startSection, it.sectionCount,
                            it.startWeek, it.endWeek, it.weekType, it.location
                        )
                    }
                )
            },
            showWeekend = table.showWeekend,
            blockAlpha = table.blockAlpha,
            bgType = table.bgType,
            bgValue = table.bgValue
        )
    }

    // ---------- 导入 ----------

    /**
     * 从备份文本导入（自动识别单课表 / 全量备份）。
     * @return 导入的课表 id 列表（全量备份会有多个）
     */
    suspend fun importAll(json: String): List<Long> =
        BackupCodec.decode(json).map { importTable(BackupCodec.sanitize(it)) }

    /** 兼容旧调用：导入并返回第一张课表 id */
    suspend fun import(json: String): Long =
        importAll(json).firstOrNull() ?: throw IllegalArgumentException("备份里没有可导入的课表")

    private suspend fun importTable(dto: BackupCodec.TableDto): Long {
        val tableId = repo.createTable(dto.name, dto.startDate, dto.totalWeeks, dto.maxSections)

        // 应用外观（sanitize 已保证非空；旧备份走默认值）
        repo.getTable(tableId)?.let { t ->
            repo.updateTable(
                t.copy(
                    showWeekend = dto.showWeekend ?: true,
                    blockAlpha = dto.blockAlpha ?: 0.95f,
                    bgType = dto.bgType ?: 0,
                    bgValue = dto.bgValue.orEmpty()
                )
            )
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

    // ---------- 分享口令 ----------

    /** 分享口令 = "WKUP:" + Base64(JSON) */
    suspend fun exportShareCode(tableId: Long): String? {
        val json = export(tableId) ?: return null
        return ShareCodec.encode(json)
    }

    suspend fun importShareCode(code: String): Long = import(ShareCodec.decode(code))
}
