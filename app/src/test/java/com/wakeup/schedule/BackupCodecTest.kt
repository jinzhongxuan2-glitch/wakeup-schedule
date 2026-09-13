package com.wakeup.schedule

import com.wakeup.schedule.core.BackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun sampleTable() = BackupCodec.TableDto(
        name = "大三上",
        startDate = "2026-08-31",
        totalWeeks = 20,
        maxSections = 12,
        sectionTimes = listOf(BackupCodec.SectionTimeDto(1, "08:00", "08:45")),
        courses = listOf(
            BackupCodec.CourseDto(
                name = "高等数学A(下)",
                teacher = "王建国",
                color = -217802,
                note = "带教材",
                slots = listOf(BackupCodec.SlotDto(1, 1, 2, 1, 16, 0, "教一-201"))
            )
        ),
        showWeekend = true,
        blockAlpha = 0.9f,
        bgType = 1,
        bgValue = "-1"
    )

    @Test
    fun `单课表备份往返不丢字段`() {
        val json = BackupCodec.encodeTable(sampleTable())
        val back = BackupCodec.decode(json)
        assertEquals(1, back.size)
        val t = back.first()
        assertEquals("大三上", t.name)
        assertEquals(20, t.totalWeeks)
        assertEquals(1, t.courses.size)
        assertEquals("高等数学A(下)", t.courses[0].name)
        assertEquals(1, t.courses[0].slots.size)
        assertEquals("教一-201", t.courses[0].slots[0].location)
        assertEquals(1, t.bgType)
        assertEquals(0.9f, t.blockAlpha!!, 1e-6f)
    }

    @Test
    fun `全量备份包含多张课表`() {
        val json = BackupCodec.encodeBundle(
            listOf(sampleTable(), sampleTable().copy(name = "考研复习"))
        )
        val back = BackupCodec.decode(json)
        assertEquals(2, back.size)
        assertEquals("考研复习", back[1].name)
    }

    @Test
    fun `旧版备份缺少外观字段时走默认值`() {
        // 模拟 v1 备份：没有 showWeekend / blockAlpha / bgType / bgValue
        val legacy = """
            {"version":1,"name":"旧课表","startDate":"2026-08-31","totalWeeks":18,
             "maxSections":10,"sectionTimes":[],"courses":[]}
        """.trimIndent()
        val t = BackupCodec.decode(legacy).first()
        assertEquals("旧课表", t.name)
        assertNull(t.blockAlpha)
        assertNull(t.bgType)
        // sanitize 后拿到安全默认值
        val safe = BackupCodec.sanitize(t)
        assertEquals(0.95f, safe.blockAlpha!!, 1e-6f)
        assertEquals(18, safe.totalWeeks)
    }

    @Test
    fun `非法开学日期被归一到周一`() {
        val t = BackupCodec.TableDto(name = "乱日期", startDate = "2026-09-02") // 周三
        assertEquals("2026-08-31", BackupCodec.sanitize(t).startDate)
    }

    @Test
    fun `损坏的文本抛出可读异常`() {
        var thrown = false
        try {
            BackupCodec.decode("这不是 JSON")
        } catch (e: IllegalArgumentException) {
            thrown = true
            assertTrue(e.message!!.contains("JSON"))
        }
        assertTrue(thrown)
    }

    @Test
    fun `空对象被拒绝而不是静默产生空课表`() {
        var thrown = false
        try {
            BackupCodec.decode("{}")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
