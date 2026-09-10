package com.wakeup.schedule

import com.wakeup.schedule.core.CsvScheduleParser
import com.wakeup.schedule.core.ScheduleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvScheduleParserTest {

    @Test
    fun `同名课程合并为多时间段`() {
        val csv = """
            课程名,星期,开始节,连上节数,开始周,结束周,单双周,地点,教师
            高等数学,1,1,2,1,16,每周,教一101,王建国
            高等数学,3,3,2,1,16,每周,教一101,王建国
        """.trimIndent()
        val r = CsvScheduleParser.parse(csv)
        assertEquals(0, r.errors.size)
        assertEquals(1, r.courses.size)
        assertEquals(2, r.courses[0].slots.size)
        assertEquals("王建国", r.courses[0].teacher)
    }

    @Test
    fun `中文星期与单双周识别`() {
        val csv = "形势与政策,周一,9,2,1,8,单周,阶梯教室A,张思远\n操作系统实验,周四,7,2,3,17,双周,实验楼,赵启明"
        val r = CsvScheduleParser.parse(csv)
        assertEquals(2, r.courses.size)
        assertEquals(ScheduleMath.WEEK_ODD, r.courses[0].slots[0].weekType)
        assertEquals(ScheduleMath.WEEK_EVEN, r.courses[1].slots[0].weekType)
        assertEquals(4, r.courses[1].slots[0].dayOfWeek)
    }

    @Test
    fun `数字单双周与全角逗号兼容`() {
        val csv = "体育课，2，5，2，1，16，0，体育馆，刘洋"
        val r = CsvScheduleParser.parse(csv)
        assertEquals(0, r.errors.size)
        assertEquals(ScheduleMath.WEEK_ALL, r.courses[0].slots[0].weekType)
    }

    @Test
    fun `注释空行与表头被跳过`() {
        val csv = """
            # 这是注释
            课程名,星期,开始节,连上节数,开始周,结束周,单双周,地点,教师

            线性代数,4,3,2,1,16,每周,教一305,周敏
        """.trimIndent()
        val r = CsvScheduleParser.parse(csv)
        assertEquals(1, r.courses.size)
        assertEquals("线性代数", r.courses[0].name)
    }

    @Test
    fun `坏行进错误列表但不中断解析`() {
        val csv = "坏行,abc\n线性代数,4,3,2,1,16,每周,教一305,周敏"
        val r = CsvScheduleParser.parse(csv)
        assertEquals(1, r.courses.size)
        assertTrue(r.errors.isNotEmpty())
    }

    @Test
    fun `结束周早于开始周时自动纠正`() {
        val csv = "测试课,1,1,2,10,5,每周,教室,老师"
        val r = CsvScheduleParser.parse(csv)
        assertEquals(10, r.courses[0].slots[0].startWeek)
        assertEquals(10, r.courses[0].slots[0].endWeek)
    }
}
