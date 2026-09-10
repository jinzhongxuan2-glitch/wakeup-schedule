package com.wakeup.schedule.data

import android.graphics.Color
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** 内置示例课表：一学期真实感的大学课程 */
object SampleData {

    /** 课程色板（与 WakeUp 风格一致的明快彩色块） */
    val COURSE_COLORS = listOf(
        Color.parseColor("#F06292"), // 粉
        Color.parseColor("#BA68C8"), // 紫
        Color.parseColor("#9575CD"), // 深紫
        Color.parseColor("#7986CB"), // 靛蓝
        Color.parseColor("#64B5F6"), // 蓝
        Color.parseColor("#4FC3F7"), // 浅蓝
        Color.parseColor("#4DD0E1"), // 青
        Color.parseColor("#4DB6AC"), // 蓝绿
        Color.parseColor("#81C784"), // 绿
        Color.parseColor("#FFB74D"), // 橙
        Color.parseColor("#FF8A65"), // 橘红
        Color.parseColor("#A1887F")  // 棕
    )

    /** 找「本周周一」，再往前推一周作为学期开始，保证打开 App 时是第 2 周，效果最佳 */
    fun semesterStart(): String {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.minusWeeks(1).toString()
    }

    suspend fun load(repo: Repository): Long {
        val tableId = repo.createTable(
            name = "大三上·示例课表",
            startDate = semesterStart(),
            totalWeeks = 20,
            maxSections = 12
        )

        data class C(
            val name: String, val teacher: String, val color: Int,
            val day: Int, val start: Int, val count: Int = 2,
            val sw: Int = 1, val ew: Int = 20, val type: Int = WeekType.ALL,
            val loc: String
        )

        val courses = listOf(
            C("高等数学A(下)", "王建国", COURSE_COLORS[4], 1, 1, 2, loc = "教一-201"),
            C("数据结构与算法", "李楠", COURSE_COLORS[0], 1, 3, 2, loc = "计科楼-305"),
            C("大学英语(四)", "Alice Chen", COURSE_COLORS[9], 1, 5, 2, 1, 16, loc = "外语楼-412"),
            C("形势与政策", "张思远", COURSE_COLORS[11], 1, 9, 2, 1, 8, WeekType.ODD, "阶梯教室A"),
            C("操作系统原理", "赵启明", COURSE_COLORS[2], 2, 1, 2, loc = "计科楼-302"),
            C("体育(羽毛球)", "刘洋", COURSE_COLORS[8], 2, 5, 2, 1, 16, loc = "体育馆2F"),
            C("马克思主义基本原理", "陈立", COURSE_COLORS[10], 2, 7, 2, 1, 16, loc = "教二-108"),
            C("高等数学A(下)", "王建国", COURSE_COLORS[4], 3, 3, 2, loc = "教一-201"),
            C("程序设计实践", "李楠", COURSE_COLORS[6], 3, 7, 2, 1, 18, loc = "实验楼-B204"),
            C("大学物理(下)", "孙维", COURSE_COLORS[3], 3, 9, 2, 1, 16, loc = "物理楼-101"),
            C("数据结构与算法", "李楠", COURSE_COLORS[0], 4, 1, 2, 1, 18, loc = "计科楼-305"),
            C("线性代数", "周敏", COURSE_COLORS[7], 4, 3, 2, 1, 16, loc = "教一-305"),
            C("操作系统实验", "赵启明", COURSE_COLORS[5], 4, 7, 2, 3, 17, WeekType.EVEN, "实验楼-C101"),
            C("大学英语(四)", "Alice Chen", COURSE_COLORS[9], 5, 1, 2, 1, 16, loc = "外语楼-412"),
            C("大学物理实验", "孙维", COURSE_COLORS[3], 5, 5, 2, 5, 15, WeekType.ODD, "物理实验中心"),
            C("通识选修·电影鉴赏", "顾晓", COURSE_COLORS[1], 5, 9, 2, 1, 12, loc = "艺术楼-201")
        )

        // 同一名称的课程合并为一个课程 + 多个时间段
        val grouped = courses.groupBy { it.name }
        grouped.forEach { (name, list) ->
            val first = list.first()
            repo.saveCourse(
                CourseEntity(
                    tableId = tableId,
                    name = name,
                    teacher = first.teacher,
                    color = first.color
                ),
                list.map {
                    TimeSlotEntity(
                        courseId = 0, // 由 saveCourse 填充
                        dayOfWeek = it.day,
                        startSection = it.start,
                        sectionCount = it.count,
                        startWeek = it.sw,
                        endWeek = it.ew,
                        weekType = it.type,
                        location = it.loc
                    )
                }
            )
        }
        return tableId
    }
}
