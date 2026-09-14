package com.wakeup.schedule

import com.wakeup.schedule.core.jw.JwLoginAnalysis
import com.wakeup.schedule.core.jw.JwLoginAnalysis.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录环节的纯逻辑判断测试。
 * 这些用例来自实测（中南大学 CAS 的 /checkNeedCaptcha.htl 返回值与登录页结构）。
 */
class JwLoginAnalysisTest {

    @Test
    fun `解析无需验证码`() {
        assertFalse(JwLoginAnalysis.parseNeedCaptcha("""{"isNeed":false}"""))
    }

    @Test
    fun `解析需要验证码`() {
        assertTrue(JwLoginAnalysis.parseNeedCaptcha("""{"isNeed":true}"""))
    }

    @Test
    fun `解析容错：大小写、空格、多余字段`() {
        assertTrue(JwLoginAnalysis.parseNeedCaptcha("""{ "isNeed" : TRUE , "other": 1 }"""))
        assertFalse(JwLoginAnalysis.parseNeedCaptcha("""{"IsNeed":False}"""))
    }

    @Test
    fun `解析失败时按不需要处理（宁可让它去试登录，也不要误拦）`() {
        assertFalse(JwLoginAnalysis.parseNeedCaptcha(""))
        assertFalse(JwLoginAnalysis.parseNeedCaptcha("<html>错误页</html>"))
        assertFalse(JwLoginAnalysis.parseNeedCaptcha("{}"))
    }

    @Test
    fun `密码错误页面被识别为账号密码错误`() {
        val html = """<html><body><span id="showErrorTip" class="errors">用户名或密码错误</span></body></html>"""
        assertEquals(Outcome.BAD_CREDENTIALS, JwLoginAnalysis.classifyLoginFailure(html))
    }

    @Test
    fun `验证码错误页面被识别为需要验证码`() {
        val html = """<div id="captchaErrorTip" class="item-error-tip">验证码错误</div>"""
        assertEquals(Outcome.NEED_CAPTCHA, JwLoginAnalysis.classifyLoginFailure(html))
    }

    @Test
    fun `验证码为空提示也归为需要验证码`() {
        assertEquals(
            Outcome.NEED_CAPTCHA,
            JwLoginAnalysis.classifyLoginFailure("<span>验证码不能为空</span>")
        )
    }

    @Test
    fun `密码错误优先于验证码（对用户更有行动价值）`() {
        val html = "<div>用户名或密码错误</div><div id='captchaDiv'>请输入验证码</div>"
        assertEquals(Outcome.BAD_CREDENTIALS, JwLoginAnalysis.classifyLoginFailure(html))
    }

    @Test
    fun `无关页面归为未知而不是乱猜`() {
        assertEquals(Outcome.UNKNOWN, JwLoginAnalysis.classifyLoginFailure("<html>系统维护中</html>"))
    }

    @Test
    fun `只有非隐藏的验证码框才算可见`() {
        // 真实登录页里这个区块默认是隐藏的（class 含 hide）
        val hidden = """<div class="captcha item hide" id="captchaDiv">…</div>"""
        assertFalse(JwLoginAnalysis.hasVisibleCaptchaInput(hidden))

        val shown = """<div class="captcha item" id="captchaDiv">…</div>"""
        assertTrue(JwLoginAnalysis.hasVisibleCaptchaInput(shown))

        assertFalse(JwLoginAnalysis.hasVisibleCaptchaInput("<html>没有验证码区块</html>"))
    }
}
