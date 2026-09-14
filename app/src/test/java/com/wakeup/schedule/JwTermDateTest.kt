package com.wakeup.schedule

import com.wakeup.schedule.core.jw.JwTermDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class JwTermDateTest {

    @Test
    fun `秋季学期推算到九月一日所在周的周一`() {
        // 2026-09-01 是周二 → 所在周周一为 2026-08-31
        assertEquals("2026-08-31", JwTermDate.guessStartDate("2026-2027-1"))
    }

    @Test
    fun `春季学期推算到次年二月二十日所在周的周一`() {
        // 2027-02-20 是周六 → 所在周周一为 2027-02-15
        assertEquals("2027-02-15", JwTermDate.guessStartDate("2026-2027-2"))
    }

    @Test
    fun `结果一定是周一`() {
        listOf("2025-2026-1", "2025-2026-2", "2030-2031-1").forEach { term ->
            val date = LocalDate.parse(JwTermDate.guessStartDate(term))
            assertEquals(DayOfWeek.MONDAY, date.dayOfWeek)
        }
    }

    @Test
    fun `编号不识别时退回本周周一而不是崩溃`() {
        val today = LocalDate.of(2026, 9, 14) // 周一
        val result = JwTermDate.guessStartDate("第一学期", today)
        assertEquals("2026-09-14", result)
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `允许编号里带空格等噪声`() {
        assertEquals("2026-08-31", JwTermDate.guessStartDate(" 2026 - 2027 - 1 "))
    }
}
