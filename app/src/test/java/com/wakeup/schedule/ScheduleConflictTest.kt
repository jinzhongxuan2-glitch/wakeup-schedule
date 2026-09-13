package com.wakeup.schedule

import com.wakeup.schedule.core.ScheduleMath
import com.wakeup.schedule.core.ScheduleMath.SlotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleConflictTest {

    private val total = 20

    private fun slot(
        day: Int = 1,
        start: Int = 1,
        count: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = ScheduleMath.WEEK_ALL
    ) = SlotKey(day, start, count, startWeek, endWeek, weekType)

    @Test
    fun `不同天不冲突`() {
        assertFalse(ScheduleMath.conflicts(slot(day = 1), slot(day = 2), total))
    }

    @Test
    fun `节次区间相交即冲突`() {
        assertTrue(ScheduleMath.conflicts(slot(start = 1, count = 2), slot(start = 2, count = 2), total))
        assertTrue(ScheduleMath.conflicts(slot(start = 3, count = 3), slot(start = 5, count = 1), total))
        assertFalse(ScheduleMath.conflicts(slot(start = 1, count = 2), slot(start = 3, count = 2), total))
    }

    @Test
    fun `周次不重叠不冲突`() {
        // 1-8 周 vs 9-16 周
        assertFalse(ScheduleMath.conflicts(slot(startWeek = 1, endWeek = 8), slot(startWeek = 9, endWeek = 16), total))
    }

    @Test
    fun `单双周相互错开不算冲突`() {
        // 同一时间：单周 1-16 vs 双周 1-16 → 永不同时出现
        val odd = slot(weekType = ScheduleMath.WEEK_ODD)
        val even = slot(weekType = ScheduleMath.WEEK_EVEN)
        assertFalse(ScheduleMath.conflicts(odd, even, total))
        // 同一天的相邻单双周各自与「每周」冲突
        assertTrue(ScheduleMath.conflicts(odd, slot(), total))
        assertTrue(ScheduleMath.conflicts(even, slot(), total))
    }

    @Test
    fun `重叠周次列表准确`() {
        val a = slot(startWeek = 1, endWeek = 8)
        val b = slot(startWeek = 5, endWeek = 12)
        assertEquals(listOf(5, 6, 7, 8), ScheduleMath.overlappingWeeks(a, b, total))
    }

    @Test
    fun `单双周与起止周共同过滤重叠周`() {
        val a = slot(startWeek = 1, endWeek = 10, weekType = ScheduleMath.WEEK_ODD)
        val b = slot(startWeek = 4, endWeek = 8, weekType = ScheduleMath.WEEK_ALL)
        // 4..8 中的单周：5、7
        assertEquals(listOf(5, 7), ScheduleMath.overlappingWeeks(a, b, total))
    }

    @Test
    fun `跨节次的长课与短课正确判冲突`() {
        val long = slot(start = 1, count = 4)   // 1-4 节
        val short = slot(start = 4, count = 1)  // 第 4 节
        assertTrue(ScheduleMath.conflicts(long, short, total))
        assertEquals(4, long.endSection)
    }

    @Test
    fun `开学日归一化到周一`() {
        assertEquals(LocalDate.of(2026, 8, 31), ScheduleMath.normalizeToMonday(LocalDate.of(2026, 9, 2)))
        // 周一本身不变
        assertEquals(LocalDate.of(2026, 8, 31), ScheduleMath.normalizeToMonday(LocalDate.of(2026, 8, 31)))
        // 周日归到本周周一
        assertEquals(LocalDate.of(2026, 9, 7), ScheduleMath.normalizeToMonday(LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `字符串开学日归一化与非法值兜底`() {
        assertEquals("2026-08-31", ScheduleMath.normalizeToMonday("2026-09-02"))
        // 非法值不崩溃，退回今天所在周的周一
        val fallback = ScheduleMath.normalizeToMonday("not-a-date")
        assertEquals(1, LocalDate.parse(fallback).dayOfWeek.value)
    }
}
