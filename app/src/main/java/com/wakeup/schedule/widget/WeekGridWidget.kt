package com.wakeup.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import com.wakeup.schedule.MainActivity
import com.wakeup.schedule.R
import com.wakeup.schedule.WakeUpApp
import com.wakeup.schedule.core.ScheduleMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** 整周课表网格小部件：Canvas 绘制整周课表为 Bitmap */
class WeekGridWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val options = appWidgetManager.getAppWidgetOptions(id)
            val density = context.resources.displayMetrics.density
            val w = ((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)) * density).toInt().coerceAtLeast(600)
            val h = ((options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)) * density).toInt().coerceAtLeast(420)

            val bitmap = WeekGridRenderer.render(context, w, h)
            val views = RemoteViews(context.packageName, R.layout.widget_week_grid)
            views.setImageViewBitmap(R.id.week_grid_image, bitmap)

            val openIntent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.week_grid_image, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

object WeekGridRenderer {

    private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

    fun render(context: Context, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 背景
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xFA, 0xFF, 0xFF, 0xFF) }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 36f, 36f, bgPaint)

        val app = WakeUpApp.get(context)
        val repo = app.repository
        val tableId = runBlocking { repo.prefs.currentTableId.first() }
        val table = runBlocking { if (tableId > 0) repo.getTable(tableId) else null } ?: return bmp

        val today = LocalDate.now()
        val startDate = LocalDate.parse(table.startDate)
        val week = ScheduleMath.currentWeek(startDate, today, table.totalWeeks)
        val monday = ScheduleMath.mondayOfWeek(startDate, week)
        val dayCount = if (table.showWeekend) 7 else 5

        // 取数据
        val db = com.wakeup.schedule.data.AppDatabase.get(context)
        val courses = runBlocking { db.courseDao().getByTable(table.id) }.associateBy { it.id }
        val slots = runBlocking { db.timeSlotDao().getByTable(table.id) }
            .filter { it.dayOfWeek <= dayCount && ScheduleMath.visibleInWeek(week, it.startWeek, it.endWeek, it.weekType) }

        val pad = 18f
        val headerH = 64f
        val axisW = 30f
        val gridX = pad + axisW
        val gridW = width - pad * 2 - axisW
        val gridY = pad + headerH
        val gridH = height - pad * 2 - headerH
        val cellW = gridW / dayCount
        val cellH = gridH / table.maxSections

        // 日期表头
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9E9E9E"); textSize = 20f; textAlign = Paint.Align.CENTER
        }
        val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#26A69A"); textSize = 20f
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        (1..dayCount).forEach { d ->
            val date = monday.plusDays((d - 1).toLong())
            val isToday = date == today
            val cx = gridX + cellW * (d - 1) + cellW / 2
            canvas.drawText(WEEKDAYS[d - 1], cx, gridY - 34f, if (isToday) todayPaint else dayPaint)
            canvas.drawText("${date.dayOfMonth}", cx, gridY - 10f, if (isToday) todayPaint else dayPaint)
        }

        // 节次轴
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BDBDBD"); textSize = 16f; textAlign = Paint.Align.CENTER
        }
        (1..table.maxSections).forEach { s ->
            canvas.drawText("$s", pad + axisW / 2, gridY + cellH * (s - 0.5f) + 6f, secPaint)
        }

        // 网格线
        val linePaint = Paint().apply { color = Color.parseColor("#F0F0F0"); strokeWidth = 1f }
        (1 until table.maxSections).forEach { i ->
            val y = gridY + cellH * i
            canvas.drawLine(gridX, y, gridX + gridW, y, linePaint)
        }

        // 课程块
        val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 18f; isFakeBoldText = true
        }
        slots.forEach { slot ->
            val course = courses[slot.courseId] ?: return@forEach
            val left = gridX + cellW * (slot.dayOfWeek - 1) + 2f
            val top = gridY + cellH * (slot.startSection - 1) + 2f
            val right = gridX + cellW * slot.dayOfWeek - 2f
            val bottom = gridY + cellH * (slot.startSection + slot.sectionCount - 1) - 2f
            blockPaint.color = course.color
            blockPaint.alpha = (table.blockAlpha * 255).toInt()
            canvas.drawRoundRect(RectF(left, top, right, bottom), 10f, 10f, blockPaint)

            // 课程名（按宽度截断，最多 3 行）
            val maxChars = ((cellW - 10f) / textPaint.textSize).toInt().coerceAtLeast(1)
            val lines = course.name.chunked(maxChars).take(3)
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, left + 6f, top + 24f + i * 22f, textPaint)
            }
        }
        return bmp
    }
}
