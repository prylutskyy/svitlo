package ua.svitlo.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.launch
import ua.svitlo.app.app

open class SvitloWidgetProvider : AppWidgetProvider() {
    private fun run(context: Context, action: suspend () -> Unit) {
        val pending = goAsync()
        context.app.scope.launch { try { action() } finally { pending.finish() } }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = run(context) {
        val state = context.app.store.load()
        ids.forEach { SvitloWidgets.render(context, state, it) }
        context.app.reconcile()
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = run(context) {
        val state = context.app.store.load()
        SvitloWidgets.render(context, state, id)
        context.app.scheduler.refreshIfDue(state)
    }

    override fun onDeleted(context: Context, ids: IntArray) = run(context) {
        context.app.updateControl({ state -> state.copy(widgets = state.widgets.orEmpty().filterNot { it.widgetId in ids }) })
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) = run(context) {
        val mapping = oldIds.zip(newIds).toMap()
        context.app.updateControl({ state -> state.copy(widgets = state.widgets.orEmpty().map { config ->
            config.copy(widgetId = mapping[config.widgetId] ?: config.widgetId)
        }) })
    }
}

class ShortSvitloWidgetProvider : SvitloWidgetProvider()

class CompactSvitloWidgetProvider : SvitloWidgetProvider()
class DetailedSvitloWidgetProvider : SvitloWidgetProvider()

object WidgetFormats {
    fun style(provider: android.content.ComponentName?) = when (provider?.className) {
        ShortSvitloWidgetProvider::class.java.name -> ua.svitlo.app.domain.WidgetStyle.SHORT
        CompactSvitloWidgetProvider::class.java.name -> ua.svitlo.app.domain.WidgetStyle.MONO
        DetailedSvitloWidgetProvider::class.java.name -> ua.svitlo.app.domain.WidgetStyle.TIMELINE
        else -> ua.svitlo.app.domain.WidgetStyle.CARD
    }
    fun title(provider: android.content.ComponentName?) = when (style(provider)) {
        ua.svitlo.app.domain.WidgetStyle.SHORT -> "Коротко · 2 × 1"
        ua.svitlo.app.domain.WidgetStyle.MONO -> "Моно · 2 × 2"
        ua.svitlo.app.domain.WidgetStyle.TIMELINE -> "Адреси · 4 × 3"
        else -> "Картка · 4 × 2"
    }
    fun components(context: Context) = listOf(SvitloWidgetProvider::class.java,
        CompactSvitloWidgetProvider::class.java, DetailedSvitloWidgetProvider::class.java, ShortSvitloWidgetProvider::class.java)
        .map { android.content.ComponentName(context, it) }
    fun defaultSize(provider: android.content.ComponentName?): Pair<Int,Int> = when (provider?.className) {
        ShortSvitloWidgetProvider::class.java.name -> 110 to 56
        CompactSvitloWidgetProvider::class.java.name -> 110 to 110
        DetailedSvitloWidgetProvider::class.java.name -> 250 to 180
        else -> 250 to 110
    }
}
