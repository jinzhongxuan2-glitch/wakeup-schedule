package com.wakeup.schedule.core.jw

/**
 * 统一认证登录环节的**纯逻辑判断**（可单元测试）。
 *
 * 背景（实测中南大学 CAS 的登录页与 JS 得到）：
 * 登录页 HTML 里**始终**包含验证码相关标记（隐藏的 `#captchaDiv`、`reloadCaptcha()` 等），
 * 所以「页面里出现验证码字样」不能作为「需要验证码」的依据 —— 早先版本据此直接阻断，
 * 导致本可正常登录的账号被误拦。
 *
 * 官方前端逻辑是：先请求 `/checkNeedCaptcha.htl?username=xxx`，按返回的 `isNeed`
 * 决定是否显示验证码；验证码图片来自 `/getCaptcha.htl`。
 */
object JwLoginAnalysis {

    /** 登录结果分类 */
    enum class Outcome {
        /** 登录成功（已跳回教务系统） */
        SUCCESS,

        /** 需要（或需要重填）验证码 */
        NEED_CAPTCHA,

        /** 账号或密码错误 */
        BAD_CREDENTIALS,

        /** 其他（页面改版、系统异常等） */
        UNKNOWN
    }

    /**
     * 解析 `{"isNeed":true|false}`。
     * 容错：字段顺序、大小写、多余空白；解析不出来时按「不需要」处理，
     * 因为「需要」时会由登录失败兜底再次询问，不会误伤正常登录。
     */
    fun parseNeedCaptcha(json: String): Boolean {
        val match = Regex("\"isNeed\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(json)
            ?: return false
        return match.groupValues[1].equals("true", ignoreCase = true)
    }

    /**
     * 依据登录失败后返回的页面内容，判断失败原因。
     * 密码错误优先于验证码（对用户更有行动价值）。
     */
    fun classifyLoginFailure(html: String): Outcome {
        val text = html.replace(Regex("<[^>]+>"), " ").replace("\u00a0", " ")

        val passwordError = listOf(
            "用户名或密码错误", "账号或密码错误", "密码错误", "用户名不存在",
            "用户名错误", "认证失败", "invalid credentials"
        ).any { text.contains(it, ignoreCase = true) }

        val captchaError = listOf(
            "验证码错误", "验证码不正确", "验证码不能为空", "验证码已失效", "请重新输入验证码"
        ).any { text.contains(it, ignoreCase = true) }

        return when {
            captchaError -> Outcome.NEED_CAPTCHA
            passwordError -> Outcome.BAD_CREDENTIALS
            else -> Outcome.UNKNOWN
        }
    }

    /** 页面里是否存在**可见**的验证码输入框（用于兜底判断，不用于直接阻断） */
    fun hasVisibleCaptchaInput(html: String): Boolean {
        // 隐藏的验证码区块带 class="hide"；这里只认可见的
        val divMatch = Regex("<div[^>]*id=\"captchaDiv\"[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val divTag = divMatch?.value ?: return false
        return !divTag.contains("hide")
    }
}
