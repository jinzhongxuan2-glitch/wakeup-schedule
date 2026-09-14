package com.wakeup.schedule

import com.wakeup.schedule.core.ProbeSample
import com.wakeup.schedule.core.UpdateSourceProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourceProbeTest {

    private fun sample(label: String, kbPerSec: Long, millis: Long = 1000) =
        ProbeSample(label, "https://example.com/$label.apk", kbPerSec * 1024 * millis / 1000, millis)

    @Test
    fun `挑出速度最快的源`() {
        val samples = listOf(
            sample("CDN", 320),
            sample("镜像", 8),
            sample("官方", 1)
        )
        val best = UpdateSourceProbe.pickFastest(samples)!!
        assertEquals("CDN", best.label)
    }

    @Test
    fun `失败的源不参与竞争`() {
        val samples = listOf(
            ProbeSample("CDN", "u1", 0, 1),      // 失败
            sample("镜像", 60)
        )
        assertEquals("镜像", UpdateSourceProbe.pickFastest(samples)!!.label)
    }

    @Test
    fun `全部慢于阈值时返回 null（交给调用方按默认顺序尝试）`() {
        val samples = listOf(sample("CDN", 5), sample("镜像", 3), sample("官方", 1))
        assertNull(UpdateSourceProbe.pickFastest(samples))
    }

    @Test
    fun `阈值可调整`() {
        val samples = listOf(sample("CDN", 5))
        assertNull(UpdateSourceProbe.pickFastest(samples))
        assertEquals("CDN", UpdateSourceProbe.pickFastest(samples, minBps = 1024)!!.label)
    }

    @Test
    fun `空列表不会崩`() {
        assertNull(UpdateSourceProbe.pickFastest(emptyList()))
        assertEquals("", UpdateSourceProbe.summary(emptyList()))
    }

    @Test
    fun `速度计算正确且不除以零`() {
        // 512 KB / 2 秒 = 256 KB/s
        val s = ProbeSample("CDN", "u", 512 * 1024, 2000)
        assertEquals(256 * 1024, s.speedBps)
        assertEquals(0, ProbeSample("x", "u", 100, 0).speedBps)
    }

    @Test
    fun `测速摘要可读`() {
        val samples = listOf(
            sample("CDN", 320),
            sample("镜像", 8),
            ProbeSample("官方", "u", 0, 1)
        )
        val text = UpdateSourceProbe.summary(samples)
        assertTrue(text.contains("CDN 320 KB/s"))
        assertTrue(text.contains("镜像 8 KB/s"))
        assertTrue(text.contains("官方 失败"))
    }

    @Test
    fun `速度单位格式化`() {
        assertEquals("512 B/s", UpdateSourceProbe.fmtSpeed(512))
        assertEquals("256 KB/s", UpdateSourceProbe.fmtSpeed(256 * 1024))
        assertEquals("1.5 MB/s", UpdateSourceProbe.fmtSpeed((1.5 * 1024 * 1024).toLong()))
    }
}
