package dev.hearth.audio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class HearthWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { updateAll(context) }
    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HearthWidget::class.java))
            if (ids.isEmpty()) return
            val repo = (context.applicationContext as HearthApp).library
            val book = repo.state.value.books.firstOrNull { it.id == repo.preferences.getString("currentBook", null) }
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_title, book?.title ?: "Hearth · Choose a book")
            views.setTextViewText(R.id.widget_play, if (PlaybackService.state.value.playing) "Ⅱ" else "▶")
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_title, open)
            listOf(R.id.widget_back to "dev.hearth.BACK", R.id.widget_play to "dev.hearth.TOGGLE", R.id.widget_next to "dev.hearth.FORWARD").forEach { (id, action) ->
                val intent = Intent(context, PlaybackService::class.java).setAction(action)
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val pending = when {
                    book == null -> open
                    action == "dev.hearth.TOGGLE" -> PendingIntent.getForegroundService(context, id, intent, flags)
                    else -> PendingIntent.getService(context, id, intent, flags)
                }
                views.setOnClickPendingIntent(id, pending)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
