package com.wakeup.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 统一触发两个桌面小部件的刷新 */
object WidgetUpdater {

    /** 数据变更后调用：让「今日课程」和「整周课表」两个小部件立刻重绘 */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        listOf(
            TodayWidgetProvider::class.java,
            WeekGridWidgetProvider::class.java
        ).forEach { provider ->
            runCatching {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isEmpty()) return@runCatching
                val intent = Intent(context, provider).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
