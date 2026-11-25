package io.github.sargemck.practicalheadphones.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.sargemck.practicalheadphones.AppGraph
import io.github.sargemck.practicalheadphones.MainActivity
import io.github.sargemck.practicalheadphones.R
import io.github.sargemck.practicalheadphones.service.HeadphoneService
import io.github.sargemck.practicalheadphones.state.HeadphoneState

/**
 * Material You home screen widget displaying battery, active mode, and one-tap controls.
 */
class HeadphoneWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = AppGraph.controller.value?.state?.value
        val views = buildViews(context, WidgetModel.from(state))
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun updateAll(context: Context, state: HeadphoneState?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HeadphoneWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, WidgetModel.from(state))
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context, model: WidgetModel): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.headphone_widget)
            views.setTextViewText(R.id.widget_title, model.title)
            views.setTextViewText(R.id.widget_subtitle, model.subtitle)

            val openIntent = PendingIntent.getActivity(
                context,
                100,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)

            bindButton(context, views, R.id.widget_btn_primary, model.primaryAction, 101)
            bindButton(context, views, R.id.widget_btn_secondary, model.secondaryAction, 102)
            return views
        }

        private fun bindButton(
            context: Context,
            views: RemoteViews,
            viewId: Int,
            action: WidgetAction?,
            requestCode: Int,
        ) {
            if (action == null) {
                views.setViewVisibility(viewId, View.GONE)
                return
            }
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setTextViewText(viewId, action.label)
            val serviceAction = when (action) {
                WidgetAction.NextMode -> HeadphoneService.ACTION_NEXT_MODE
                WidgetAction.AncOn, WidgetAction.AncOff -> HeadphoneService.ACTION_TOGGLE_ANC
                WidgetAction.Reconnect -> HeadphoneService.ACTION_RECONNECT
            }
            val pending = PendingIntent.getForegroundService(
                context,
                requestCode,
                Intent(context, HeadphoneService::class.java).setAction(serviceAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(viewId, pending)
        }
    }
}
