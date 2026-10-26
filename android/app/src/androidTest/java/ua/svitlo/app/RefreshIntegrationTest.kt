package ua.svitlo.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.io.File
import java.time.Instant

/** Isolated durable stores + a scripted source; no live VOE/network requests. */
@RunWith(AndroidJUnit4::class)
class RefreshIntegrationTest {
    private val at = Instant.parse("2026-10-19T10:00:00Z")
    private val address = SavedAddress("a","Дім",AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","1"))
    private val diskContexts = mutableMapOf<StateStore,Context>()
    private fun schedule(now: Instant, end: Int = 781, outage: Boolean = true) = Schedule(now.toEpochMilli(),
        listOf(ScheduleDay("Сьогодні","2026-10-19",if (outage) listOf(Slot(0,700,PowerState.NO_SCHEDULED_OUTAGE),
            Slot(700,end,PowerState.CONFIRMED_OUTAGE),Slot(end,1440,PowerState.NO_SCHEDULED_OUTAGE)) else listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
    private suspend fun isolated(): StateStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir,"refresh-${System.nanoTime()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) { override fun getFilesDir() = folder }
        return StateStore(scoped).also { diskContexts[it] = scoped; it.load() }
    }
    private fun state(source: Schedule) = AppState(addresses = listOf(address),notificationsEnabled = true,eventMonitoring = true,
        currentAddressId = address.id,schedules = listOf(CachedSchedule(address.id,source)),
        attempts = listOf(FetchAttempt(address.id,source.fetchedAt,0,null)))

    @Test fun endDeadlineFetchesImmediatelyPublishesAndDeduplicatesConcurrentTriggers(): Unit = runBlocking {
        val store = isolated()
        val beforeEnd = at.plusSeconds(59)
        store.update { RestorationPolicy.observe(state(schedule(beforeEnd)),beforeEnd) }
        val end = at.plusSeconds(60)
        var calls = 0
        var publications = 0
        val repo = Repository(store,VoeClient(),{ end },{ ScheduleBatch {
            calls++
            kotlinx.coroutines.delay(50)
            schedule(end,outage = false)
        } })
        coroutineScope { listOf(async { repo.refresh(false,onUpdated = { publications++ }) },
            async { repo.refresh(false,onUpdated = { publications++ }) }).forEach { assertTrue(it.await()) } }
        assertEquals(1,calls)
        assertTrue(publications > 0)
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE,store.state.value.schedule(address.id)!!.powerAt(end,end))
        assertNotNull(store.state.value.restorationNotifiedKey)
        assertTrue(store.state.value.observations.orEmpty().isEmpty())
        assertEquals(1,store.state.value.syncStats!!.succeeded)
    }

    @Test fun extensionNeverRestoresAndTheRevisedEndTriggersAnotherFetch(): Unit = runBlocking {
        val store = isolated()
        var clock = at.plusSeconds(60)
        var calls = 0
        store.update { RestorationPolicy.observe(state(schedule(at)),at) }
        val repo = Repository(store,VoeClient(),{clock},{ ScheduleBatch {
            calls++
            if (calls == 1) schedule(clock,end = 782) else schedule(clock,outage = false)
        } })
        assertTrue(repo.refresh(false))
        assertEquals(1,calls)
        assertNull(store.state.value.restorationNotifiedKey)
        assertFalse(RefreshPolicy.due(store.state.value,address.id,clock))
        clock = at.plusSeconds(120)
        assertTrue(repo.refresh(false))
        assertEquals(2,calls)
        assertNotNull(store.state.value.restorationNotifiedKey)
    }

    @Test fun unknownResponseKeepsObservationAcrossRestartUntilClearResponse(): Unit = runBlocking {
        val store = isolated()
        store.update { RestorationPolicy.observe(state(schedule(at)),at) }
        var clock = at.plusSeconds(60)
        var unknown = true
        val repo = Repository(store,VoeClient(),{clock},{ ScheduleBatch {
            if (unknown) Schedule(clock.toEpochMilli(),emptyList(),emptyList()) else schedule(clock,outage = false)
        } })
        repo.refresh(false)
        assertNull(store.state.value.restorationNotifiedKey)
        assertTrue(store.state.value.observations.orEmpty().isNotEmpty())
        unknown = false
        clock = at.plusSeconds(16*60)
        val reloaded = StateStore(diskContexts.getValue(store))
        Repository(reloaded,VoeClient(),{clock},{ ScheduleBatch { schedule(clock,outage=false) } }).refresh(false)
        assertNotNull(reloaded.state.value.restorationNotifiedKey)
    }

    @Test fun sharedRateLimitPausesBatchAndSurvivesStoreReload(): Unit = runBlocking {
        val store = isolated()
        store.update { state(schedule(at)).copy(addresses = listOf(address,address.copy(id="b")),attempts = emptyList(),schedules = emptyList()) }
        var calls = 0
        val retry = at.plusSeconds(900).toEpochMilli()
        val repo = Repository(store,VoeClient(),{at},{ ScheduleBatch { calls++; throw VoeHttpException(429,retry) } })
        assertFalse(repo.refresh(false))
        assertEquals(1,calls)
        assertEquals(retry,store.state.value.attempts.single().retryAfter)
        assertEquals(store.state.value.attempts,StateStore(diskContexts.getValue(store)).load().attempts)
        assertTrue(repo.refresh(manual = true)) // Skipped, not a second network request.
        assertEquals(1,calls)
        assertEquals(1,store.state.value.syncStats!!.failed)
    }

    @Test fun successfulAddressIsPublishedBeforeLaterAddressCompletesAndUnchangedSourceKeepsChangedAt(): Unit = runBlocking {
        val store = isolated()
        val previous = schedule(at,outage = false)
        store.update { state(previous).copy(addresses=listOf(address,address.copy(id="b")),
            schedules=listOf(CachedSchedule(address.id,previous,at.minusSeconds(100).toEpochMilli())),
            attempts=emptyList(),eventMonitoring=false) }
        val clock = at.plusSeconds(1801)
        var firstPublished = false
        val repo = Repository(store,VoeClient(),{clock},{ ScheduleBatch { next ->
            if (next.id == "b") assertTrue(firstPublished)
            schedule(clock,outage=false)
        } })
        repo.refresh(false,onUpdated = { if (store.state.value.schedule(address.id)?.fetchedAt == clock.toEpochMilli()) firstPublished = true })
        assertTrue(firstPublished)
        assertEquals(at.minusSeconds(100).toEpochMilli(),store.state.value.schedules.first { it.addressId == address.id }.changedAt)
    }
}
