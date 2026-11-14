package yos.music.player.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import yos.music.player.R

abstract class BaseSonifyWidgetProvider(
    private val widgetSize: SonifyWidgetUpdater.WidgetSize,
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        SonifyWidgetUpdater.updateSpecificWidgetsAsync(context, widgetSize, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        SonifyWidgetUpdater.updateSpecificWidgetsAsync(context, widgetSize, intArrayOf(appWidgetId))
    }
}

class SonifyWidget4x2Provider : BaseSonifyWidgetProvider(SonifyWidgetUpdater.WidgetSize.SIZE_4X2) {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == ACTION_SHELF_PREV || action == ACTION_SHELF_NEXT) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val views = RemoteViews(context.packageName, R.layout.widget_4x2_empty)
            @Suppress("DEPRECATION")
            if (action == ACTION_SHELF_PREV) {
                views.showPrevious(R.id.widget_queue_flipper)
            } else {
                views.showNext(R.id.widget_queue_flipper)
            }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val ACTION_SHELF_PREV = "yos.music.player.widget.ACTION_SHELF_PREV"
        const val ACTION_SHELF_NEXT = "yos.music.player.widget.ACTION_SHELF_NEXT"
    }
}

class SonifyWidget4x1Provider : BaseSonifyWidgetProvider(SonifyWidgetUpdater.WidgetSize.SIZE_4X1)

class SonifyWidget2x2Provider : BaseSonifyWidgetProvider(SonifyWidgetUpdater.WidgetSize.SIZE_2X2)
