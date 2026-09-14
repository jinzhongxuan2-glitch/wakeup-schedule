package com.wakeup.schedule

import com.wakeup.schedule.core.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private val full = """
        {
          "versionCode": 4,
          "versionName": "1.2.1",
          "apkUrl": "https://github.com/u/r/releases/latest/download/app-release.apk",
          "apkUrlMirror": "https://mirror.example.com/u/r/app-release.apk",
          "notes": "修复更新无反应",
          "force": false
        }
    """.trimIndent()

    @Test
    fun `完整清单解析出全部字段`() {
        val info = UpdateManifest.parse(full)!!
        assertEquals(4, info.versionCode)
        assertEquals("1.2.1", info.versionName)
        assertEquals("修复更新无反应", info.notes)
        assertEquals("https://mirror.example.com/u/r/app-release.apk", info.apkUrlMirror)
        assertFalse(info.force)
    }

    @Test
    fun `镜像字段缺失时只有官方一个候选源`() {
        val noMirror = """{"versionCode":9,"versionName":"9.9.9","apkUrl":"https://a/b.apk"}"""
        val info = UpdateManifest.parse(noMirror)!!
        assertEquals("", info.apkUrlMirror)
        val candidates = UpdateManifest.downloadCandidates(info)
        assertEquals(1, candidates.size)
        assertEquals("GitHub 官方", candidates[0].first)
    }

    @Test
    fun `有镜像时按官方优先、镜像兜底的顺序返回`() {
        val info = UpdateManifest.parse(full)!!
        val candidates = UpdateManifest.downloadCandidates(info)
        assertEquals(2, candidates.size)
        assertEquals("GitHub 官方", candidates[0].first)
        assertEquals("镜像加速", candidates[1].first)
        assertTrue(candidates[0].second.contains("github.com"))
        assertTrue(candidates[1].second.contains("mirror.example.com"))
    }

    @Test
    fun `镜像与官方地址相同时不重复列出`() {
        val same = """{"versionCode":4,"apkUrl":"https://x/y.apk","apkUrlMirror":"https://x/y.apk"}"""
        val info = UpdateManifest.parse(same)!!
        assertEquals(1, UpdateManifest.downloadCandidates(info).size)
    }

    @Test
    fun `版本号比较只认更大`() {
        val info = UpdateManifest.parse(full)!!
        assertTrue(UpdateManifest.isNewer(info, localVersionCode = 3))
        assertFalse(UpdateManifest.isNewer(info, localVersionCode = 4))
        assertFalse(UpdateManifest.isNewer(info, localVersionCode = 99))
    }

    @Test
    fun `缺字段或非法内容一律返回null而不是半成品对象`() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("{}"))
        assertNull(UpdateManifest.parse("""{"versionCode":0,"apkUrl":"https://a/b.apk"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":4}"""))          // 没有 apkUrl
        assertNull(UpdateManifest.parse("""{"versionCode":4,"apkUrl":""}""")) // apkUrl 为空
    }

    @Test
    fun `force 字段可解析为强制更新`() {
        val forced = """{"versionCode":5,"apkUrl":"https://a/b.apk","force":true}"""
        assertTrue(UpdateManifest.parse(forced)!!.force)
    }
}
