package com.wakeup.schedule.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.core.jw.JwClient
import com.wakeup.schedule.core.jw.JwCourse
import com.wakeup.schedule.core.jw.JwException
import com.wakeup.schedule.core.jw.JwScheduleHtmlParser
import com.wakeup.schedule.core.jw.JwTerm
import com.wakeup.schedule.core.jw.JwTermDate
import com.wakeup.schedule.data.CourseEntity
import com.wakeup.schedule.data.TimeSlotEntity
import com.wakeup.schedule.ui.theme.CourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从「中南大学本科教务系统」直接导入课表。
 *
 * 设计要点：
 * - 学号/密码只用于本次登录请求，不写入数据库、不写日志（日志里只记长度）
 * - 登录与解析全在 IO 线程；解析逻辑在 core 层有单元测试覆盖
 * - 出错时给出可操作提示（校园网/VPN、验证码、密码错误…）并保留调试日志供反馈
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwImportScreen(app: WakeUpApp, onBack: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    var courses by remember { mutableStateOf<List<JwCourse>>(emptyList()) }
    var terms by remember { mutableStateOf<List<JwTerm>>(emptyList()) }
    var selectedTerm by remember { mutableStateOf<JwTerm?>(null) }
    var loggedIn by remember { mutableStateOf(false) }

    var logText by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    val client = remember { JwClient() }

    /** 登录（仅首次）+ 读取指定学期（null 表示教务系统默认学期） */
    fun load(term: JwTerm?) {
        if (studentId.isBlank() || password.isBlank()) {
            error = "请先填写学号与密码"
            return
        }
        scope.launch {
            busy = true
            error = null
            status = if (term == null) "正在登录并读取课表…" else "正在读取「${term.name}」…"
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!loggedIn) {
                        client.login(studentId.trim(), password)
                    }
                    val html = client.fetchScheduleHtml(term?.id)
                    JwScheduleHtmlParser.parse(html, client.log) to
                        JwScheduleHtmlParser.parseTerms(html)
                }
                loggedIn = true
                courses = result.first
                if (result.second.isNotEmpty()) {
                    terms = result.second
                    selectedTerm = result.second.firstOrNull { it.id == term?.id }
                        ?: result.second.firstOrNull { it.name.isNotBlank() }
                } else if (term == null) {
                    selectedTerm = null
                }
                status = "读取成功：${courses.size} 门课程，" +
                    "${courses.sumOf { it.slots.size }} 个时间段"
            } catch (e: JwException) {
                error = e.message
                status = ""
                if (e.kind == com.wakeup.schedule.core.jw.JwErrorKind.BAD_CREDENTIALS) loggedIn = false
            } catch (e: Exception) {
                error = "读取失败：${e.message ?: e.javaClass.simpleName}"
                status = ""
            } finally {
                logText = client.log.text()
                busy = false
            }
        }
    }

    /** 导入为一门新课表 */
    fun doImport() {
        scope.launch {
            busy = true
            error = null
            try {
                val termLabel = selectedTerm?.name.orEmpty()
                val tableName = "中南大学 $termLabel".trim()
                val startDate = JwTermDate.guessStartDate(selectedTerm?.id.orEmpty())
                val maxSection = courses.flatMap { it.slots }
                    .maxOfOrNull { it.startSection + it.sectionCount - 1 }
                    ?.coerceAtLeast(12) ?: 12

                val tableId = repo.createTable(
                    name = tableName.ifBlank { "中南大学课表" },
                    startDate = startDate,
                    totalWeeks = 20,
                    maxSections = maxSection
                )
                courses.forEachIndexed { index, course ->
                    repo.saveCourse(
                        CourseEntity(
                            tableId = tableId,
                            name = course.name,
                            teacher = course.teacher,
                            color = CourseColors[index % CourseColors.size].toArgb()
                        ),
                        course.slots.map { slot ->
                            TimeSlotEntity(
                                courseId = 0,
                                dayOfWeek = slot.dayOfWeek,
                                startSection = slot.startSection,
                                sectionCount = slot.sectionCount,
                                startWeek = slot.startWeek,
                                endWeek = slot.endWeek,
                                weekType = slot.weekType,
                                location = slot.location
                            )
                        }
                    )
                }
                repo.prefs.setCurrentTable(tableId)
                status = "已导入「$tableName」：${courses.size} 门课程（开学日按 $startDate 推算，可在课表设置里修改）"
                onBack()
            } catch (e: Exception) {
                error = "导入失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从教务系统导入") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 说明
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("中南大学本科教务系统", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "csujwc.its.csu.edu.cn（统一认证登录）\n" +
                            "· 学号密码仅用于本次登录请求，不会保存到手机\n" +
                            "· 教务系统通常需要校园网或学校 VPN，校外失败属正常",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = studentId,
                onValueChange = { studentId = it.trim() },
                label = { Text("学号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = "切换密码可见性"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { load(null) },
                enabled = !busy && studentId.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (courses.isEmpty()) "登录并读取课表" else "重新读取")
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, lineHeight = 18.sp)
            }

            error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        lineHeight = 18.sp
                    )
                }
            }

            // 学期切换
            if (courses.isNotEmpty() && terms.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("学期", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(terms, key = { it.id }) { term ->
                        FilterChip(
                            selected = term.id == selectedTerm?.id,
                            onClick = {
                                selectedTerm = term
                                load(term)
                            },
                            label = { Text(term.name, fontSize = 12.sp) }
                        )
                    }
                }
            }

            // 预览 + 导入
            if (courses.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text(
                    "预览（共 ${courses.size} 门 / ${courses.sumOf { it.slots.size }} 个时间段）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                courses.take(12).forEach { course ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(course.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${course.slots.size} 段" + if (course.teacher.isBlank()) "" else " · ${course.teacher}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (courses.size > 12) {
                    Text(
                        "…… 还有 ${courses.size - 12} 门（导入后可在已添课程里查看）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { doImport() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导入为一门新课表") }
            }

            // 调试日志
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "收起调试日志" else "查看调试日志", fontSize = 12.sp)
                }
                if (showLog && logText.isNotBlank()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(logText)) }) {
                        Text("复制", fontSize = 12.sp)
                    }
                }
            }
            if (showLog) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        logText.ifBlank { "（暂无日志，先点一次「登录并读取课表」）" },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "导入失败时把这份日志复制给开发者即可定位问题（不含密码）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
