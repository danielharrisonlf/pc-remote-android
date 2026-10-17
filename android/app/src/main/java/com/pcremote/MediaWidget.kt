package com.pcremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home screen widget: what the PC plays and three buttons. A tap starts
 * MediaControlService, which connects first when needed. It never polls:
 * MediaCoordinator redraws it when the connection or the playback changes.
 */
internal class MediaWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    // Drawing does not create the client just for this; it shows the idle text until it exists.
    val client = (context.applicationContext as RemoteApplication).existingClient
    manager.updateAppWidget(ids, views(context, client?.let { MediaRules.widget(it.view) } ?: IDLE))
  }

  companion object {
    private val IDLE = WidgetContent("PC Remote", "Toque ⏯ para conectar", false)

    fun update(context: Context, content: WidgetContent) {
      try {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, MediaWidget::class.java))
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, content))
      } catch (_: RuntimeException) { /* No widget host on this device. */ }
    }

    private fun views(context: Context, content: WidgetContent) = RemoteViews(context.packageName, R.layout.widget_media).apply {
      setTextViewText(R.id.widget_title, content.title)
      setTextViewText(R.id.widget_subtitle, content.subtitle)
      setImageViewResource(R.id.widget_toggle, if (content.playing) R.drawable.ic_pause else R.drawable.ic_play)
      setContentDescription(R.id.widget_toggle, if (content.playing) "Pausar no PC" else "Tocar no PC")
      setOnClickPendingIntent(R.id.widget_previous, MediaControlService.widgetIntent(context, MediaRules.PREVIOUS))
      setOnClickPendingIntent(R.id.widget_toggle, MediaControlService.widgetIntent(context, MediaRules.TOGGLE))
      setOnClickPendingIntent(R.id.widget_next, MediaControlService.widgetIntent(context, MediaRules.NEXT))
      val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MediaControlService.EXTRA_TAB, Tab.MEDIA.name)
      setOnClickPendingIntent(R.id.widget_text, PendingIntent.getActivity(context, 50, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
  }
}
