package com.wakeup.schedule.core.jw

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 中南大学教务系统客户端（纯 JVM，可单元测试解析部分）。
 *
 * 流程：
 * 1. GET CAS 登录页（`https://ca.csu.edu.cn/authserver/login?service=…/sso.jsp`），
 *    从页面里取 `execution` 与 `#pwdEncryptSalt`
 * 2. 用 AES 加密密码后 POST 回登录页 → 成功后重定向回教务系统（Cookie 生效）
 * 3. POST `/jsxsd/xskb/xskb_list.do`（`xnxq01id` 学期、`zc` 周次、`sfFD=1`）拿课表 HTML
 * 4. 交给 [JwScheduleHtmlParser] 解析
 *
 * 密码只在本方法内使用，不写日志、不落盘。
 */
class JwClient(val log: JwDebugLog = JwDebugLog()) {

    companion object {
        const val JWC_BASE = "http://csujwc.its.csu.edu.cn"
        const val JWC_SSO = "$JWC_BASE/sso.jsp"
        private const val CAS_HOST = "https://ca.csu.edu.cn"
        private const val TIMEOUT_MS = 20000

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 需要允许明文 HTTP 的域名（教务系统本体是 http） */
        val CAS_LOGIN_URL: String =
            "$CAS_HOST/authserver/login?service=" + URLEncoder.encode(JWC_SSO, "UTF-8")
    }

    /** 手工维护的 Cookie 罐（避免使用全局 CookieHandler） */
    private val cookies = linkedMapOf<String, String>()
    private var loggedIn = false

    // ---------------- 对外接口 ----------------

    /**
     * 登录。密码加密方式各校实现不一，这里按 A（iv+密文）→ B（仅密文）→ 明文 依次尝试，
     * 成功即返回。
     */
    fun login(studentId: String, password: String) {
        log.add("① 打开统一认证登录页…")
        val loginPage = get(CAS_LOGIN_URL, referer = null)
        val html = loginPage.body
        log.add("   登录页 HTTP ${loginPage.code}，长度 ${html.length}")

        if (html.contains("验证码") && (html.contains("captcha") || html.contains("validateCode"))) {
            log.add("   检测到验证码字段")
            throw JwException(
                JwErrorKind.CAPTCHA_REQUIRED,
                "学校登录页需要验证码，当前版本还不支持。可以先用「导出/导入备份文件」的方式导入课表。"
            )
        }

        val salt = attrOf(html, "pwdEncryptSalt")
        val execution = attrOf(html, "execution")
        val lt = attrOf(html, "lt")
        if (execution.isBlank() || salt.isBlank()) {
            log.add("   登录页解析失败: execution=${execution.isNotEmpty()}, salt=${salt.isNotEmpty()}")
            throw JwException(
                JwErrorKind.LOGIN_PAGE_CHANGED,
                "登录页结构与预期不同，无法完成登录。请把调试日志发给开发者。"
            )
        }
        log.add("   解析到 execution / salt(长度 ${salt.length})，准备提交（密码长度 ${password.length}，不记录内容）")

        val variants = listOf(
            "A(iv+密文)" to { JwCrypto.encryptWithIvPrefix(password, salt) },
            "B(仅密文)" to { JwCrypto.encryptCipherOnly(password, salt) },
            "C(明文)" to { password }
        )

        var lastError: String = ""
        for ((label, build) in variants) {
            val encrypted = try {
                build()
            } catch (e: Exception) {
                log.add("   加密方案 $label 计算失败：${e.message}")
                continue
            }
            val postUrl = loginPage.url
            val form = linkedMapOf(
                "username" to studentId,
                "password" to encrypted,
                "passwordText" to "",
                "lt" to lt,
                "execution" to execution,
                "_eventId" to attrOf(html, "_eventId").ifBlank { "submit" },
                "cllt" to "userNameLogin",
                "dllt" to "generalLogin"
            )
            val resp = post(postUrl, form, referer = postUrl)
            val ok = resp.url.contains("csujwc.its.csu.edu.cn")
            log.add("   加密方案 $label → HTTP ${resp.code}，最终地址 ${hostOf(resp.url)}，${if (ok) "成功" else "未通过"}")
            if (ok) {
                loggedIn = true
                return
            }
            lastError = resp.body
        }

        val hint = when {
            lastError.contains("验证码") -> "（登录页提示需要验证码）"
            lastError.contains("密码") || lastError.contains("用户名") -> "（提示账号或密码有误）"
            else -> ""
        }
        throw JwException(
            JwErrorKind.BAD_CREDENTIALS,
            "登录失败：账号或密码错误 $hint".trim()
        )
    }

    /** 拉取课表页面 HTML（termId 为 null 时取教务系统默认学期，同时可用于解析学期列表） */
    fun fetchScheduleHtml(termId: String?): String {
        check(loggedIn) { "请先登录" }
        log.add("② 读取课表页面…")
        val form = linkedMapOf(
            "zc" to "",
            "sfFD" to "1"
        )
        if (!termId.isNullOrBlank()) form["xnxq01id"] = termId

        var resp = post(
            "$JWC_BASE/jsxsd/xskb/xskb_list.do",
            form,
            referer = "$JWC_BASE/jsxsd/framework/xsMain.jsp"
        )
        log.add("   课表页 HTTP ${resp.code}，长度 ${resp.body.length}")

        // 有些部署把课表套在 iframe 里，需要再跟一次内层地址
        JwScheduleHtmlParser.findInnerFrameUrl(resp.body)?.let { inner ->
            val url = if (inner.startsWith("http")) inner else JWC_BASE + (if (inner.startsWith("/")) inner else "/$inner")
            log.add("   检测到 iframe，继续请求内层页面")
            resp = post(url, form, referer = "$JWC_BASE/jsxsd/framework/xsMain.jsp")
            log.add("   内层页 HTTP ${resp.code}，长度 ${resp.body.length}")
        }
        return resp.body
    }

    /** 拉取可选学期列表 */
    fun fetchTerms(): List<JwTerm> {
        val html = fetchScheduleHtml(null)
        val terms = JwScheduleHtmlParser.parseTerms(html)
        log.add("   解析到 ${terms.size} 个学期")
        return terms
    }

    /** 一次性完成：登录 → 读学期 → 读指定学期课表 → 解析 */
    fun fetchSchedule(studentId: String, password: String, termId: String?): List<JwCourse> {
        login(studentId, password)
        val html = fetchScheduleHtml(termId)
        if (!JwScheduleHtmlParser.looksLikeSchedulePage(html)) {
            throw JwException(
                JwErrorKind.SYSTEM_RESPONSE,
                "拿到了页面但不是课表页（可能登录态失效或该学期没有课表）"
            )
        }
        log.add("③ 解析课表 HTML…")
        return JwScheduleHtmlParser.parse(html, log)
    }

    // ---------------- HTTP 细节 ----------------

    private data class Resp(val code: Int, val body: String, val url: String)

    private fun get(url: String, referer: String?): Resp = request("GET", url, null, referer)

    private fun post(url: String, form: Map<String, String>, referer: String?): Resp =
        request("POST", url, form, referer)

    private fun request(method: String, url: String, form: Map<String, String>?, referer: String?): Resp {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
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
            val decoded = decodeBody(bytes, conn.contentType)
            return Resp(code, decoded, conn.url.toString())
        } catch (e: UnknownHostException) {
            throw JwException(
                JwErrorKind.NETWORK,
                "连不上教务系统（域名解析失败）。请确认网络可用，校内系统通常需要连接校园网或学校 VPN。",
                e
            )
        } catch (e: ConnectException) {
            throw JwException(
                JwErrorKind.NETWORK,
                "连接被拒绝。教务系统可能仅限校园网/VPN 访问，请切换网络后重试。",
                e
            )
        } catch (e: SocketTimeoutException) {
            throw JwException(JwErrorKind.NETWORK, "连接超时，请检查网络（校园网/VPN）后重试。", e)
        } catch (e: SSLException) {
            throw JwException(JwErrorKind.NETWORK, "HTTPS 握手失败，可能是网络被劫持或代理配置异常。", e)
        } catch (e: IOException) {
            throw JwException(JwErrorKind.NETWORK, "网络错误：${e.message}", e)
        }
    }

    private fun storeCookies(conn: HttpURLConnection) {
        conn.headerFields.forEach { (key, values) ->
            if (key.equals("Set-Cookie", ignoreCase = true)) {
                values?.forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && !value.isEmpty()) cookies[name] = value
                }
            }
        }
    }

    /**
     * 解码响应体。强智的页面多为 GBK，CAS 页面是 UTF-8，
     * 直接按 UTF-8 读会把中文课程名读成乱码，因此这里做嗅探。
     */
    private fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val declared = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType ?: "")?.groupValues?.get(1)
        if (declared != null) return String(bytes, charsetOf(declared))

        val prefix = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            .lowercase()
        Regex("charset=[\"']?([\\w-]+)").find(prefix)?.groupValues?.get(1)?.let {
            return String(bytes, charsetOf(it))
        }

        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, JAVA_GBK) else utf8
    }

    private val JAVA_GBK: java.nio.charset.Charset = java.nio.charset.Charset.forName("GBK")

    private fun charsetOf(name: String) = when (name.lowercase()) {
        "gb2312", "gbk", "gb18030" -> JAVA_GBK
        "utf8", "utf-8" -> Charsets.UTF_8
        else -> runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun attrOf(html: String, name: String): String {
        val pattern = Regex(
            "name\\s*=\\s*[\"']?$name[\"']?[^>]*value\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        )
        pattern.find(html)?.let { return it.groupValues[1].trim() }
        val idPattern = Regex(
            "id\\s*=\\s*[\"']?$name[\"']?[^>]*value\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        )
        return idPattern.find(html)?.groupValues[1]?.trim() ?: ""
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}
