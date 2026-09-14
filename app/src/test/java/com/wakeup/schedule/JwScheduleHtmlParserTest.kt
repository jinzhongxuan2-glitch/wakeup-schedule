package com.wakeup.schedule

import com.wakeup.schedule.core.ScheduleMath
import com.wakeup.schedule.core.jw.JwDebugLog
import com.wakeup.schedule.core.jw.JwErrorKind
import com.wakeup.schedule.core.jw.JwException
import com.wakeup.schedule.core.jw.JwScheduleHtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用与真实强智页面同构的样本验证解析逻辑。
 * 覆盖：表头偏移、周日在前、单双周、逗号周次、一格多课、节次兜底、学期下拉框。
 */
class JwScheduleHtmlParserTest {

    private fun page(rowsHtml: String, header: String = defaultHeader) = """
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gb2312"></head><body>
        <select name="xnxq01id" id="xnxq01id">
          <option value="0">请选择</option>
          <option value="2026-2027-1" selected>2026-2027-1</option>
          <option value="2025-2026-2">2025-2026-2</option>
        </select>
        <table id="kbtable" class="displayTag">
        $header
        $rowsHtml
        </table>
        </body></html>
    """.trimIndent()

    private val defaultHeader =
        "<tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th></tr>"

    private fun cell(name: String, teacher: String, weeks: String, room: String, sections: String = "") =
        """<div class="kbcontent">$name<br><font title="老师">$teacher</font><br>""" +
            """<font title="周次(节次)">$weeks${if (sections.isBlank()) "" else " [${sections}节]"}</font><br>""" +
            """<font title="教室">$room</font></div>"""

    @Test
    fun `解析基本课表：星期、节次、周次、地点、教师`() {
        val html = page(
            """
            <tr><th>1-2</th><td>${cell("高等数学A(下)", "王建国", "1-16(周)", "教一-201", "1-2")}</td>${"<td></td>".repeat(6)}</tr>
            <tr><th>3-4</th><td></td><td>${cell("数据结构与算法", "李楠", "1-8,10-12(周)", "计科楼-305", "3-4")}</td>${"<td></td>".repeat(5)}</tr>
            """.trimIndent()
        )

        val result = JwScheduleHtmlParser.parse(html)

        val math = result.first { it.name == "高等数学A(下)" }
        assertEquals("王建国", math.teacher)
        assertEquals(1, math.slots.size)
        with(math.slots[0]) {
            assertEquals(1, dayOfWeek)          // 星期一
            assertEquals(1, startSection)
            assertEquals(2, sectionCount)
            assertEquals(1, startWeek)
            assertEquals(16, endWeek)
            assertEquals("教一-201", location)
            assertEquals(ScheduleMath.WEEK_ALL, weekType)
        }

        // 逗号周次 → 拆成两个时间段
        val ds = result.first { it.name == "数据结构与算法" }
        assertEquals(2, ds.slots.size)
        assertEquals(1 to 8, ds.slots[0].startWeek to ds.slots[0].endWeek)
        assertEquals(10 to 12, ds.slots[1].startWeek to ds.slots[1].endWeek)
        assertEquals(2, ds.slots[0].dayOfWeek)  // 星期二
    }

    @Test
    fun `同一格里的多门课都能解析出来`() {
        val twoCourses = """
            <div class="kbcontent">大学物理<br><font title="老师">陈涛</font><br><font title="周次(节次)">1-16(周) [7-8节]</font><br><font title="教室">理科楼-201</font></div>
            ---------------------
            <div class="kbcontent">形势与政策<br><font title="老师">张思远</font><br><font title="周次(节次)">1-8(周) [7-8节]</font><br><font title="教室">阶梯教室A</font></div>
        """.trimIndent()
        val html = page("<tr><th>7-8</th><td>$twoCourses</td>${"<td></td>".repeat(6)}</tr>")

        val result = JwScheduleHtmlParser.parse(html)
        assertEquals(2, result.size)
        assertNotNull(result.find { it.name == "大学物理" })
        assertNotNull(result.find { it.name == "形势与政策" })
    }

    @Test
    fun `单双周识别正确`() {
        val html = page(
            """
            <tr><th>3-4</th><td>${cell("形势与政策", "张思远", "1-8(周) 单周", "阶梯教室A", "3-4")}</td>${"<td></td>".repeat(6)}</tr>
            <tr><th>5-6</th><td>${cell("操作系统实验", "赵启明", "1-16(周) 双周", "实验楼-C101", "5-6")}</td>${"<td></td>".repeat(6)}</tr>
            """.trimIndent()
        )
        val result = JwScheduleHtmlParser.parse(html)
        assertEquals(ScheduleMath.WEEK_ODD, result.first { it.name == "形势与政策" }.slots[0].weekType)
        assertEquals(ScheduleMath.WEEK_EVEN, result.first { it.name == "操作系统实验" }.slots[0].weekType)
    }

    @Test
    fun `节次只写在行首节次列时用行节次兜底`() {
        // 单元格里没有 [x-y节]，只有周次
        val noSection = """<div class="kbcontent">大学英语<br><font title="老师">Alice</font><br><font title="周次(节次)">1-16(周)</font><br><font title="教室">外语楼-412</font></div>"""
        val html = page("<tr><th>5-6</th><td>$noSection</td>${"<td></td>".repeat(6)}</tr>")

        val result = JwScheduleHtmlParser.parse(html)
        val en = result.first { it.name == "大学英语" }
        assertEquals(5, en.slots[0].startSection)
        assertEquals(2, en.slots[0].sectionCount)
    }

    @Test
    fun `紧凑节次写法 0102节 正确解析`() {
        val compact = """<div class="kbcontent">线性代数<br><font title="老师">周敏</font><br><font title="周次(节次)">4-18(周) [0102节]</font><br><font title="教室">教一-305</font></div>"""
        val html = page("<tr><th>1-2</th><td>$compact</td>${"<td></td>".repeat(6)}</tr>")
        val result = JwScheduleHtmlParser.parse(html)
        assertEquals(1, result[0].slots[0].startSection)
        assertEquals(2, result[0].slots[0].sectionCount)
    }

    @Test
    fun `表格没有节次列时列索引不错位`() {
        val headerNoSection =
            "<tr><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>"
        val html = page(
            "<tr><td>${cell("体育(四)", "刘洋", "1-16(周)", "体育馆", "1-2")}</td>${"<td></td>".repeat(4)}</tr>",
            header = headerNoSection
        )
        val result = JwScheduleHtmlParser.parse(html)
        assertEquals(1, result[0].slots[0].dayOfWeek) // 第一列是周一，不能错位成周二
    }

    @Test
    fun `周日在前面的表头也能正确映射星期`() {
        val headerSunFirst =
            "<tr><th>节次</th><th>星期日</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th><th>星期六</th></tr>"
        val html = page(
            "<tr><th>1-2</th><td>${cell("周日课", "甲", "1-16(周)", "A", "1-2")}</td><td>${cell("周一课", "乙", "1-16(周)", "B", "1-2")}</td>${"<td></td>".repeat(5)}</tr>",
            header = headerSunFirst
        )
        val result = JwScheduleHtmlParser.parse(html)
        assertEquals(7, result.first { it.name == "周日课" }.slots[0].dayOfWeek)
        assertEquals(1, result.first { it.name == "周一课" }.slots[0].dayOfWeek)
    }

    @Test
    fun `同名课程多个时间段合并成一门课`() {
        val html = page(
            """
            <tr><th>1-2</th><td>${cell("高等数学A(下)", "王建国", "1-16(周)", "教一-201", "1-2")}</td>${"<td></td>".repeat(6)}</tr>
            <tr><th>3-4</th><td></td><td></td><td>${cell("高等数学A(下)", "王建国", "1-16(周)", "教一-201", "3-4")}</td>${"<td></td>".repeat(4)}</tr>
            """.trimIndent()
        )
        val result = JwScheduleHtmlParser.parse(html)
        assertEquals(1, result.size)
        assertEquals(2, result[0].slots.size)
    }

    @Test
    fun `教学资料等非课程条目被排除`() {
        val html = page("<tr><th>1-2</th><td>${cell("教学资料", "x", "1-16(周)", "y", "1-2")}</td>${"<td></td>".repeat(6)}</tr>")
        var thrown = false
        try {
            JwScheduleHtmlParser.parse(html)
        } catch (e: JwException) {
            thrown = true
            assertEquals(JwErrorKind.SCHEDULE_PARSE_FAILED, e.kind)
        }
        assertTrue("全是无效条目时应报「解析不出课程」", thrown)
    }

    @Test
    fun `空课表报出可读错误而不是返回空列表`() {
        val html = page("<tr><th>1-2</th>${"<td></td>".repeat(7)}</tr>")
        var thrown = false
        try {
            JwScheduleHtmlParser.parse(html)
        } catch (e: JwException) {
            thrown = true
            assertTrue(e.message!!.contains("空") || e.message!!.contains("没有"))
        }
        assertTrue(thrown)
    }

    @Test
    fun `解析学期下拉框`() {
        val terms = JwScheduleHtmlParser.parseTerms(page(""))
        assertEquals(2, terms.size) // 值为 0 的「请选择」被过滤
        assertEquals("2026-2027-1", terms[0].id)
        assertEquals("2025-2026-2", terms[1].id)
    }

    @Test
    fun `识别课表页与iframe嵌套`() {
        assertTrue(JwScheduleHtmlParser.looksLikeSchedulePage(page("")))
        assertFalse(JwScheduleHtmlParser.looksLikeSchedulePage("<html><body>登录</body></html>"))

        val framed = """<html><body><iframe src="/jsxsd/xskb/xskb_list.do?term=1"></iframe></body></html>"""
        assertEquals("/jsxsd/xskb/xskb_list.do?term=1", JwScheduleHtmlParser.findInnerFrameUrl(framed))
    }

    @Test
    fun `解析过程写入调试日志`() {
        val log = JwDebugLog()
        val html = page("<tr><th>1-2</th><td>${cell("高等数学", "王", "1-16(周)", "教一", "1-2")}</td>${"<td></td>".repeat(6)}</tr>")
        JwScheduleHtmlParser.parse(html, log)
        assertTrue(log.text().contains("表头侦测"))
        assertTrue(log.text().contains("合并为"))
    }
}
