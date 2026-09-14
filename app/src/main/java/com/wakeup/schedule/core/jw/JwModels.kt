package com.wakeup.schedule.core.jw

/** 教务系统的一个学期 */
data class JwTerm(val id: String, val name: String) {
    override fun toString(): String = name
}

/** 教务系统解析出的一个时间段 */
data class JwSlot(
    val dayOfWeek: Int,
    val startSection: Int,
    val sectionCount: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int,
    val location: String,
    /** 原始周次文本，例如 "1-16(周)"，用于展示与排查 */
    val rawWeeks: String = ""
)

/** 教务系统解析出的一门课（同名课程已合并） */
data class JwCourse(
    val name: String,
    val teacher: String,
    val slots: List<JwSlot>
)

/** 导入失败的原因，UI 据此给出不同提示 */
enum class JwErrorKind {
    /** 网络不可达（大概率是不在校园网 / 需要 VPN） */
    NETWORK,

    /** 账号或密码错误 */
    BAD_CREDENTIALS,

    /** 登录页需要验证码，当前不支持 */
    CAPTCHA_REQUIRED,

    /** 登录页结构变化，无法解析（需要反馈日志） */
    LOGIN_PAGE_CHANGED,

    /** 课表页面结构变化，解析不出课程 */
    SCHEDULE_PARSE_FAILED,

    /** 教务系统返回了错误页（如未选课、学期无数据） */
    SYSTEM_RESPONSE,

    UNKNOWN
}

class JwException(
    val kind: JwErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 采集调试日志：用户实测出问题时，把这份日志发回来就能定位。
 * 不记录密码，密码只记长度。
 */
class JwDebugLog(private val limit: Int = 60) {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        if (lines.size >= limit) lines.removeFirst()
        lines.addLast(line)
    }

    fun text(): String = lines.joinToString("\n")

    fun clear() = lines.clear()
}
