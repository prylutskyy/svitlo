package ua.svitlo.app.ui

import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.LocalDate

internal data class DetailInterval(val start:Instant,val end:Instant?,val state:PowerState,val label:String,val estimated:Boolean=false)

/** No unreported day becomes an all-clear, and a plan never becomes a confirmed incident. */
internal fun detailsIntervals(schedule:Schedule?,date:LocalDate,now:Instant):List<DetailInterval>? {
    if(schedule==null)return null
    val start=date.atStartOfDay(KYIV).toInstant();val end=date.plusDays(1).atStartOfDay(KYIV).toInstant()
    val rows=mutableListOf<DetailInterval>()
    val published=schedule.days.any {it.date==date.toString() && it.slots.isNotEmpty()}
    if(published) schedule.publishedQueuePlan().chartSpans(start,end,now).filter {it.state!=PowerState.NO_SCHEDULED_OUTAGE}.forEach {
        rows+=DetailInterval(it.start,it.end,it.state,when(it.state) {
            PowerState.SCHEDULED_OUTAGE -> "Планове"
            PowerState.POSSIBLE_OUTAGE -> "За графіком"
            PowerState.EMERGENCY_OUTAGE -> "Аварійне"
            PowerState.CONFIRMED_OUTAGE -> "Повідомлене відключення"
            else -> "Немає даних"
        })
    }
    val incidents=(schedule.reportedOutages.orEmpty()+listOfNotNull(schedule.currentOutage)).distinctBy {Triple(it.startedAt,it.estimatedEnd,it.reason)}
    for(incident in incidents) {
        val began=incident.startedAt?.let(Instant::ofEpochMilli) ?: continue
        val finish=incident.estimatedEnd?.let(Instant::ofEpochMilli)
        if(finish!=null && finish<=began)continue
        if(finish==null && date>now.atZone(KYIV).toLocalDate())continue
        if(began>=end || (finish!=null && finish<=start))continue
        rows+=DetailInterval(maxOf(began,start),finish?.let {minOf(it,end)},
            if(incident.emergency) PowerState.EMERGENCY_OUTAGE else PowerState.CONFIRMED_OUTAGE,incident.kindLabel,true)
    }
    return if(!published && rows.isEmpty())null else rows.sortedBy {it.start}
}
