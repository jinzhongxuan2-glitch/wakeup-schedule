package com.wakeup.schedule.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.core.jw.JwClient
import com.wakeup.schedule.core.jw.JwCourse
import com.wakeup.schedule.core.jw.JwException
import com.wakeup.schedule.core.jw.JwLoginAnalysis
import com.wakeup.schedule.core.jw.JwLoginPage
import com.wakeup.schedule.core.jw.JwLoginResult
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
 * 登录流程（实测学校 CAS 行为）：
 * 1. 打开登录页并**询问服务端该账号是否需要验证码**（页面里始终有验证码标记，不能据此阻断）
 * 2. 不需要 → 直接登录；需要 → 显示验证码图片让用户输入
 * 3. 登录失败时按返回页面的错误文案区分「密码错误」与「验证码错误」，
 *    后者自动换一张新验证码；`execution` 是一次性令牌，重试会重新取登录页
 *
 * 隐私：学号密码只用于本次登录请求，不写数据库、不写日志（日志只记密码长度）。
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

    // 验证码阶段
    var loginPage by remember { mutableStateOf<JwLoginPage?>(null) }
    var captchaImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var captchaInput by remember { mutableStateOf("") }

    var courses by remember { mutableStateOf<List<JwCourse>>(emptyList()) }
    var terms by remember { mutableStateOf<List<JwTerm>>(emptyList()) }
    var selectedTerm by remember { mutableStateOf<JwTerm?>(null) }

    var logText by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    val client = remember { JwClient() }

    fun decode(bytes: ByteArray?): ImageBitmap? = bytes?.let {
        runCatching {
            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }.getOrNull()
    }

    /** 登录成功后读取课表 */
    fun loadSchedule(term: JwTerm?) {
        scope.launch {
            busy = true
            error = null
            status = if (term == null) "正在读取课表…" else "正在读取「${term.name}」…"
            try {
                val result = withContext(Dispatchers.IO) {
                    val html = client.fetchScheduleHtml(term?.id)
                    JwScheduleHtmlParser.parse(html, client.log) to JwScheduleHtmlParser.parseTerms(html)
                }
                courses = result.first
                if (result.second.isNotEmpty()) {
                    terms = result.second
                    selectedTerm = result.second.firstOrNull { it.id == term?.id } ?: result.second.first()
                }
                status = "读取成功：${courses.size} 门课程，${courses.sumOf { it.slots.size }} 个时间段"
            } catch (e: JwException) {
                error = e.message
                status = ""
            } catch (e: Exception) {
                error = "读取课表失败：${e.message ?: e.javaClass.simpleName}"
                status = ""
            } finally {
                logText = client.log.text()
                busy = false
            }
        }
    }

    /** 提交登录（captcha 为 null 表示不带验证码） */
    fun submitLogin(captcha: String?) {
        scope.launch {
            busy = true
            error = null
            status = "正在登录…"
            try {
                val result: JwLoginResult = withContext(Dispatchers.IO) {
                    // execution 一次性：每次提交都确保用的是当前登录页状态
                    val page = loginPage ?: client.fetchLoginPage().also { loginPage = it }
                    client.submitLogin(studentId.trim(), password, page, captcha)
                }
                when {
                    result.success -> {
                        captchaImage = null
                        captchaInput = ""
                        status = "登录成功"
                        loadSchedule(null)
                    }

                    result.outcome == JwLoginAnalysis.Outcome.NEED_CAPTCHA -> {
                        // 换新登录页 + 新验证码（旧 execution 已失效）
                        val img = withContext(Dispatchers.IO) {
                            loginPage = client.fetchLoginPage()
                            client.fetchCaptcha()
                        }
                        captchaImage = decode(img)
                        captchaInput = ""
                        error = if (captcha.isNullOrBlank())
                            "该账号需要验证码，请按图片输入" else "验证码不正确或已过期，已换一张，请重新输入"
                        status = ""
                    }

                    result.outcome == JwLoginAnalysis.Outcome.BAD_CREDENTIALS -> {
                        error = "账号或密码错误，请检查后重试"
                        status = ""
                    }

                    else -> {
                        error = "登录未成功（原因未识别），请展开调试日志反馈给开发者"
                        status = ""
                    }
                }
            } catch (e: JwException) {
                error = e.message
                status = ""
            } catch (e: Exception) {
                error = "登录失败：${e.message ?: e.javaClass.simpleName}"
                status = ""
            } finally {
                logText = client.log.text()
                busy = false
            }
        }
    }

    /** 第一步：取登录页 + 问服务端是否需要验证码 */
    fun startLogin() {
        if (studentId.isBlank() || password.isBlank()) {
            error = "请先填写学号与密码"
            return
        }
        scope.launch {
            busy = true
            error = null
            courses = emptyList()
            status = "正在打开统一认证登录页…"
            try {
                val page = withContext(Dispatchers.IO) { client.fetchLoginPage().also { loginPage = it } }
                val need = withContext(Dispatchers.IO) { client.checkNeedCaptcha(studentId.trim()) }
                status = if (need) "该账号当前需要验证码" else "无需验证码，正在登录…"
                if (need) {
                    val img = withContext(Dispatchers.IO) { client.fetchCaptcha() }
                    captchaImage = decode(img)
                    captchaInput = ""
                    if (img == null) error = "验证码图片获取失败，可点「换一张」重试"
                } else {
                    captchaImage = null
                    submitLogin(null)
                }
            } catch (e: JwException) {
                error = e.message
                status = ""
            } catch (e: Exception) {
                error = "打开登录页失败：${e.message ?: e.javaClass.simpleName}"
                status = ""
            } finally {
                logText = client.log.text()
                busy = false
            }
        }
    }

    /** 换一张验证码（同一登录页会话内刷新即可） */
    fun refreshCaptcha() {
        scope.launch {
            error = null
            val img = withContext(Dispatchers.IO) { client.fetchCaptcha() }
            captchaImage = decode(img)
            captchaInput = ""
            logText = client.log.text()
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
                            "· 教务系统通常需要校园网或学校 VPN，校外失败属正常\n" +
                            "· 验证码仅在服务端要求时才会出现",
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
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                enabled = !busy,
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

            // ===== 验证码（仅在服务端要求时出现） =====
            if (captchaImage != null) {
                Spacer(Modifier.height(14.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("请输入验证码", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                bitmap = captchaImage!!,
                                contentDescription = "验证码图片",
                                modifier = Modifier
                                    .size(width = 120.dp, height = 44.dp)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { refreshCaptcha() }
                            )
                            Spacer(Modifier.width(10.dp))
                            TextButton(onClick = { refreshCaptcha() }, enabled = !busy) {
                                Text("换一张", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = captchaInput,
                            onValueChange = { captchaInput = it.take(10) },
                            label = { Text("验证码") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { submitLogin(captchaInput.trim()) },
                            enabled = !busy && captchaInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("提交验证码并登录") }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { startLogin() },
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
                                loadSchedule(term)
                            },
                            label = { Text(term.name, fontSize = 12.sp) }
                        )
                    }
                }
            }

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
                        modifier = Modifier.padding(10.dp),
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
