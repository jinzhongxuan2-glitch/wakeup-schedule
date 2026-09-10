package com.wakeup.schedule

import com.wakeup.schedule.core.ScheduleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleMathTest {

    private val start = LocalDate.of(2026, 8, 31) // 周一
    private val total = 20

    // ---------- currentWeek ----------

    @Test
    fun `开学当天是第1周`() {
        assertEquals(1, ScheduleMath.currentWeek(start, start, total))
    }

    @Test
    fun `开学当周周日仍是第1周`() {
        assertEquals(1, ScheduleMath.currentWeek(start, start.plusDays(6), total))
    }

    @Test
    fun `下个周一是第2周`() {
        assertEquals(2, ScheduleMath.currentWeek(start, start.plusDays(7), total))
    }

    @Test
    fun `开学前日期夹取为第1周`() {
        assertEquals(1, ScheduleMath.currentWeek(start, start.minusDays(30), total))
    }

    @Test
    fun `学期结束后夹取为总周数`() {
        assertEquals(total, ScheduleMath.currentWeek(start, start.plusDays(500), total))
    }

    @Test
    fun `学期最后一天仍是最后一周`() {
        assertEquals(total, ScheduleMath.currentWeek(start, start.plusDays(total * 7L - 1), total))
    }

    // ---------- isInTerm ----------

    @Test
    fun `学期边界判断`() {
        assertFalse(ScheduleMath.isInTerm(start, start.minusDays(1), total))
        assertTrue(ScheduleMath.isInTerm(start, start, total))
        assertTrue(ScheduleMath.isInTerm(start, start.plusDays(total * 7L - 1), total))
        assertFalse(ScheduleMath.isInTerm(start, start.plusDays(total * 7L), total))
    }

    // ---------- mondayOfWeek ----------

    @Test
    fun `第1周周一即开学日`() {
        assertEquals(start, ScheduleMath.mondayOfWeek(start, 1))
    }

    @Test
    fun `第3周周一为开学日+14天`() {
        assertEquals(start.plusDays(14), ScheduleMath.mondayOfWeek(start, 3))
    }

    // ---------- visibleInWeek ----------

    @Test
    fun `起止周之外的周不可见`() {
        assertFalse(ScheduleMath.visibleInWeek(2, 3, 16, ScheduleMath.WEEK_ALL))
        assertFalse(ScheduleMath.visibleInWeek(17, 3, 16, ScheduleMath.WEEK_ALL))
        assertTrue(ScheduleMath.visibleInWeek(3, 3, 16, ScheduleMath.WEEK_ALL))
    }

    @Test
    fun `单双周过滤`() {
        assertTrue(ScheduleMath.visibleInWeek(3, 1, 20, ScheduleMath.WEEK_ODD))
        assertFalse(ScheduleMath.visibleInWeek(4, 1, 20, ScheduleMath.WEEK_ODD))
        assertTrue(ScheduleMath.visibleInWeek(4, 1, 20, ScheduleMath.WEEK_EVEN))
        assertFalse(ScheduleMath.visibleInWeek(3, 1, 20, ScheduleMath.WEEK_EVEN))
        assertTrue(ScheduleMath.visibleInWeek(3, 1, 20, ScheduleMath.WEEK_ALL))
    }

    // ---------- startDateForCurrentWeek ----------

    @Test
    fun `修改当前周反推开学日期`() {
        val today = LocalDate.of(2026, 9, 9) // 周三
        // 设为第2周 → 本周周一(9-7) 往前 1 周 = 8-31
        assertEquals(LocalDate.of(2026, 8, 31), ScheduleMath.startDateForCurrentWeek(today, 2))
        // 设为第1周 → 本周周一
        assertEquals(LocalDate.of(2026, 9, 7), ScheduleMath.startDateForCurrentWeek(today, 1))
        // 周日也要归到本周周一：2026-9-13 是周日
        assertEquals(LocalDate.of(2026, 9, 7), ScheduleMath.startDateForCurrentWeek(LocalDate.of(2026, 9, 13), 1))
    }
}
