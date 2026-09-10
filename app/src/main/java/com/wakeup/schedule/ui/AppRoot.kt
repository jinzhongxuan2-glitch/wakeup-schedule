package com.wakeup.schedule.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.ui.edit.CourseEditScreen
import com.wakeup.schedule.ui.list.CourseListScreen
import com.wakeup.schedule.ui.schedule.ScheduleScreen
import com.wakeup.schedule.ui.settings.AppearanceScreen
import com.wakeup.schedule.ui.settings.GlobalSettingsScreen
import com.wakeup.schedule.ui.settings.TableManageScreen
import com.wakeup.schedule.ui.settings.TableSettingsScreen
import com.wakeup.schedule.ui.settings.TimeSettingsScreen

object Routes {
    const val SCHEDULE = "schedule"
    const val COURSE_LIST = "course_list"
    const val TABLE_SETTINGS = "table_settings"
    const val TIME_SETTINGS = "time_settings"
    const val GLOBAL_SETTINGS = "global_settings"
    const val TABLE_MANAGE = "table_manage"
    const val APPEARANCE = "appearance"

    fun edit(courseId: Long = -1L) = if (courseId > 0) "edit?courseId=$courseId" else "edit"
}

@Composable
fun AppRoot(app: WakeUpApp) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SCHEDULE) {
        composable(Routes.SCHEDULE) { ScheduleScreen(app, nav) }
        composable(
            route = "edit?courseId={courseId}",
            arguments = listOf(navArgument("courseId") {
                type = NavType.LongType
                defaultValue = -1L
            })
        ) { entry ->
            val courseId = entry.arguments?.getLong("courseId") ?: -1L
            CourseEditScreen(app = app, courseId = courseId, onBack = { nav.popBackStack() })
        }
        composable(Routes.COURSE_LIST) { CourseListScreen(app, nav) }
        composable(Routes.TABLE_SETTINGS) { TableSettingsScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.TIME_SETTINGS) { TimeSettingsScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.GLOBAL_SETTINGS) { GlobalSettingsScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.TABLE_MANAGE) { TableManageScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.APPEARANCE) { AppearanceScreen(app, onBack = { nav.popBackStack() }) }
    }
}
