package ua.svitlo.app.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.svitlo.app.domain.*
import java.io.File

/** One small durable snapshot, atomic replacement, observable without polling disk. */
class StateStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "state-v1.json"))
    private val gson = Gson()
    private val mutex = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()

    suspend fun load(): AppState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                val saved = if (file.baseFile.exists()) file.openRead().bufferedReader().use { reader ->
                    // Do not overwrite an unreadable file with an empty address book.
                    gson.fromJson(reader, AppState::class.java) ?: error("Не вдалося прочитати локальні дані")
                } else AppState()
                // Preserve an active old session as an unlimited opt-in; expired sessions stay off.
                val migrated = if (saved.notificationsEnabled == null) saved.copy(
                    notificationsEnabled = saved.graphsEnabled(java.time.Instant.now()), session = null) else saved
                if (migrated != saved) write(migrated)
                mutable.value = migrated
                loaded = true
            }
            mutable.value
        }
    }

    suspend fun update(transform: (AppState) -> AppState): AppState {
        load()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(mutable.value)
                if (next != mutable.value) {
                    write(next)
                    mutable.value = next
                }
                next
            }
        }
    }
    private fun write(state: AppState) {
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
