package ua.svitlo.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ua.svitlo.app.app
import ua.svitlo.app.domain.*
import ua.svitlo.app.background.lowPower

data class Lookup(val level: String = "city", val parent: String? = null, val query: String = "")
data class LookupState(val request: Lookup = Lookup(), val loading: Boolean = false,
                       val items: List<AddressItem> = emptyList(), val error: String? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.app
    val state = services.store.state
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val lookup = MutableStateFlow(Lookup())
    val suggestions = lookup.debounce(450).mapLatest { request ->
        if (request.query.trim().length < if (request.level == "house") 1 else 2) LookupState(request)
        else {
            try { LookupState(request, items = services.repository.api.suggestions(request.level, request.query.trim(), request.parent)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { LookupState(request, error = "Не вдалося завантажити адреси. Перевірте з’єднання.") }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LookupState())

    init { action { services.store.load(); reconcile() } }

    fun search(level: String, query: String, parent: String? = null) { lookup.value = Lookup(level, parent, query) }
    fun clearSearch() { lookup.value = Lookup() }

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

    fun add(title: String, city: AddressItem, street: AddressItem, house: AddressItem) = action {
        val id = "${city.id}:${street.id}:${house.id}"
        services.store.update {
            require(it.addresses.none { a -> a.id == id }) { "Ця адреса вже збережена" }
            it.copy(addresses = it.addresses + SavedAddress(id, title.trim().ifEmpty { "${street.name}, ${house.name}" }, city, street, house))
        }
        reconcile(refreshSoon = true)
        refreshInternal()
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
            val success = services.repository.refresh(manual = true,onUpdated = { services.publish() })
            if (!success) message.value = "Частину графіків не оновлено. Попередні дані збережено."
            reconcile()
        } finally { busy.value = false }
    }
}
