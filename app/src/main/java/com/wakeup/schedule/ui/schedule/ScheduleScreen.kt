package com.wakeup.schedule.ui.schedule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.data.CourseEntity
import com.wakeup.schedule.data.TimeSlotEntity
import com.wakeup.schedule.data.WeekType
import com.wakeup.schedule.ui.Routes
import com.wakeup.schedule.update.AppUpdater
import com.wakeup.schedule.update.RemoteVersion
import com.wakeup.schedule.update.UpdateChecker
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun visibleInWeek(slot: TimeSlotEntity, week: Int): Boolean =
    week in slot.startWeek..slot.endWeek && WeekType.matches(slot.weekType, week)

internal fun slotTimeText(slot: TimeSlotEntity): String {
    val end = slot.startSection + slot.sectionCount - 1
    return "周${WEEKDAYS[slot.dayOfWeek - 1]} 第${slot.startSection}-${end}节"
}

internal fun slotWeekText(slot: TimeSlotEntity): String {
    val base = if (slot.startWeek == 1 && slot.endWeek >= 20) "1-${slot.endWeek}周"
    else "${slot.startWeek}-${slot.endWeek}周"
    return "$base · ${WeekType.label(slot.weekType)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(app: WakeUpApp, nav: NavController) {
    val vm: ScheduleViewModel = viewModel(factory = ScheduleViewModel.factory(app))
    val state by vm.uiState.collectAsState()
    val toast by vm.toast.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var showPanel by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var shareCode by remember { mutableStateOf<String?>(null) }
    var showPasteCode by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var showWeekEditor by remember { mutableStateOf(false) }

    // 应用内更新：启动时检查一次
    var updateInfo by remember { mutableStateOf<RemoteVersion?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { updateInfo = UpdateChecker.check() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { vm.exportJson(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importJson(it) } }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importCsv(it) } }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.table == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                EmptyTableView(onCreate = { showPanel = true })
            }

            else -> {
                val table = state.table!!
                val pagerState = rememberPagerState(initialPage = state.currentWeek - 1) { table.totalWeeks }
                val pagerWeek = pagerState.currentPage + 1
                val isCurrentWeek = pagerWeek == state.currentWeek
                val today = LocalDate.now()
                val monday = remember(pagerWeek, table.startDate) {
                    java.time.LocalDate.parse(table.startDate).plusWeeks((pagerWeek - 1).toLong())
                }
                val courseById = state.courses.associateBy { it.id }
                val dayCount = if (table.showWeekend) 7 else 5

                // 课表背景：默认 / 纯色 / 相册图片（低透明度水印式）
                val bgPainter = if (table.bgType == 2 && table.bgValue.isNotBlank())
                    rememberAsyncImagePainter(table.bgValue) else null
                val bgModifier = when {
                    table.bgType == 1 -> Modifier.background(
                        table.bgValue.toIntOrNull()?.let { Color(it) } ?: MaterialTheme.colorScheme.background
                    )
                    bgPainter != null -> Modifier.paint(bgPainter, contentScale = ContentScale.Crop, alpha = 0.25f)
                    else -> Modifier.background(MaterialTheme.colorScheme.background)
                }

                Column(Modifier.fillMaxSize().then(bgModifier).statusBarsPadding()) {
                    // ===== 顶栏 =====
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).clickable { showWeekEditor = true }) {
                            Text(
                                text = today.format(DateTimeFormatter.ofPattern("M月d日")),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "第${pagerWeek}周",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                if (isCurrentWeek) {
                                    Text(
                                        text = "周${WEEKDAYS[today.dayOfWeek.value - 1]}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                } else {
                                    Text(
                                        text = "非本周",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                            .clickable { scope.launch { pagerState.animateScrollToPage(state.currentWeek - 1) } }
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { nav.navigate(Routes.edit()) }) {
                            Icon(Icons.Filled.Add, "添加课程", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showImport = true }) {
                            Icon(Icons.Filled.FileDownload, "导入课表", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showExport = true }) {
                            Icon(Icons.Filled.FileUpload, "导出课表", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showPanel = true }) {
                            Icon(Icons.Outlined.MoreVert, "更多功能", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    // ===== 日期栏 =====
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${monday.monthValue}月",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(30.dp)
                        )
                        (1..dayCount).forEach { day ->
                            val date = monday.plusDays((day - 1).toLong())
                            val isToday = date == today
                            val faded = !isCurrentWeek && !isToday
                            Column(
                                Modifier.weight(1f).alpha(if (faded) 0.35f else 1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = WEEKDAYS[day - 1],
                                    fontSize = 11.sp,
                                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        fontSize = 13.sp,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // ===== 课表网格（横向翻页切周） =====
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        val week = page + 1
                        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                            val axisW = 30.dp
                            val gridW = maxWidth - axisW
                            val cellW = gridW / dayCount
                            val sectionH = maxHeight / table.maxSections

                            // 左侧时间轴
                            Column(Modifier.width(axisW).fillMaxSize()) {
                                (1..table.maxSections).forEach { sec ->
                                    val t = state.sectionTimes.firstOrNull { it.section == sec }
                                    Column(
                                        Modifier.width(axisW).height(sectionH),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = sec.toString(),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (state.showSectionTime && t != null) {
                                            Text(t.start, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(t.end, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }

                            // 网格线
                            Column(Modifier.offset(x = axisW).width(gridW).fillMaxSize()) {
                                (1 until table.maxSections).forEach { i ->
                                    Spacer(Modifier.height(sectionH - 0.5.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), thickness = 0.5.dp)
                                    Spacer(Modifier.height(0.5.dp))
                                }
                            }

                            // 课程块
                            state.slots.filter { it.dayOfWeek <= dayCount && visibleInWeek(it, week) }.forEach { slot ->
                                val course = courseById[slot.courseId] ?: return@forEach
                                Box(
                                    Modifier
                                        .offset(
                                            x = axisW + cellW * (slot.dayOfWeek - 1),
                                            y = sectionH * (slot.startSection - 1)
                                        )
                                        .width(cellW)
                                        .height(sectionH * slot.sectionCount)
                                        .padding(1.5.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(course.color).copy(alpha = table.blockAlpha))
                                        .clickable { detailCourse = course }
                                        .padding(4.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = course.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 5,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 15.sp
                                        )
                                        if (slot.location.isNotBlank()) {
                                            Text(
                                                text = "@${slot.location}",
                                                fontSize = 10.sp,
                                                color = Color.White.copy(alpha = 0.9f),
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                lineHeight = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ===== 更多功能面板 =====
                if (showPanel) {
                    ModalBottomSheet(
                        onDismissRequest = { showPanel = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ) {
                        FunctionPanel(
                            state = state,
                            pagerWeek = pagerWeek,
                            onJumpWeek = { w ->
                                scope.launch { pagerState.animateScrollToPage(w - 1) }
                            },
                            onSwitchTable = { vm.switchTable(it) },
                            onCreateTable = { name, date, weeks, sections -> vm.createTable(name, date, weeks, sections) },
                            onNavigate = { route ->
                                showPanel = false
                                nav.navigate(route)
                            },
                            onDismiss = { showPanel = false }
                        )
                    }
                }

                // ===== 修改当前周 =====
                if (showWeekEditor) {
                    var weekVal by remember { mutableFloatStateOf(state.currentWeek.toFloat()) }
                    AlertDialog(
                        onDismissRequest = { showWeekEditor = false },
                        title = { Text("修改当前周") },
                        text = {
                            Column {
                                Text("当前设置为：第 ${weekVal.toInt()} 周", fontSize = 15.sp)
                                Slider(
                                    value = weekVal,
                                    onValueChange = { weekVal = it },
                                    valueRange = 1f..table.totalWeeks.toFloat(),
                                    steps = (table.totalWeeks - 2).coerceAtLeast(0)
                                )
                                Text(
                                    "会根据所选周数自动调整学期开始日期",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                vm.setCurrentWeek(weekVal.toInt())
                                showWeekEditor = false
                            }) { Text("确定") }
                        },
                        dismissButton = { TextButton(onClick = { showWeekEditor = false }) { Text("取消") } }
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // ===== 导入对话框 =====
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入课表") },
            text = { Text("选择导入方式：") },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            showImport = false
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从备份文件导入") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImport = false
                            csvLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从 Excel/CSV 模板导入") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImport = false
                            showPasteCode = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从分享口令导入") }
                }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } }
        )
    }

    // ===== 导出对话框 =====
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出课表") },
            text = { Text("「${state.table?.name ?: ""}」的导出方式：") },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            showExport = false
                            val name = (state.table?.name ?: "课表").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            exportLauncher.launch("$name.wakeup.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出为备份文件") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showExport = false
                            vm.exportShareCode { code -> shareCode = code }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("生成分享口令") }
                }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("取消") } }
        )
    }

    // ===== 分享口令展示 =====
    shareCode?.let { code ->
        AlertDialog(
            onDismissRequest = { shareCode = null },
            title = { Text("分享口令") },
            text = {
                Text(
                    text = code.take(120) + if (code.length > 120) "…" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    shareCode = null
                }) { Text("复制口令") }
            },
            dismissButton = { TextButton(onClick = { shareCode = null }) { Text("关闭") } }
        )
    }

    // ===== 粘贴口令导入 =====
    if (showPasteCode) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteCode = false },
            title = { Text("从分享口令导入") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("粘贴 WKUP: 开头的口令") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPasteCode = false
                    vm.importShareCode(input)
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showPasteCode = false }) { Text("取消") } }
        )
    }

    // ===== 新版本提示 =====
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text("发现新版本 ${info.versionName}") },
            text = {
                Text(
                    if (info.notes.isBlank()) "有新版本可用，点击立即更新，下载完成后会自动调起安装。"
                    else info.notes
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppUpdater.downloadAndInstall(context, info.apkUrl, info.versionName)
                    updateInfo = null
                }) { Text("立即更新") }
            },
            dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("下次再说") } }
        )
    }

    // ===== 课程详情 =====
    detailCourse?.let { course ->
        val slots = state.slots.filter { it.courseId == course.id }
        AlertDialog(
            onDismissRequest = { detailCourse = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(course.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(course.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    if (course.teacher.isNotBlank()) Text("教师：${course.teacher}")
                    if (course.note.isNotBlank()) Text("备注：${course.note}")
                    Spacer(Modifier.height(8.dp))
                    slots.forEach { s ->
                        Text(slotTimeText(s), fontSize = 14.sp)
                        Text(
                            "${slotWeekText(s)}" + if (s.location.isNotBlank()) " · @${s.location}" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detailCourse = null
                    nav.navigate(Routes.edit(course.id))
                }) { Text("编辑") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.deleteCourse(course.id)
                    detailCourse = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun EmptyTableView(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("还没有课表", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("新建一张课表，或从备份 / 口令导入", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) { Text("开始") }
    }
}
