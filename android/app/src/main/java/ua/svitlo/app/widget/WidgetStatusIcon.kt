package ua.svitlo.app.widget

import android.widget.RemoteViews
import ua.svitlo.app.R
import ua.svitlo.app.domain.*

/** Always describes the current source state, independently of the next event shown on a card. */
internal object WidgetStatusIcon {
    fun resource(state: PowerState) = when (state) {
        in OUTAGE_STATES -> R.drawable.ic_bolt_off
        PowerState.NO_SCHEDULED_OUTAGE -> R.drawable.ic_bolt
        else -> R.drawable.ic_power_unknown
    }
    fun description(state: PowerState) = when (state) {
        PowerState.SCHEDULED_OUTAGE,PowerState.POSSIBLE_OUTAGE -> "Зараз відключення за графіком"
        PowerState.CONFIRMED_OUTAGE,PowerState.EMERGENCY_OUTAGE -> "Зараз повідомлене відключення"
        PowerState.NO_SCHEDULED_OUTAGE -> "Зараз без відключень за графіком"
        else -> "Стан уточнюється"
    }
    fun color(state: PowerState, colors: WidgetColors, monochrome: Boolean): Int = when {
        monochrome -> colors.ink
        state == PowerState.EMERGENCY_OUTAGE -> if (colors.dark) 0xFFFF8983.toInt() else 0xFFAA2A23.toInt()
        state in OUTAGE_STATES -> colors.accent ?: colors.ink
        else -> colors.muted
    }
    fun apply(views: RemoteViews, state: PowerState, colors: WidgetColors, monochrome: Boolean) {
        views.setImageViewResource(R.id.focus_power_icon,resource(state))
        views.setInt(R.id.focus_power_icon,"setColorFilter",color(state,colors,monochrome))
        views.setContentDescription(R.id.focus_power_icon,description(state))
    }
}
