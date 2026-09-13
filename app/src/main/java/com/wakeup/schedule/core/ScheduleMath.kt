package com.wakeup.schedule.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 课表周次计算（纯 JVM 逻辑，不依赖 Android，可直接单元测试）
 */
object ScheduleMath {

    const val WEEK_ALL = 0
    const val WEEK_ODD = 1
    const val WEEK_EVEN = 2

    /** 今天属于第几周（1-based），夹取到 1..totalWeeks */
    fun currentWeek(startDate: LocalDate, today: LocalDate, totalWeeks: Int): Int {
        val days = ChronoUnit.DAYS.between(startDate, today)
        val week = (days / 7).toInt() + 1
        return week.coerceIn(1, totalWeeks)
    }

    /** 今天是否落在学期范围内 */
    fun isInTerm(startDate: LocalDate, today: LocalDate, totalWeeks: Int): Boolean {
        val days = ChronoUnit.DAYS.between(startDate, today)
        return days >= 0 && days < totalWeeks * 7L
    }

    /** 第 week 周周一的日期 */
    fun mondayOfWeek(startDate: LocalDate, week: Int): LocalDate =
        startDate.plusWeeks((week - 1).toLong())

    /** 时间段在给定周是否可见（起止周 + 单双周） */
    fun visibleInWeek(week: Int, startWeek: Int, endWeek: Int, weekType: Int): Boolean {
        if (week !in startWeek..endWeek) return false
        return when (weekType) {
            WEEK_ODD -> week % 2 == 1
            WEEK_EVEN -> week % 2 == 0
            else -> true
        }
    }

    /** 把「今天」设为第 targetWeek 周时，应有的学期开始日期 */
    fun startDateForCurrentWeek(today: LocalDate, targetWeek: Int): LocalDate {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return monday.minusWeeks((targetWeek - 1).toLong())
    }

    /** 把任意日期归一到所在周的周一（开学日必须是周一，否则整个学期周次都会偏移） */
    fun normalizeToMonday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 字符串版本：解析失败时退回今天所在周的周一 */
    fun normalizeToMonday(date: String): String {
        val parsed = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: LocalDate.now()
        return normalizeToMonday(parsed).toString()
    }

    // ---------- 时间冲突检测（纯逻辑，供编辑页与单测使用） ----------

    /** 一个时间段在网格上的占用：星期 + 节次区间 + 周次区间 + 单双周 */
    data class SlotKey(
        val dayOfWeek: Int,
        val startSection: Int,
        val sectionCount: Int,
        val startWeek: Int,
        val endWeek: Int,
        val weekType: Int
    ) {
        val endSection: Int get() = startSection + sectionCount - 1
    }

    /** 两个时间段是否节次重叠（同一天、节次区间相交） */
    fun sectionsOverlap(a: SlotKey, b: SlotKey): Boolean =
        a.dayOfWeek == b.dayOfWeek &&
            a.startSection <= b.endSection &&
            b.startSection <= a.endSection

    /** 两个时间段实际同时可见的周次（已考虑起止周与单双周），空表示不冲突 */
    fun overlappingWeeks(a: SlotKey, b: SlotKey, totalWeeks: Int): List<Int> {
        if (!sectionsOverlap(a, b)) return emptyList()
        return (1..totalWeeks).filter {
            visibleInWeek(it, a.startWeek, a.endWeek, a.weekType) &&
                visibleInWeek(it, b.startWeek, b.endWeek, b.weekType)
        }
    }

    fun conflicts(a: SlotKey, b: SlotKey, totalWeeks: Int): Boolean =
        overlappingWeeks(a, b, totalWeeks).isNotEmpty()
}
