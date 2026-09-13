package com.wakeup.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.wakeup.schedule.MainActivity
import com.wakeup.schedule.R
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.data.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 桌面小部件：展示今日课程列表 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_today)

            val today = LocalDate.now()
            val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[today.dayOfWeek.value - 1]
            views.setTextViewText(
                R.id.widget_date,
                today.format(DateTimeFormatter.ofPattern("M月d日")) + " 周$weekday"
            )

            // 打开 App
            val openIntent = Intent(context, MainActivity::class.java)
            val openPi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPi)
            views.setPendingIntentTemplate(R.id.widget_list, openPi)

            // 列表数据
            val serviceIntent = Intent(context, TodayWidgetService::class.java)
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        }
    }
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private data class Item(val name: String, val detail: String, val time: String, val color: Int)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var items = listOf<Item>()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val app = WakeUpApp.get(context)
            val tableId = runBlocking { app.repository.prefs.currentTableId.first() }
            if (tableId <= 0) {
                items = emptyList()
                return
            }
            val blocks = app.repository.getTodayBlocksSync(tableId)
            val times = runBlocking { AppDatabase.get(context).sectionTimeDao().getByTable(tableId) }
            items = blocks.map { b ->
                val endSec = b.slot.startSection + b.slot.sectionCount - 1
                val detail = buildString {
                    append("第${b.slot.startSection}-${endSec}节")
                    if (b.slot.location.isNotBlank()) append("  @${b.slot.location}")
                }
                val startTime = times.firstOrNull { it.section == b.slot.startSection }?.start ?: ""
                Item(b.course.name, detail, startTime, b.course.color)
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val item = items[position]
            return RemoteViews(context.packageName, R.layout.widget_course_item).apply {
                setTextViewText(R.id.item_name, item.name)
                setTextViewText(R.id.item_detail, item.detail)
                setTextViewText(R.id.item_time, item.time)
                setInt(R.id.item_color_bar, "setBackgroundColor", item.color)
                setTextColor(R.id.item_time, item.color)
                val openIntent = Intent(context, MainActivity::class.java)
                // 整行都可点击：模板设置在 widget_list 上，这里给根布局与课程名都挂上 fill-in intent
                setOnClickFillInIntent(R.id.item_root, openIntent)
                setOnClickFillInIntent(R.id.item_name, openIntent)
            }
        }

        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
        override fun onDestroy() {}
    }
}
