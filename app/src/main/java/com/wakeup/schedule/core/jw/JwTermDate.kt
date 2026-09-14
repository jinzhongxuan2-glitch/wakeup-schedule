package com.wakeup.schedule.core.jw

import com.wakeup.schedule.core.ScheduleMath
import java.time.LocalDate

/**
 * 教务系统只提供学期编号（如 `2026-2027-1`），不提供开学日期，
 * 而本 App 的周次计算依赖开学日期，所以这里按学期编号做合理推算，
 * 用户导入后也可以在「课表设置」里手动微调。
 */
object JwTermDate {

    /**
     * 依据学期编号推算开学日期（已归一到周一）。
     * - 第 1 学期（秋季）：该学年 9 月 1 日所在周的周一
     * - 第 2 学期（春季）：次年 2 月 20 日所在周的周一
     * - 编号不识别时：退回「今天所在周的周一」
     */
    fun guessStartDate(termId: String, today: LocalDate = LocalDate.now()): String {
        val match = Regex("(\\d{4})\\s*-\\s*(\\d{4})\\s*-\\s*([12])").find(termId.trim())
        if (match != null) {
            val startYear = match.groupValues[1].toIntOrNull()
            val term = match.groupValues[3].toIntOrNull()
            if (startYear != null && term != null) {
                val anchor = when (term) {
                    1 -> LocalDate.of(startYear, 9, 1)
                    2 -> LocalDate.of(startYear + 1, 2, 20)
                    else -> null
                }
                if (anchor != null) return ScheduleMath.normalizeToMonday(anchor).toString()
            }
        }
        return ScheduleMath.startDateForCurrentWeek(today, 1).toString()
    }

    /** 学期名称的友好展示（编号本身通常就是「2026-2027-1」） */
    fun displayName(termId: String, rawName: String): String = rawName.ifBlank { termId }
}
