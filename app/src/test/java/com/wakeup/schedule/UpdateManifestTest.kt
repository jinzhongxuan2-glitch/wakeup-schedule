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
          "versionCode": 8,
          "versionName": "1.2.5",
          "apkUrl": "https://github.com/u/r/releases/latest/download/app-release.apk",
          "apkUrlMirror": "https://ghfast.top/https://github.com/u/r/releases/latest/download/app-release.apk",
          "apkUrlCdn": "https://cdn.jsdelivr.net/gh/u/r@dist/v1.2.5/app-release.apk",
          "notes": "修复下载慢",
          "force": false
        }
    """.trimIndent()

    @Test
    fun `完整清单解析出全部字段`() {
        val info = UpdateManifest.parse(full)!!
        assertEquals(8, info.versionCode)
        assertEquals("1.2.5", info.versionName)
        assertEquals("修复下载慢", info.notes)
        assertTrue(info.apkUrlMirror.contains("ghfast.top"))
        assertTrue(info.apkUrlCdn.contains("cdn.jsdelivr.net"))
        assertFalse(info.force)
    }

    @Test
    fun `候选源按 CDN 镜像 官方 的顺序给出`() {
        val info = UpdateManifest.parse(full)!!
        val candidates = UpdateManifest.downloadCandidates(info)
        assertEquals(3, candidates.size)
        // 实测 CDN 约 316 KB/s、镜像 4~8 KB/s、直连无 VPN 基本不可用
        assertEquals("CDN 加速", candidates[0].first)
        assertEquals("镜像加速", candidates[1].first)
        assertEquals("GitHub 官方", candidates[2].first)
    }

    @Test
    fun `只有官方地址时仍可用`() {
        val only = UpdateManifest.parse("""{"versionCode":9,"apkUrl":"https://a/b.apk"}""")!!
        assertEquals("", only.apkUrlCdn)
        val candidates = UpdateManifest.downloadCandidates(only)
        assertEquals(1, candidates.size)
        assertEquals("GitHub 官方", candidates[0].first)
    }

    @Test
    fun `可以指定优先使用的下载源`() {
        val info = UpdateManifest.parse(full)!!
        // 指定镜像优先 → 镜像排第一，其余依次跟随
        val mirrorFirst = UpdateManifest.downloadCandidates(info, 1)
        assertEquals("镜像加速", mirrorFirst[0].first)
        assertEquals(3, mirrorFirst.size)
        assertEquals("CDN 加速", mirrorFirst[1].first)

        // 指定官方优先
        assertEquals("GitHub 官方", UpdateManifest.downloadCandidates(info, 2)[0].first)
    }

    @Test
    fun `优先下标越界时安全退回推荐顺序`() {
        val info = UpdateManifest.parse(full)!!
        assertEquals("CDN 加速", UpdateManifest.downloadCandidates(info, -1)[0].first)
        assertEquals("CDN 加速", UpdateManifest.downloadCandidates(info, 99)[0].first)
    }

    @Test
    fun `地址重复时不重复列出`() {
        val same = """
            {"versionCode":4,"apkUrl":"https://x/y.apk",
             "apkUrlMirror":"https://x/y.apk","apkUrlCdn":"https://x/y.apk"}
        """.trimIndent()
        val info = UpdateManifest.parse(same)!!
        assertEquals(1, UpdateManifest.downloadCandidates(info).size)
    }

    @Test
    fun `版本号比较只认更大`() {
        val info = UpdateManifest.parse(full)!!
        assertTrue(UpdateManifest.isNewer(info, localVersionCode = 7))
        assertFalse(UpdateManifest.isNewer(info, localVersionCode = 8))
        assertFalse(UpdateManifest.isNewer(info, localVersionCode = 99))
    }

    @Test
    fun `缺字段或非法内容一律返回null而不是半成品对象`() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("{}"))
        assertNull(UpdateManifest.parse("""{"versionCode":0,"apkUrl":"https://a/b.apk"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":4}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":4,"apkUrl":""}"""))
    }

    @Test
    fun `force 字段可解析为强制更新`() {
        val forced = """{"versionCode":5,"apkUrl":"https://a/b.apk","force":true}"""
        assertTrue(UpdateManifest.parse(forced)!!.force)
    }
}
