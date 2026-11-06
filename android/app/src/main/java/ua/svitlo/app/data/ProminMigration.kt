package ua.svitlo.app.data
import ua.svitlo.app.domain.AppState

/** Preserve saved places/widget IDs, but never present another source's cache as Promin data. */
object ProminMigration {
    fun apply(state: AppState): AppState {
        if (state.dataSourceVersion==2) return state
        val linked=state.addresses.filter { a -> FeedProviders.all.any{it.id==a.feedId} }.map { it.id }.toSet()
        return state.copy(dataSourceVersion=2,schedules=state.schedules.filter { it.addressId in linked },
            attempts=state.attempts.filter { it.addressId in linked },observations=state.observations?.filter { it.addressId in linked },
            historyTails=state.historyTails?.filter { it.addressId in linked },restorationNotifiedKey=null,dismissedEventKey=null)
    }
}
