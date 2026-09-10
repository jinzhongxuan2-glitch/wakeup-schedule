package com.wakeup.schedule.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
}
