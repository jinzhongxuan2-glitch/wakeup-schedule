package com.wakeup.schedule

import com.wakeup.schedule.core.jw.JwCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JwCryptoTest {

    private val salt = "Ab3dEfGh1JkLmNpQ" // 16 位

    @Test
    fun `随机串长度与字符集正确`() {
        val s = JwCrypto.randomString(64)
        assertEquals(64, s.length)
        assertTrue(s.all { it in "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678" })
    }

    @Test
    fun `A流派密文可以解回原密码`() {
        val encoded = JwCrypto.encryptWithIvPrefix("MyPassw0rd!", salt)
        // 前 16 位是明文 IV，其余是 base64 密文
        assertEquals(16 + ((encoded.length - 16)), encoded.length)
        assertEquals("MyPassw0rd!", JwCrypto.decryptIvPrefixed(encoded, salt))
    }

    @Test
    fun `每次加密结果都不同（IV与随机前缀随机）`() {
        val a = JwCrypto.encryptWithIvPrefix("same", salt)
        val b = JwCrypto.encryptWithIvPrefix("same", salt)
        assertNotEquals(a, b)
    }

    @Test
    fun `B流派是纯base64且与A流派不同`() {
        val a = JwCrypto.encryptWithIvPrefix("pwd", salt)
        val b = JwCrypto.encryptCipherOnly("pwd", salt)
        assertNotEquals(a, b)
        assertEquals(0, b.length % 4) // base64 长度对齐
    }

    @Test
    fun `密文长度随密码长度按16字节块增长`() {
        val short = JwCrypto.encryptCipherOnly("a", salt)
        val long = JwCrypto.encryptCipherOnly("a".repeat(40), salt)
        assertTrue(long.length > short.length)
    }

    @Test
    fun `非16位salt会明确报错而不是静默出错`() {
        var thrown = false
        try {
            JwCrypto.encryptCipherOnly("pwd", "short")
        } catch (e: IllegalArgumentException) {
            thrown = true
            assertTrue(e.message!!.contains("16"))
        }
        assertTrue(thrown)
    }
}
