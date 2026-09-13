package com.wakeup.schedule.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 备份 JSON 编解码（纯 JVM，可单元测试）。
 *
 * 两种格式：
 * 1. 单课表备份（历史格式）：顶层直接是课表字段
 * 2. 全量备份（v2 起）：{ "version":2, "tables":[ 课表… ] }
 *
 * 解析时自动识别，字段缺失一律走默认值，保证旧备份可导入。
 */
object BackupCodec {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    const val FORMAT_VERSION = 2

    // ---------- 传输对象 ----------

    data class SectionTimeDto(
        val section: Int,
        val start: String,
        val end: String
    )

    data class SlotDto(
        val dayOfWeek: Int,
        val startSection: Int,
        val sectionCount: Int,
        val startWeek: Int,
        val endWeek: Int,
        val weekType: Int,
        val location: String = ""
    )

    data class CourseDto(
        val name: String,
        val teacher: String = "",
        val color: Int = 0,
        val note: String = "",
        val slots: List<SlotDto> = emptyList()
    )

    data class TableDto(
        val version: Int = 1,
        val name: String,
        val startDate: String,
        val totalWeeks: Int = 20,
        val maxSections: Int = 12,
        val sectionTimes: List<SectionTimeDto> = emptyList(),
        val courses: List<CourseDto> = emptyList(),
        // 外观（v2 起；旧备份为 null）
        val showWeekend: Boolean? = null,
        val blockAlpha: Float? = null,
        val bgType: Int? = null,
        val bgValue: String? = null
    )

    data class BundleDto(
        val version: Int = FORMAT_VERSION,
        val exportedAt: Long = 0,
        val tables: List<TableDto> = emptyList()
    )

    // ---------- 编码 ----------

    fun encodeTable(table: TableDto): String = gson.toJson(table)

    fun encodeBundle(tables: List<TableDto>, exportedAt: Long = System.currentTimeMillis()): String =
        gson.toJson(BundleDto(version = FORMAT_VERSION, exportedAt = exportedAt, tables = tables))

    // ---------- 解码 ----------

    /**
     * 解析备份文本，返回其中的全部课表。
     * 兼容三种输入：全量备份、单课表备份、以及被编辑器加了空白/BOM 的文本。
     *
     * @throws IllegalArgumentException 文本不是合法备份
     */
    fun decode(text: String): List<TableDto> {
        val clean = text.trim().trimStart('\uFEFF')
        require(clean.startsWith("{")) { "备份内容不是有效的 JSON" }

        // 先尝试全量备份
        runCatching { gson.fromJson(clean, BundleDto::class.java) }
            .getOrNull()
            ?.takeIf { !it.tables.isNullOrEmpty() }
            ?.let { return it.tables }

        // 退回单课表格式
        val single = runCatching { gson.fromJson(clean, TableDto::class.java) }.getOrNull()
        if (single != null && !single.name.isNullOrBlank() && !single.startDate.isNullOrBlank()) {
            return listOf(single)
        }
        throw IllegalArgumentException("备份文件格式不正确，或缺少课表名称/开学日期")
    }

    /** 空课表兜底：字段缺失或越界时给出安全默认值 */
    fun sanitize(table: TableDto): TableDto = table.copy(
        name = table.name.trim().ifBlank { "导入的课表" },
        startDate = ScheduleMath.normalizeToMonday(table.startDate),
        totalWeeks = table.totalWeeks.coerceIn(1, 30),
        maxSections = table.maxSections.coerceIn(1, 16),
        blockAlpha = (table.blockAlpha ?: 0.95f).coerceIn(0.3f, 1f),
        bgType = (table.bgType ?: 0).coerceIn(0, 2),
        bgValue = table.bgValue.orEmpty()
    )
}
