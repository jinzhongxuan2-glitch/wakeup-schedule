package com.wakeup.schedule.core

/**
 * Excel/CSV 课表模板解析器（纯 JVM，可单测）。
 *
 * 每行一门课的一个时间段，同名课程自动合并：
 *   课程名,星期,开始节,连上节数,开始周,结束周,单双周,地点,教师
 * - 星期：1-7 或 周一..周日
 * - 单双周：0/1/2 或 每周/单周/双周
 * - `#` 开头为注释，空行跳过，表头行自动跳过
 */
object CsvScheduleParser {

    data class ParsedSlot(
        val dayOfWeek: Int,
        val startSection: Int,
        val sectionCount: Int,
        val startWeek: Int,
        val endWeek: Int,
        val weekType: Int,
        val location: String
    )

    data class ParsedCourse(
        val name: String,
        val teacher: String,
        val slots: List<ParsedSlot>
    )

    data class ParseResult(
        val courses: List<ParsedCourse>,
        val errors: List<String>
    )

    private val DAY_NAMES = mapOf(
        "周一" to 1, "周二" to 2, "周三" to 3, "周四" to 4,
        "周五" to 5, "周六" to 6, "周日" to 7, "周天" to 7
    )

    fun parse(text: String): ParseResult {
        val errors = mutableListOf<String>()
        val courseMap = linkedMapOf<String, Pair<String, MutableList<ParsedSlot>>>()

        text.lines().forEachIndexed { idx, raw ->
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cols = line.split(',', '，', '\t').map { it.trim() }
            if (cols.size < 7) {
                errors.add("第${idx + 1}行：列数不足（需至少7列）")
                return@forEachIndexed
            }
            // 跳过表头
            if (cols[0] == "课程名" || cols[1] == "星期") return@forEachIndexed

            val name = cols[0]
            if (name.isBlank()) {
                errors.add("第${idx + 1}行：课程名为空")
                return@forEachIndexed
            }
            val day = cols[1].toIntOrNull() ?: DAY_NAMES[cols[1]]
            if (day == null || day !in 1..7) {
                errors.add("第${idx + 1}行：星期「${cols[1]}」无法识别")
                return@forEachIndexed
            }
            val startSection = cols[2].toIntOrNull()
            val sectionCount = cols[3].toIntOrNull() ?: 2
            val startWeek = cols[4].toIntOrNull()
            val endWeek = cols[5].toIntOrNull()
            if (startSection == null || startSection < 1 || startWeek == null || endWeek == null) {
                errors.add("第${idx + 1}行：节次/周数必须是数字")
                return@forEachIndexed
            }
            val weekType = when (cols[6]) {
                "1", "单周", "单" -> ScheduleMath.WEEK_ODD
                "2", "双周", "双" -> ScheduleMath.WEEK_EVEN
                "0", "每周", "" -> ScheduleMath.WEEK_ALL
                else -> {
                    errors.add("第${idx + 1}行：单双周「${cols[6]}」无法识别，已按每周处理")
                    ScheduleMath.WEEK_ALL
                }
            }
            val location = cols.getOrElse(7) { "" }
            val teacher = cols.getOrElse(8) { "" }

            val entry = courseMap.getOrPut(name) { teacher to mutableListOf() }
            entry.second.add(
                ParsedSlot(
                    dayOfWeek = day,
                    startSection = startSection,
                    sectionCount = sectionCount.coerceIn(1, 12),
                    startWeek = startWeek,
                    endWeek = endWeek.coerceAtLeast(startWeek),
                    weekType = weekType,
                    location = location
                )
            )
        }

        return ParseResult(
            courses = courseMap.map { (name, pair) -> ParsedCourse(name, pair.first, pair.second) },
            errors = errors
        )
    }
}
