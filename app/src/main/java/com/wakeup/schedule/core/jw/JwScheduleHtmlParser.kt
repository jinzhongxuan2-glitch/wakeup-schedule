package com.wakeup.schedule.core.jw

import com.wakeup.schedule.core.ScheduleMath
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * 强智科技 jsxsd 教务系统「学生课表」HTML 解析器（纯 JVM，可单元测试）。
 *
 * 页面结构（`xskb_list.do` 返回的 HTML）：
 * ```
 * <table id="kbtable">
 *   <tr><th>节次</th><th>星期一</th>…<th>星期日</th></tr>
 *   <tr>
 *     <th>1-2</th>
 *     <td><div class="kbcontent">高等数学<br>
 *          <font title="老师">王建国</font><br>
 *          <font title="周次(节次)">1-16(周) [1-2节]</font><br>
 *          <font title="教室">教一-201</font></div>
 *         <hr><div class="kbcontent">…同一格里的另一门课…</div></td>
 *   </tr>
 * </table>
 * ```
 *
 * 各校/各版本的差异点，这里都做了容错：
 * - 表头列偏移（左起第一列是不是「节次」）→ 自动侦测
 * - 星期顺序（周一在前 or 周日在前）→ 自动侦测
 * - 单元格 class 可能是 `kbcontent` / `kbcontent1`，同一格多门课用 `-----` 或 `<hr>` 分隔
 * - 节次可能写在「周次(节次)」字段里（`[1-2节]` / `[0102节]`），也可能只写在行首的节次列
 * - 周次可能是 `1-16(周)`、`1-8,10-12(周)`，单双周用 `单周` / `双周` 标注
 * - 整个课表可能被套在 iframe 里（外层页面需要再跟一次内层地址）
 */
object JwScheduleHtmlParser {

    private val SPLIT_PATTERN = Regex("-{10,}")
    private val WEEK_PATTERN = Regex("([\\d\\-,]+)\\s*\\(周\\)")
    private val SECTION_IN_BRACKETS = Regex("\\[(\\d+)\\s*-\\s*(\\d+)节?\\]")
    /** 形如 [0102节]：前两位开始节，后两位结束节 */
    private val SECTION_COMPACT = Regex("\\[(\\d{2})(\\d{2})节\\]")
    private val SECTION_PLAIN = Regex("(\\d+)\\s*-\\s*(\\d+)节")

    private val DAY_NAMES = mapOf(
        "星期一" to 1, "周一" to 1, "礼拜一" to 1,
        "星期二" to 2, "周二" to 2,
        "星期三" to 3, "周三" to 3,
        "星期四" to 4, "周四" to 4,
        "星期五" to 5, "周五" to 5,
        "星期六" to 6, "周六" to 6,
        "星期日" to 7, "星期天" to 7, "周日" to 7, "周天" to 7
    )

    private val CHINESE_NUMBERS = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
        "七" to 7, "八" to 8, "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
    )

    private val EXCLUDE_NAMES = setOf("教学资料", "备注", "说明", "空闲")

    /** 页面里是否有课表表格（用于判断登录是否真的拿到了内容） */
    fun looksLikeSchedulePage(html: String): Boolean =
        html.contains("kbtable") || (html.contains("星期") && html.contains("节次"))

    /** 外层页面套了 iframe 时，返回内层地址；没有则返回 null */
    fun findInnerFrameUrl(html: String): String? {
        val doc = Jsoup.parse(html)
        // 只认课表相关的 iframe，避免误抓广告/统计页
        doc.select("iframe").forEach { frame ->
            val src = frame.attr("src")
            if (src.isNotBlank() && (src.contains("xskb", true) || src.contains("list.do", true))) {
                return src
            }
        }
        val frame1 = doc.getElementById("Frame1")
        if (frame1 != null && frame1.tagName() == "iframe" && frame1.attr("src").isNotBlank()) {
            return frame1.attr("src")
        }
        return null
    }

    /**
     * 解析课表页面。
     * @throws JwException 找不到课表结构或一门课都没解析出来
     */
    fun parse(html: String, log: JwDebugLog? = null): List<JwCourse> {
        val doc = Jsoup.parse(html)
        val table = findScheduleTable(doc)
            ?: throw JwException(
                JwErrorKind.SCHEDULE_PARSE_FAILED,
                "页面上没有找到课表表格（可能是登录未生效，或教务系统改版）"
            )
        log?.add("找到课表表格: id=${table.id().ifBlank { "(无)" }}，行数=${table.select("tr").size}")

        val layout = detectLayout(table, log)
        val rows = table.select("tr")

        // 课程名 → (教师, 时间段列表)
        val merged = LinkedHashMap<String, Pair<String, MutableList<JwSlot>>>()
        var cellHits = 0

        rows.forEach { row ->
            val cells = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }
            if (cells.size < 2) return@forEach

            // 这一行的节次（用于单元格里没写节次时的兜底）
            val rowSections = parseSections(cells.first().text())

            cells.forEachIndexed { colIdx, cell ->
                if (layout.firstColIsHeader && colIdx == 0) return@forEachIndexed
                val day = layout.dayOfWeek(colIdx) ?: return@forEachIndexed
                if (day !in 1..7) return@forEachIndexed

                if (cell.text().isBlank()) return@forEachIndexed

                splitCellSegments(cell).forEach { segmentHtml ->
                    val parsed = parseSegment(segmentHtml, rowSections, day, log) ?: return@forEach
                    cellHits++
                    val entry = merged.getOrPut(parsed.name) { parsed.teacher to mutableListOf() }
                    if (entry.first.isBlank() && parsed.teacher.isNotBlank()) {
                        merged[parsed.name] = parsed.teacher to entry.second
                    }
                    entry.second.addAll(parsed.slots)
                }
            }
        }
        log?.add("扫描到 $cellHits 个课程单元格片段")
        if (cellHits == 0) {
            throw JwException(
                JwErrorKind.SCHEDULE_PARSE_FAILED,
                "课表表格是空的（这学期可能还没选课，或该学期无课表数据）"
            )
        }

        val courses = merged.map { (name, pair) -> JwCourse(name, pair.first, pair.second) }
        log?.add("合并为 ${courses.size} 门课程，${courses.sumOf { it.slots.size }} 个时间段")
        return courses
    }

    /** 解析学期下拉框（`<select name="xnxq01id">`） */
    fun parseTerms(html: String): List<JwTerm> {
        val doc = Jsoup.parse(html)
        val select = doc.selectFirst("select[name=xnxq01id]")
            ?: doc.selectFirst("select#xnxq01id")
            ?: doc.select("select").firstOrNull { sel ->
                sel.select("option").any { it.text().contains("学期") || it.text().matches(Regex("\\d{4}-\\d{4}-[12]")) }
            }
            ?: return emptyList()

        return select.select("option").mapNotNull { option ->
            val value = option.attr("value").trim()
            val name = option.text().trim()
            if (value.isBlank() || value == "0") null else JwTerm(value, name.ifBlank { value })
        }
    }

    // ---------------- 内部实现 ----------------

    private data class ParsedSegment(val name: String, val teacher: String, val slots: List<JwSlot>)

    private data class Layout(val firstColIsHeader: Boolean, val sundayFirst: Boolean) {
        /** 单元格列索引 → 星期几；返回 null 表示这一列不是星期列 */
        fun dayOfWeek(colIdx: Int): Int? {
            var effective = colIdx
            if (firstColIsHeader && effective > 0) effective -= 1
            if (effective < 0 || effective > 6) return null
            return if (sundayFirst) {
                if (effective == 0) 7 else effective
            } else {
                effective + 1
            }
        }
    }

    /** 表头自动侦测：首列是否为节次列、星期是否从周日开始 */
    private fun detectLayout(table: Element, log: JwDebugLog?): Layout {
        var firstColIsHeader = false
        var sundayFirst = false

        val headerRow = table.select("tr").firstOrNull { row ->
            val t = row.text()
            t.contains("星期") || t.contains("周一")
        }
        if (headerRow != null) {
            val texts = headerRow.children().map { it.text().trim() }
            val idxSun = texts.indexOfFirst { it.contains("星期日") || it.contains("周日") || it.contains("星期天") }
            val idxMon = texts.indexOfFirst { it.contains("星期一") || it.contains("周一") }
            val first = listOf(idxSun, idxMon).filter { it >= 0 }.minOrNull()
            if (first != null) {
                firstColIsHeader = first > 0
                if (idxSun >= 0 && idxMon >= 0) sundayFirst = idxSun < idxMon
                log?.add("表头侦测: 首列为节次列=$firstColIsHeader, 周日在前=$sundayFirst")
            }
        }
        return Layout(firstColIsHeader, sundayFirst)
    }

    private fun findScheduleTable(doc: Document): Element? {
        doc.getElementById("kbtable")?.let { return it }
        // 兜底：给所有表格打分（含「星期」「节次」「(周)」越多越像课表）
        return doc.select("table").map { table ->
            val text = table.text()
            var score = 0
            if (text.contains("星期")) score += 10
            if (text.contains("节次")) score += 10
            score += Regex("\\d+-\\d+\\(周\\)").findAll(text).count() * 5
            if (text.length < 50) score = 0
            score to table
        }.filter { it.first > 0 }.maxByOrNull { it.first }?.second
    }

    /**
     * 把一个单元格切成若干「课程片段」。真实页面里同一格多门课的写法至少有三种：
     * 1. 多个 `div.kbcontent` 并列
     * 2. 一个 div 内用 `---------------------` 分隔
     * 3. 用 `<hr>` 分隔
     * 三种都覆盖，避免漏课。
     */
    private fun splitCellSegments(cell: Element): List<String> {
        val divs = cell.select("div.kbcontent").ifEmpty { cell.select("div.kbcontent1") }
        val sources = if (divs.isNotEmpty()) divs.map { it.html() } else {
            cell.select("div").filter { it.text().isNotBlank() }.map { it.html() }.ifEmpty { listOf(cell.html()) }
        }

        return sources.flatMap { html ->
            val out = mutableListOf<String>()
            SPLIT_PATTERN.split(html).forEach { part ->
                val fragment = Jsoup.parseBodyFragment(part).body()
                var current = StringBuilder()
                fragment.childNodes().forEach { node ->
                    if (node is Element && node.tagName() == "hr") {
                        out.add(current.toString()); current = StringBuilder()
                    } else {
                        current.append(node.outerHtml())
                    }
                }
                out.add(current.toString())
            }
            out.filter { it.isNotBlank() }
        }
    }

    private fun parseSegment(
        segmentHtml: String,
        rowSections: Pair<Int, Int>?,
        day: Int,
        log: JwDebugLog?
    ): ParsedSegment? {
        val fragment = Jsoup.parseBodyFragment(segmentHtml).body()
        val texts = collectTexts(fragment)
        if (texts.isEmpty()) return null

        val rawName = texts.first()
        // 注意：不能剥掉末尾括号里的内容 —— 中南大学的课名本身就带括号，
        // 例如「高等数学A(下)」「大学英语(四)」「体育(四)」，剥了就成了另一门课。
        val name = rawName.replace("\u00a0", " ").replace(Regex("\\s+"), " ").trim()
        if (name.isBlank() || name in EXCLUDE_NAMES) return null

        var teacher = ""
        var location = ""
        var weekSecText = ""

        val fonts = fragment.getElementsByTag("font")
        if (fonts.isNotEmpty()) {
            fonts.forEach { font ->
                val title = font.attr("title")
                val value = font.text().trim()
                when {
                    title == "老师" || title.contains("教师") -> teacher = value
                    title == "教室" || title.contains("地点") -> location = value
                    title.contains("周次") || title.contains("节次") -> weekSecText = value
                }
            }
        }
        // 没有 font 标注时，按「字段名 值」相邻文本兜底
        if (teacher.isBlank()) teacher = valueAfter(texts, "老师")
        if (location.isBlank()) location = valueAfter(texts, "教室")
        if (weekSecText.isBlank()) weekSecText = valueAfter(texts, "周次")

        val searchSpace = weekSecText.ifBlank { fragment.text() }

        val sections = parseSections(searchSpace) ?: rowSections ?: run {
            log?.add("跳过「$name」：节次信息缺失")
            return null
        }
        val step = (sections.second - sections.first + 1).coerceAtLeast(1)

        val weekRanges = parseWeekRanges(searchSpace.ifBlank { fragment.text() })
        if (weekRanges.isEmpty()) {
            log?.add("跳过「$name」：周次信息缺失")
            return null
        }

        val weekType = when {
            fragment.text().contains("单周") -> ScheduleMath.WEEK_ODD
            fragment.text().contains("双周") -> ScheduleMath.WEEK_EVEN
            else -> ScheduleMath.WEEK_ALL
        }

        val slots = weekRanges.map { (from, to) ->
            JwSlot(
                dayOfWeek = day,
                startSection = sections.first,
                sectionCount = step,
                startWeek = from,
                endWeek = to,
                weekType = weekType,
                location = location,
                rawWeeks = searchSpace
            )
        }
        return ParsedSegment(name, teacher, slots)
    }

    /** 按文档顺序收集非空文本片段（等价于网页端 get_text("|") 后切分） */
    private fun collectTexts(root: Element): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: org.jsoup.nodes.Node) {
            if (node is TextNode) {
                val t = node.text().replace("\u00a0", " ").trim()
                if (t.isNotEmpty()) out.add(t)
            } else if (node is Element) {
                if (node.tagName() == "br" || node.tagName() == "hr") return
                node.childNodes().forEach { walk(it) }
            }
        }
        root.childNodes().forEach { walk(it) }
        return out
    }

    private fun valueAfter(texts: List<String>, label: String): String {
        val idx = texts.indexOfFirst { it == label || it == "$label:" || it == "$label：" }
        if (idx >= 0 && idx + 1 < texts.size) {
            val v = texts[idx + 1]
            if (!v.startsWith("周次") && !v.startsWith("教室")) return v
        }
        return ""
    }

    /** 解析节次，支持 `[1-2节]`、`[0102节]`、`1-2节`，以及单个数字（视为 1 节） */
    private fun parseSections(text: String): Pair<Int, Int>? {
        SECTION_IN_BRACKETS.find(text)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return@let
            val b = it.groupValues[2].toIntOrNull() ?: return@let
            if (a in 1..20 && b >= a && b <= 24) return a to b
        }
        SECTION_COMPACT.find(text)?.let {
            val a = it.groupValues[1].toIntOrNull()
            val b = it.groupValues[2].toIntOrNull()
            if (a != null && b != null && a in 1..24 && b >= a && b <= 24) return a to b
        }
        SECTION_PLAIN.find(text)?.let {
            val a = it.groupValues[1].toIntOrNull()
            val b = it.groupValues[2].toIntOrNull()
            if (a != null && b != null && a in 1..24 && b >= a && b <= 24) return a to b
        }
        // 中文/阿拉伯数字兜底："第3节" → (3,3)；"3" → (3,3)
        val single = Regex("第?\\s*(\\d{1,2})\\s*节").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: text.trim().takeIf { it.matches(Regex("\\d{1,2}")) }?.toIntOrNull()
            ?: CHINESE_NUMBERS.entries.firstOrNull { text.contains("第${it.key}大节") }?.value?.let { it * 2 - 1 }
        if (single != null && single in 1..24) {
            return if (text.contains("大节")) single to (single + 1) else single to single
        }
        // "1、2" 或 "1,2" 形式的节次列。
        // 必须排除含「周」的文本，否则 "1-16(周)" 会被误判成第 1-16 节。
        if (!text.contains("周") && text.length <= 10) {
            val digits = Regex("\\d{1,2}").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
            if (digits.size >= 2 && digits.max() <= 24) return digits.min() to digits.max()
        }
        return null
    }

    /** 解析周次，返回若干 (start, end) 区间；`1-8,10-12(周)` → [(1,8),(10,12)] */
    private fun parseWeekRanges(text: String): List<Pair<Int, Int>> {
        val match = WEEK_PATTERN.find(text) ?: return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        match.groupValues[1].split(',').forEach { part ->
            val p = part.trim()
            when {
                p.contains('-') -> {
                    val bits = p.split('-')
                    val a = bits.getOrNull(0)?.trim()?.toIntOrNull()
                    val b = bits.getOrNull(1)?.trim()?.toIntOrNull()
                    if (a != null && b != null && a in 1..30 && b in a..30) ranges.add(a to b)
                }
                p.toIntOrNull()?.let { it in 1..30 } == true -> ranges.add(p.toInt() to p.toInt())
            }
        }
        return ranges
    }
}
