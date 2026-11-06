package ua.svitlo.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ua.svitlo.app.app
import ua.svitlo.app.domain.*
import ua.svitlo.app.background.lowPower

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.app
    val state = services.store.state
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    init { action { services.store.load(); reconcile() } }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Не вдалося виконати дію" }
        }
    }

    private suspend fun reconcile(refreshSoon: Boolean = false) {
        services.reconcile(refreshSoon)
    }

    suspend fun feedGroups(id:String)=services.repository.api.feedGroups(id)
    suspend fun emergencyRegions()=services.repository.api.emergency.regions()
    suspend fun saveFeed(providerId:String,group:String,title:String,existingId:String?,incident:VoeIncidentAddress?)=withContext(Dispatchers.IO) {
        val provider=ua.svitlo.app.data.FeedProviders.get(providerId)
        require(group in feedGroups(providerId)){"Оберіть опубліковану чергу"}
        if(incident!=null) {
            ua.svitlo.app.data.VoeEmergencyParser.validate(incident)
            require(providerId=="voe/Vinnytsiaoblenerho"){"Адресні аварії цього оператора ще не підключено"}
            require(incident.city.isNotBlank() && incident.street.isNotBlank() && incident.house.isNotBlank()) {"Вкажіть повну адресу для перевірки аварій"}
            require(emergencyRegions().any{it.id==incident.regionId}){"Оберіть район VOE"}
        }
        val id=existingId ?: java.util.UUID.randomUUID().toString()
        services.store.update { state ->
            val old=existingId?.let{key->state.addresses.single{it.id==key}}
            require(state.addresses.none{it.id!=existingId && it.feedId==providerId && it.feedGroup==group && it.voeIncidentAddress==incident}){"Це місце вже збережено"}
            val address=SavedAddress(id,old?.title ?: title.trim().ifEmpty{"Черга ${group.removePrefix("GPV")}"},
                AddressItem("feed",provider.label),AddressItem("queue","Черга"),AddressItem(group,group.removePrefix("GPV")),
                selected=old?.selected ?: true,feedId=providerId,feedGroup=group,voeIncidentAddress=incident)
            state.copy(addresses=state.addresses.filterNot{it.id==id}+address,schedules=state.schedules.filterNot{it.addressId==id},
                attempts=state.attempts.filterNot{it.addressId==id},observations=state.observations?.filterNot{it.addressId==id},historyTails=state.historyTails?.filterNot{it.addressId==id})
        }
        reconcile(refreshSoon=true);refreshInternal()
    }

    suspend fun prominConfig(id: String) = services.repository.api.prominConfig(id)
    suspend fun prominStreets(id: String) = services.repository.api.prominStreets(id)
    suspend fun prominHouses(id: String,street: ua.svitlo.app.data.ProminStreet) = services.repository.api.prominHouses(id,street)
    suspend fun savePromin(selection: ua.svitlo.app.data.ProminSelection,existingId: String?) = withContext(Dispatchers.IO) {
        val city=ua.svitlo.app.data.ProminCities.get(selection.city)
        val street=selection.street
        val house=selection.house
        require((street!=null && house!=null) || selection.group.isNotBlank()) { "Оберіть будинок або чергу" }
        val groupLabel=if(street==null) prominConfig(city.id).groupName(selection.group) else selection.group
        val key="promin:${city.id}:${street?.cityId}:${street?.id}:${house?.number ?: selection.group}"
        services.store.update { state ->
            val old=existingId?.let { id -> state.addresses.single { it.id==id } }
            require(state.addresses.none { it.id!=existingId && it.prominCity==city.id &&
                it.street.id==(street?.id ?: "queue") && it.house.name==(house?.number ?: selection.group) &&
                it.city.id==(street?.cityId ?: "queue") }) { "Це місце вже збережене" }
            val id=existingId ?: key
            val address=SavedAddress(id,old?.title ?: selection.title.trim().ifEmpty {
                if(street!=null && house!=null) "${street.name}, ${house.number}" else "${city.name} · $groupLabel"
            }, AddressItem(street?.cityId ?: "queue",street?.city ?: city.name),
                AddressItem(street?.id ?: "queue",street?.name ?: "Черга"),AddressItem(house?.number ?: selection.group,house?.number ?: groupLabel.removePrefix("Черга ")),
                selected=old?.selected ?: true,feedId="promin/${city.id}",feedGroup=house?.group ?: selection.group,
                prominCity=city.id,prominBuildingId=house?.buildingId,prominGroupEstimated=house?.estimated ?: false)
            state.copy(addresses=state.addresses.filterNot { it.id==id }+address,
                schedules=state.schedules.filterNot { it.addressId==id },attempts=state.attempts.filterNot { it.addressId==id },
                observations=state.observations?.filterNot { it.addressId==id },historyTails=state.historyTails?.filterNot { it.addressId==id })
        }
        reconcile(refreshSoon=true); refreshInternal()
    }

    fun select(id: String, selected: Boolean) = action {
        services.store.update {
            val addresses = it.addresses.map { a -> if (a.id == id) a.copy(selected = selected) else a }
            it.copy(addresses = addresses, session = if (addresses.any { a -> a.selected }) it.session else null,
                eventMonitoring = it.eventMonitoring && !(it.currentAddressId == id && !selected))
        }
        reconcile(refreshSoon = selected)
    }

    fun remove(id: String) = action {
        services.store.update {
            val addresses = it.addresses.filterNot { a -> a.id == id }
            it.copy(addresses = addresses, schedules = it.schedules.filterNot { c -> c.addressId == id },
                attempts = it.attempts.filterNot { c -> c.addressId == id },
                widgets = it.widgets?.map { config -> config.copy(addressIds = config.addressIds.filterNot { selected -> selected == id }) },
                currentAddressId = it.currentAddressId.takeUnless { current -> current == id },
                eventMonitoring = it.eventMonitoring && it.currentAddressId != id,
                session = if (addresses.any { a -> a.selected }) it.session else null)
        }
        reconcile()
    }

    fun rename(id: String, title: String) = action {
        val name = title.trim().take(60)
        require(name.isNotEmpty()) { "Введіть назву адреси" }
        services.store.update { state -> state.copy(addresses = state.addresses.map {
            if (it.id == id) it.copy(title = name) else it
        }) }
        reconcile()
    }

    fun showGraphs() = action {
        services.updateControl({
            require(it.addresses.any { a -> a.selected }) { "Спершу виберіть хоча б одну адресу" }
            it.copy(notificationsEnabled = true, graphDismissed = false, session = null)
        }, refreshSoon = true)
    }

    fun hideGraphs() = action {
        services.updateControl({ it.copy(notificationsEnabled = false, graphDismissed = false, session = null) })
    }

    fun here(id: String) = action {
        services.updateControl({ state -> state.copy(currentAddressId = id, dismissedEventKey = null,
            restorationNotifiedKey = null,
            addresses = state.addresses.map { if (it.id == id) it.copy(selected = true) else it }) }, refreshSoon = true)
    }

    fun monitorEvents(enabled: Boolean) = action {
        services.updateControl({ it.copy(eventMonitoring = enabled, dismissedEventKey = null,
            restorationNotifiedKey = if (enabled) it.restorationNotifiedKey else null) }, refreshSoon = enabled)
    }

    fun refresh() = action { refreshInternal() }
    fun refreshVisible() = action {
        if (!busy.compareAndSet(false,true)) return@action
        try {
            services.repository.refresh(manual = false,foreground = true,lowPower = services.lowPower(),onUpdated = { services.publish() })
            reconcile()
        } finally { busy.value = false }
    }
    private suspend fun refreshInternal() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val snapshot = services.store.load()
            val now = java.time.Instant.now()
            val targets = snapshot.addresses
            if (targets.isNotEmpty() && targets.none { RefreshPolicy.due(snapshot,it.id,now,RefreshMode.MANUAL) }) {
                val due = targets.minOf { RefreshPolicy.nextDue(snapshot,it.id,now,RefreshMode.MANUAL) }
                val seconds = java.time.Duration.between(now,due).seconds.coerceAtLeast(0)+1
                message.value = if (seconds <= RefreshPolicy.MANUAL_COOLDOWN_SECONDS)
                    "Щойно перевірено. Повторіть через $seconds с."
                else "Джерело обмежило запити. Наступна спроба о ${due.atZone(KYIV).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}."
                return
            }
            val success = services.repository.refresh(manual = true,onUpdated = { services.publish() })
            message.value = if (success) "Графіки перевірено" else "Частину графіків не оновлено. Попередні дані збережено."
            reconcile()
        } finally { busy.value = false }
    }
}
