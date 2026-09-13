package com.wakeup.schedule.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.ui.Routes
import com.wakeup.schedule.ui.schedule.ScheduleViewModel
import com.wakeup.schedule.ui.schedule.slotTimeText
import com.wakeup.schedule.ui.schedule.slotWeekText

/** 已添课程列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListScreen(app: WakeUpApp, nav: NavController) {
    val vm: ScheduleViewModel = viewModel(factory = ScheduleViewModel.factory(app))
    val state by vm.uiState.collectAsState()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已添课程") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 搜索：课程名 / 教师 / 地点
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索课程名、教师或地点") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "清空") }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            val filtered = remember(query, state.courses, state.slots) {
                if (query.isBlank()) state.courses
                else {
                    val q = query.trim()
                    state.courses.filter { c ->
                        val slotText = state.slots
                            .filter { it.courseId == c.id }
                            .joinToString(" ") { it.location }
                        c.name.contains(q, true) ||
                            c.teacher.contains(q, true) ||
                            c.note.contains(q, true) ||
                            slotText.contains(q, true)
                    }
                }
            }

            when {
                state.courses.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { Text("还没有添加课程", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { Text("没有匹配「$query」的课程", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) {
                    items(filtered, key = { it.id }) { course ->
                        val slots = state.slots.filter { it.courseId == course.id }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable { nav.navigate(Routes.edit(course.id)) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(course.color)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(course.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    if (course.teacher.isNotBlank()) {
                                        Text(course.teacher, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    slots.forEach { s ->
                                        Text(
                                            "${slotTimeText(s)}  ${slotWeekText(s)}" +
                                                if (s.location.isNotBlank()) "  @${s.location}" else "",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
