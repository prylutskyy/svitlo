package ua.svitlo.app.domain

import java.time.Instant

/** Evidence from VOE, never a measurement of voltage. Missing coverage stays unknown. */
data class HistoryInterval(val start: Long, val end: Long, val state: PowerState)
data class HistoryTail(val addressId: String, val intervals: List<HistoryInterval>)
data class HistoryIncident(val id: Long, val start: Long, val firstSeen: Long, val lastSeen: Long,
    val disappearedAt: Long?, val emergency: Boolean, val reason: String, val estimatedEnd: Long?,
    val sourceStart: Boolean)
data class HistoryReport(val start: Long, val end: Long, val intervals: List<HistoryInterval>,
    val incidents: List<HistoryIncident>, val recordedSince: Long?) {
    fun millis(state: PowerState) = intervals.filter { it.state == state }.sumOf { it.end - it.start }
    val outageMillis get() = millis(PowerState.EMERGENCY_OUTAGE) + millis(PowerState.CONFIRMED_OUTAGE)
    val totalMillis get() = (end - start).coerceAtLeast(0)
}

object PowerHistory {
    /** Do not let a cached graph fill an offline gap or count future predictions as history. */
    fun observed(schedule: Schedule, until: Long): List<HistoryInterval> {
        val start = Instant.ofEpochMilli(schedule.fetchedAt)
        val end = minOf(until, start.plus(schedule.freshness(start)).toEpochMilli())
        if (end <= schedule.fetchedAt) return emptyList()
        return schedule.chartSpans(start, Instant.ofEpochMilli(end), start)
            .filter { it.state != PowerState.UNKNOWN }
            .map { HistoryInterval(it.start.toEpochMilli(), it.end.toEpochMilli(), it.state) }
    }

    /** Sweep overlapping evidence once. Emergency > planned > possible > clear; no double counting. */
    fun resolve(input: List<HistoryInterval>, start: Long, end: Long): List<HistoryInterval> {
        if (end <= start) return emptyList()
        val changes = sortedMapOf<Long, MutableList<Pair<PowerState, Int>>>()
        changes[start] = mutableListOf(); changes[end] = mutableListOf()
        input.forEach {
            val a = maxOf(start, it.start); val b = minOf(end, it.end)
            if (b > a && it.state != PowerState.UNKNOWN) {
                changes.getOrPut(a) { mutableListOf() }.add(it.state to 1)
                changes.getOrPut(b) { mutableListOf() }.add(it.state to -1)
            }
        }
        val priority = listOf(PowerState.EMERGENCY_OUTAGE, PowerState.CONFIRMED_OUTAGE,
            PowerState.POSSIBLE_OUTAGE, PowerState.SCHEDULED_OUTAGE, PowerState.NO_SCHEDULED_OUTAGE)
        val counts = mutableMapOf<PowerState, Int>()
        val result = mutableListOf<HistoryInterval>()
        val bounds = changes.keys.toList()
        bounds.zipWithNext().forEach { (a,b) ->
            changes.getValue(a).forEach { (state, delta) -> counts[state] = (counts[state] ?: 0) + delta }
            val state = priority.firstOrNull { (counts[it] ?: 0) > 0 } ?: PowerState.UNKNOWN
            val last = result.lastOrNull()
            if (last?.state == state && last.end == a) result[result.lastIndex] = last.copy(end = b)
            else result += HistoryInterval(a,b,state)
        }
        return result
    }

    /** Historical context only: never overwrite the present or future live schedule. */
    fun overlay(spans: List<ChartSpan>, history: List<HistoryInterval>, now: Instant): List<ChartSpan> {
        if (history.isEmpty() || spans.isEmpty()) return spans
        val start = spans.first().start.toEpochMilli(); val end = spans.last().end.toEpochMilli()
        val cut = now.toEpochMilli()
        val past = history.map { it.copy(end = minOf(it.end, cut)) }.filter { it.end > it.start }
        // Fresh source fills only portions for which no durable historical evidence exists.
        val bounds = (listOf(start,end,cut.coerceIn(start,end)) + spans.flatMap { listOf(it.start.toEpochMilli(),it.end.toEpochMilli()) } +
            past.flatMap { listOf(it.start.coerceIn(start,end),it.end.coerceIn(start,end)) }).distinct().sorted()
        return bounds.zipWithNext().map { (a,b) ->
            val archived = past.firstOrNull { a >= it.start && a < it.end }
            val live = spans.firstOrNull { a >= it.start.toEpochMilli() && a < it.end.toEpochMilli() }
            ChartSpan(Instant.ofEpochMilli(a),Instant.ofEpochMilli(b),archived?.state ?: live?.state ?: PowerState.UNKNOWN)
        }
    }
}
