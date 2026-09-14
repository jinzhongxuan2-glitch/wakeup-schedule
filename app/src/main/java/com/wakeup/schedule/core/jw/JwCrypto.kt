package com.wakeup.schedule.core.jw

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 统一认证（Apereo CAS）登录密码加密，纯 JVM 实现（可单元测试）。
 *
 * 国内多数高校的 CAS 登录页会在 `#pwdEncryptSalt` 里给出 salt，前端用
 * AES-128-CBC 加密密码再提交，规则是：
 *   明文 = 64 位随机串 + 真实密码，PKCS7 补齐
 *   IV   = 16 位随机串
 *   key  = salt（16 位）
 *
 * 服务端如何拿到 IV，各校实现并不一致，已知两种流派：
 *   A. 把 IV 明文拼在密文前面：`iv + base64(cipher)`
 *   B. 只提交 `base64(cipher)`
 * 因此这里两种都提供，登录时按 A → B 顺序重试，谁能通过就用谁。
 */
object JwCrypto {

    /** 与网页端一致的字符集（不含易混淆字符） */
    private const val CHARSET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    private val random = SecureRandom()

    fun randomString(length: Int): String {
        if (length <= 0) return ""
        val sb = StringBuilder(length)
        repeat(length) { sb.append(CHARSET[random.nextInt(CHARSET.length)]) }
        return sb.toString()
    }

    private fun pkcs7Pad(data: ByteArray, blockSize: Int = 16): ByteArray {
        val padding = blockSize - (data.size % blockSize)
        val out = ByteArray(data.size + padding)
        System.arraycopy(data, 0, out, 0, data.size)
        for (i in data.size until out.size) out[i] = padding.toByte()
        return out
    }

    /** 返回 (iv, base64 密文) */
    private fun encryptRaw(password: String, salt: String): Pair<String, String> {
        require(salt.length == 16) { "salt 长度必须是 16，实际为 ${salt.length}" }
        val iv = randomString(16)
        val plain = pkcs7Pad((randomString(64) + password).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            )
        }
        return iv to Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }

    /** 流派 A：iv + base64(密文) */
    fun encryptWithIvPrefix(password: String, salt: String): String {
        val (iv, cipherText) = encryptRaw(password, salt)
        return iv + cipherText
    }

    /** 流派 B：仅 base64(密文) */
    fun encryptCipherOnly(password: String, salt: String): String =
        encryptRaw(password, salt).second

    /** 自检用：把 A 流派的结果解回明文，验证 IV/补齐/密钥流程正确 */
    fun decryptIvPrefixed(encoded: String, salt: String): String {
        val iv = encoded.substring(0, 16)
        val cipherBytes = Base64.getDecoder().decode(encoded.substring(16))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            )
        }
        val plain = cipher.doFinal(cipherBytes)
        val pad = plain.last().toInt()
        val body = String(plain, 0, plain.size - pad, Charsets.UTF_8)
        return body.substring(64) // 去掉 64 位随机前缀
    }
}
