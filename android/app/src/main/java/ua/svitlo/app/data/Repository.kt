package ua.svitlo.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.svitlo.app.domain.*
import java.time.Instant

class Repository(val store: StateStore, val api: VoeClient,
                 private val clock: () -> Instant = Instant::now,
                 private val batchFactory: () -> ScheduleBatch = { api.Batch() },
                 private val recordHistory: (SavedAddress, Schedule?, Schedule) -> List<HistoryInterval>? = { _,_,_ -> null }) {
    private val refreshMutex = Mutex()
    suspend fun refresh(manual: Boolean, foreground: Boolean = false, lowPower: Boolean = false,
                        reconnected: Boolean = false, onUpdated: suspend () -> Unit = {}): Boolean = refreshMutex.withLock {
        val initial = store.load()
        if (!manual && !foreground && !initial.active(clock())) return@withLock true
        val mode = when { manual -> RefreshMode.MANUAL; foreground -> RefreshMode.FOREGROUND
            reconnected -> RefreshMode.RECONNECTED; else -> RefreshMode.BACKGROUND }
        val ids = (if (manual || foreground) initial.addresses.filter { it.selected || it.id in initial.widgetAddressIds() }
            else initial.monitoredAddresses(clock()))
            .sortedWith(compareByDescending<SavedAddress> { it.id == initial.currentAddressId }
                .thenByDescending { RefreshPolicy.critical(initial,it.id,clock()) }
                .thenBy { initial.attempt(it.id)?.at ?: 0L }).map { it.id }
        val batch = batchFactory()
        var allSucceeded = true
        for (id in ids) {
            val latest = store.state.value
            val eligible = if (manual || foreground) latest.addresses.filter { it.selected || it.id in latest.widgetAddressIds() }
                else latest.monitoredAddresses(clock())
            val address = eligible.firstOrNull { it.id == id } ?: continue
            if (!RefreshPolicy.due(latest,id,clock(),mode,lowPower)) continue
            val requestsBefore = api.requestCount.get()
            val bytesBefore = api.receivedBytes.get()
            val started = System.nanoTime()
            try {
                val schedule = batch.schedule(address)
                val history = recordHistory(address,store.state.value.schedule(id),schedule)
                store.update { state ->
                    if (state.addresses.none { it.id == id }) state else {
                        val old = state.schedules.firstOrNull { it.addressId == id }
                        val changed = old == null || old.schedule.copy(fetchedAt = 0) != schedule.copy(fetchedAt = 0)
                        val at = Instant.ofEpochMilli(schedule.fetchedAt)
                        val clear = !schedule.stale(at) && schedule.currentOutage == null && schedule.powerAt(at,at) == PowerState.NO_SCHEDULED_OUTAGE
                        RestorationPolicy.observe(state.copy(
                            historyTails = if (history == null) state.historyTails else state.historyTails.orEmpty().filterNot { it.addressId == id } + HistoryTail(id,history),
                            schedules = state.schedules.filterNot { it.addressId == id } + CachedSchedule(id,schedule,
                                if (changed) schedule.fetchedAt else old?.changedAt ?: old?.schedule?.fetchedAt),
                            attempts = state.attempts.filterNot { it.addressId == id } + FetchAttempt(id,schedule.fetchedAt,0,null),
                            observations = if (clear) state.observations?.filterNot { it.addressId == id } else state.observations,
                            syncStats = stats(state.syncStats,requestsBefore,bytesBefore,started,true),
                            restorationNotifiedKey = RestorationPolicy.notificationKey(state,id,schedule) ?: state.restorationNotifiedKey), at)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                allSucceeded = false
                val kind = when (e) {
                    is VoeFormatException -> FetchFailure.FORMAT
                    is VoeHttpException -> if (e.status == 429) FetchFailure.RATE_LIMIT else FetchFailure.SERVER
                    is java.io.IOException -> FetchFailure.NETWORK
                    else -> FetchFailure.OTHER
                }
                store.update { state -> if (state.addresses.none { it.id == id }) state else state.copy(
                    attempts = state.attempts.filterNot { it.addressId == id } + FetchAttempt(id,clock().toEpochMilli(),
                        (state.attempt(id)?.failures ?: 0) + 1,e.message ?: "Не вдалося оновити VOE",kind,(e as? VoeHttpException)?.retryAt),
                    syncStats = stats(state.syncStats,requestsBefore,bytesBefore,started,false)) }
                if (e is VoeHttpException || e is VoeFormatException) break
            }
            onUpdated() // Publish each completed address without waiting for the last address's timeout.
        }
        onUpdated()
        allSucceeded
    }

    private fun stats(previous: SyncStats?, requests: Long, bytes: Long, started: Long, success: Boolean): SyncStats {
        val old = previous ?: SyncStats()
        return old.copy(requests = old.requests + api.requestCount.get()-requests,
            receivedBytes = old.receivedBytes + api.receivedBytes.get()-bytes,
            succeeded = old.succeeded + if (success) 1 else 0,
            failed = old.failed + if (success) 0 else 1,
            durationMs = old.durationMs + (System.nanoTime()-started)/1_000_000)
    }
}
