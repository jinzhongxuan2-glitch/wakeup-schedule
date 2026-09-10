package com.wakeup.schedule

import com.wakeup.schedule.core.ShareCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCodecTest {

    @Test
    fun `编码带前缀且可完整解码`() {
        val json = """{"name":"大三上","courses":[{"name":"高等数学A(下)","teacher":"王建国"}]}"""
        val code = ShareCodec.encode(json)
        assertTrue(code.startsWith(ShareCodec.PREFIX))
        assertEquals(json, ShareCodec.decode(code))
    }

    @Test
    fun `解码容忍首尾空白`() {
        val json = """{"a":1}"""
        val code = "  " + ShareCodec.encode(json) + "\n"
        assertEquals(json, ShareCodec.decode(code))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非本应用口令抛异常`() {
        ShareCodec.decode("SUPER:xxxx")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `损坏的Base64抛异常`() {
        ShareCodec.decode("WKUP:!!!not-base64!!!")
    }
}
