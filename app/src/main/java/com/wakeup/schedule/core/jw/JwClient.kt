package com.wakeup.schedule.core.jw

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** CAS 登录页解析结果。execution 是一次性令牌，每次重试都必须重新取页面。 */
data class JwLoginPage(
    val url: String,
    val html: String,
    val salt: String,
    val execution: String,
    val lt: String,
    val eventId: String
)

/** 登录提交结果 */
data class JwLoginResult(
    val success: Boolean,
    val outcome: JwLoginAnalysis.Outcome,
    val html: String
)

/**
 * 中南大学教务系统客户端（纯 JVM；解析与判断逻辑有单元测试覆盖）。
 *
 * 登录链路（实测自学校 CAS 页面与 login.js）：
 * 1. GET `https://ca.csu.edu.cn/authserver/login?service=…/sso.jsp`
 *    取 `execution`、`lt`、`#pwdEncryptSalt`
 * 2. GET `/authserver/checkNeedCaptcha.htl?username=<学号>` → `{"isNeed":true|false}`
 *    **验证码是按需出现的**（连续输错才触发），页面里始终存在验证码标记，
 *    因此绝不能「看到验证码字样就阻断」
 * 3. 需要验证码时 GET `/authserver/getCaptcha.htl?<时间戳>` 取图片
 * 4. POST 登录（密码按网页端规则 AES 加密；需要时带 `captcha` 字段）
 * 5. POST `/jsxsd/xskb/xskb_list.do` 取课表 HTML → 交给 [JwScheduleHtmlParser]
 *
 * 密码只在本类内部使用，不写日志（日志只记长度）。
 */
class JwClient(val log: JwDebugLog = JwDebugLog()) {

    companion object {
        const val JWC_BASE = "http://csujwc.its.csu.edu.cn"
        const val JWC_SSO = "$JWC_BASE/sso.jsp"
        const val CAS_HOST = "https://ca.csu.edu.cn"
        private const val TIMEOUT_MS = 20000

        /** 登录页 JS 内置的默认盐（页面未提供 #pwdEncryptSalt 时使用） */
        private const val DEFAULT_SALT = "rjBFAaHsNkKAhpoi"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        val CAS_LOGIN_URL: String =
            "$CAS_HOST/authserver/login?service=" + URLEncoder.encode(JWC_SSO, "UTF-8")

        private val JAVA_GBK = java.nio.charset.Charset.forName("GBK")
    }

    /** 手工维护的 Cookie 罐（避免全局 CookieHandler） */
    private val cookies = linkedMapOf<String, String>()
    private var loggedIn = false

    val isLoggedIn: Boolean get() = loggedIn

    // ---------------- 登录 ----------------

    fun fetchLoginPage(): JwLoginPage {
        log.add("① 打开统一认证登录页…")
        val resp = get(CAS_LOGIN_URL, referer = null)
        val html = resp.body
        log.add("   登录页 HTTP ${resp.code}，长度 ${html.length}")

        val execution = attrOf(html, "execution")
        if (execution.isBlank()) {
            log.add("   未找到 execution，登录页结构可能变了")
            throw JwException(
                JwErrorKind.LOGIN_PAGE_CHANGED,
                "登录页结构与预期不同，无法完成登录。请把调试日志发给开发者。"
            )
        }
        val saltFromPage = attrOf(html, "pwdEncryptSalt")
        val salt = saltFromPage.ifBlank { DEFAULT_SALT }
        log.add(
            "   解析完成：execution ✓，salt ${if (saltFromPage.isBlank()) "缺失→用页面默认值" else "✓(长度 ${salt.length})"}"
        )

        return JwLoginPage(
            url = resp.url,
            html = html,
            salt = salt,
            execution = execution,
            lt = attrOf(html, "lt"),
            eventId = attrOf(html, "_eventId").ifBlank { "submit" }
        )
    }

    /** 问服务端：这个账号现在需要验证码吗（页面里始终有验证码标记，不能据此判断） */
    fun checkNeedCaptcha(studentId: String): Boolean {
        val url = "$CAS_HOST/authserver/checkNeedCaptcha.htl?username=" +
            URLEncoder.encode(studentId, "UTF-8")
        val need = runCatching {
            val resp = get(url, referer = CAS_LOGIN_URL)
            JwLoginAnalysis.parseNeedCaptcha(resp.body)
        }.getOrElse {
            log.add("   验证码判断接口调用失败（${it.message}），按「不需要」继续")
            false
        }
        log.add("   checkNeedCaptcha → ${if (need) "需要验证码" else "无需验证码"}")
        return need
    }

    /** 取验证码图片（JPEG/PNG 字节）。必须与登录页在同一会话（Cookie）下请求。 */
    fun fetchCaptcha(): ByteArray? {
        val url = "$CAS_HOST/authserver/getCaptcha.htl?${System.currentTimeMillis()}"
        val bytes = runCatching { getBytes(url, referer = CAS_LOGIN_URL) }.getOrNull()
        log.add("   验证码图片：${bytes?.size ?: 0} 字节${if (bytes == null) "（获取失败）" else ""}")
        return bytes
    }

    /**
     * 提交登录。
     * 密码加密按 A（iv+密文）→ B（仅密文）→ C（明文）依次尝试；
     * 一旦判定为「账号或密码错误」立即停止（避免多次失败触发风控）。
     */
    fun submitLogin(
        studentId: String,
        password: String,
        page: JwLoginPage,
        captcha: String?
    ): JwLoginResult {
        val variants = listOf<Pair<String, () -> String>>(
            "A(iv+密文)" to { JwCrypto.encryptWithIvPrefix(password, page.salt) },
            "B(仅密文)" to { JwCrypto.encryptCipherOnly(password, page.salt) },
            "C(明文)" to { password }
        )
        val captchaLabel = if (captcha.isNullOrBlank()) "不带验证码" else "带验证码(${captcha.length}位)"
        var lastHtml = ""

        for ((label, build) in variants) {
            val encrypted = try {
                build()
            } catch (e: Exception) {
                log.add("   加密方案 $label 失败：${e.message}")
                continue
            }
            val form = linkedMapOf(
                "username" to studentId,
                "password" to encrypted,
                "passwordText" to "",
                "lt" to page.lt,
                "execution" to page.execution,
                "_eventId" to page.eventId,
                "cllt" to "userNameLogin",
                "dllt" to "generalLogin"
            )
            if (!captcha.isNullOrBlank()) form["captcha"] = captcha

            val resp = post(page.url, form, referer = page.url)
            val ok = resp.url.contains("csujwc.its.csu.edu.cn")
            log.add("   方案 $label + $captchaLabel → HTTP ${resp.code}，最终 ${hostOf(resp.url)}，${if (ok) "成功" else "未通过"}")
            if (ok) {
                loggedIn = true
                return JwLoginResult(true, JwLoginAnalysis.Outcome.SUCCESS, resp.body)
            }
            lastHtml = resp.body

            when (JwLoginAnalysis.classifyLoginFailure(resp.body)) {
                JwLoginAnalysis.Outcome.BAD_CREDENTIALS -> {
                    log.add("   判定：账号或密码错误，停止继续尝试")
                    return JwLoginResult(false, JwLoginAnalysis.Outcome.BAD_CREDENTIALS, lastHtml)
                }
                JwLoginAnalysis.Outcome.NEED_CAPTCHA -> {
                    log.add("   判定：服务端要求（或校验失败）验证码")
                    return JwLoginResult(false, JwLoginAnalysis.Outcome.NEED_CAPTCHA, lastHtml)
                }
                else -> Unit // 继续试下一个加密方案
            }
        }

        val outcome = JwLoginAnalysis.classifyLoginFailure(lastHtml)
        log.add("   全部方案均未通过，判定：${outcome.name}")
        return JwLoginResult(false, outcome, lastHtml)
    }

    // ---------------- 课表 ----------------

    /** 拉取课表页面 HTML（termId 为 null 时取教务系统默认学期，同时可用于解析学期列表） */
    fun fetchScheduleHtml(termId: String?): String {
        check(loggedIn) { "请先登录" }
        log.add("⑤ 读取课表页面…")
        val form = linkedMapOf("zc" to "", "sfFD" to "1")
        if (!termId.isNullOrBlank()) form["xnxq01id"] = termId

        var resp = post(
            "$JWC_BASE/jsxsd/xskb/xskb_list.do",
            form,
            referer = "$JWC_BASE/jsxsd/framework/xsMain.jsp"
        )
        log.add("   课表页 HTTP ${resp.code}，长度 ${resp.body.length}")

        JwScheduleHtmlParser.findInnerFrameUrl(resp.body)?.let { inner ->
            val url = if (inner.startsWith("http")) inner
            else JWC_BASE + (if (inner.startsWith("/")) inner else "/$inner")
            log.add("   检测到 iframe，继续请求内层页面")
            resp = post(url, form, referer = "$JWC_BASE/jsxsd/framework/xsMain.jsp")
            log.add("   内层页 HTTP ${resp.code}，长度 ${resp.body.length}")
        }
        return resp.body
    }

    fun fetchTerms(): List<JwTerm> {
        val html = fetchScheduleHtml(null)
        val terms = JwScheduleHtmlParser.parseTerms(html)
        log.add("   解析到 ${terms.size} 个学期")
        return terms
    }

    // ---------------- HTTP 细节 ----------------

    private data class Resp(val code: Int, val body: String, val url: String)

    private fun get(url: String, referer: String?): Resp = request("GET", url, null, referer)

    private fun post(url: String, form: Map<String, String>, referer: String?): Resp =
        request("POST", url, form, referer)

    private fun getBytes(url: String, referer: String?): ByteArray {
        val conn = open("GET", url, referer, binary = true)
        return try {
            storeCookies(conn)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, url: String, form: Map<String, String>?, referer: String?): Resp {
        try {
            val conn = open(method, url, referer, binary = false)
            if (method == "POST") {
                val body = (form ?: emptyMap()).entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            storeCookies(conn)
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, decodeBody(bytes, conn.contentType), conn.url.toString())
        } catch (e: JwException) {
            throw e
        } catch (e: UnknownHostException) {
            throw JwException(
                JwErrorKind.NETWORK,
                "连不上学校统一认证（域名解析失败）。请确认网络可用；校外访问通常需要校园网或学校 VPN。",
                e
            )
        } catch (e: ConnectException) {
            throw JwException(JwErrorKind.NETWORK, "连接被拒绝，请切换网络（校园网 / VPN）后重试。", e)
        } catch (e: SocketTimeoutException) {
            throw JwException(JwErrorKind.NETWORK, "连接超时，请检查网络（校园网 / VPN）后重试。", e)
        } catch (e: SSLException) {
            throw JwException(JwErrorKind.NETWORK, "HTTPS 握手失败，可能是网络被劫持或代理配置异常。", e)
        } catch (e: IOException) {
            throw JwException(JwErrorKind.NETWORK, "网络错误：${e.message}", e)
        }
    }

    private fun open(method: String, url: String, referer: String?, binary: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty(
                "Accept",
                if (binary) "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                else "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
            )
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Connection", "keep-alive")
            if (referer != null) setRequestProperty("Referer", referer)
            if (cookies.isNotEmpty()) {
                setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }

    private fun storeCookies(conn: HttpURLConnection) {
        conn.headerFields.forEach { (key, values) ->
            if (key.equals("Set-Cookie", ignoreCase = true)) {
                values?.forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
            }
        }
    }

    /** 强智页面多为 GBK，CAS 页面是 UTF-8 —— 直接按 UTF-8 读会把中文读成乱码，这里做嗅探 */
    private fun decodeBody(bytes: ByteArray, contentType: String?): String {
        Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType ?: "")?.groupValues?.get(1)?.let { return String(bytes, charsetOf(it)) }

        val prefix = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase()
        Regex("charset=[\"']?([\\w-]+)").find(prefix)?.groupValues?.get(1)?.let {
            return String(bytes, charsetOf(it))
        }

        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, JAVA_GBK) else utf8
    }

    private fun charsetOf(name: String) = when (name.lowercase()) {
        "gb2312", "gbk", "gb18030" -> JAVA_GBK
        "utf8", "utf-8" -> Charsets.UTF_8
        else -> runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun attrOf(html: String, name: String): String {
        Regex(
            "name\\s*=\\s*[\"']?$name[\"']?[^>]*value\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.let { return it.groupValues[1].trim() }
        Regex(
            "id\\s*=\\s*[\"']?$name[\"']?[^>]*value\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}
