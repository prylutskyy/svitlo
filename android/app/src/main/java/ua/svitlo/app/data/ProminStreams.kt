package ua.svitlo.app.data

import ua.svitlo.app.domain.*
import java.time.Instant

/** Independent upstream streams; an absent hourly plan must not erase address events. */
object ProminStreams {
    fun combine(config: ProminConfig,address: SavedAddress,plan: Result<String>?,events: Result<String>?,now: Instant): Schedule {
        val expectsEvents=config.outages && address.street.id!="queue"
        if(config.addressSchedule && !expectsEvents) throw VoeFormatException("Для цього міста виберіть будинок: Промінь надає відключення за адресою, а не за чергою.")
        val parsedPlan=plan?.mapCatching { ProminParser.schedule(config.copy(outages=false),it,null,address,now) }
        val parsedEvents=events?.mapCatching { ProminParser.events(it,address,now) }
        if(parsedPlan?.isSuccess!=true && parsedEvents?.isSuccess!=true)
            throw (parsedPlan?.exceptionOrNull() ?: parsedEvents?.exceptionOrNull() ?: VoeFormatException("Промінь не повернув дані"))
        val p=parsedPlan?.getOrNull()
        val e=parsedEvents?.getOrNull()
        val issues=mutableListOf<String>()
        val planBad=!config.addressSchedule && (p==null || p.sourceStale)
        val eventsBad=expectsEvents && (e==null || e.stale)
        if(planBad) issues += "Графік: ${parsedPlan?.exceptionOrNull()?.message ?: "дані застаріли"}"
        if(eventsBad) issues += "Адресні події: ${parsedEvents?.exceptionOrNull()?.message ?: "дані застаріли"}"
        val today=now.atZone(KYIV).toLocalDate()
        val days=if(config.addressSchedule && e!=null && !e.stale && !e.oldRecords) {
            // The current incident list is not an archive of the already elapsed day.
            val checked=Instant.ofEpochMilli(e.fetchedAt).atZone(KYIV)
            val minute=if(checked.toLocalDate()==today) checked.hour*60+checked.minute else 1440
            listOf(ScheduleDay("Сьогодні",today.toString(),buildList {
                if(minute>0) add(Slot(0,minute,PowerState.UNKNOWN))
                if(minute<1440) add(Slot(minute,1440,PowerState.NO_SCHEDULED_OUTAGE))
            }),ScheduleDay("Завтра",today.plusDays(1).toString(),listOf(Slot(0,1440,PowerState.UNKNOWN))))
        } else p?.days ?: listOf(ScheduleDay("Сьогодні",today.toString(),listOf(Slot(0,1440,PowerState.UNKNOWN))))
        val incidents=e?.takeUnless { it.stale }?.incidents.orEmpty()
        val current=incidents.filter { it.startedAt!!<=now.toEpochMilli() }.sortedWith(compareByDescending<CurrentOutage>{it.emergency}.thenByDescending{it.startedAt}).firstOrNull()
        val messages=mutableListOf("Джерело: Промінь · ${config.city.name}")
        if(config.addressSchedule) messages += "Відключення перевіряються за адресними повідомленнями. Окремого графіка за чергами Промінь для цього міста не надає. Завтра невідоме, доки немає опублікованих подій."
        else messages += p?.messages.orEmpty().filterNot { it.startsWith("Джерело:") || (config.outages && it.contains("не надає адресних аварій")) }
        if(config.outages && !expectsEvents) messages += "Виберіть будинок, щоб бачити адресні аварії."
        if(expectsEvents && !eventsBad && incidents.isEmpty()) messages += "Адресних відключень не повідомлено. Це не вимір наявності електроенергії."
        if(e?.oldRecords==true) messages += "Давні адресні записи не визначають поточний стан."
        messages += issues
        val at=listOfNotNull(p?.fetchedAt,e?.fetchedAt).maxOrNull()!!
        return Schedule(at,days,messages,current,p?.queue,"https://promin.vlasen.com${config.city.path}",incidents,
            sourceStale=(p==null || p.sourceStale) && (e==null || e.stale),
            planFetchedAt=if(config.addressSchedule) e?.fetchedAt else p?.fetchedAt,outagesFetchedAt=e?.fetchedAt,
            planUnavailable=if(config.addressSchedule) eventsBad || e?.oldRecords==true else planBad,outagesUnavailable=eventsBad,
            outagesExpected=expectsEvents,streamIssues=issues)
    }
}
