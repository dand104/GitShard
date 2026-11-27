package io.gitshard.gitshard.testutil

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryDataStore<T>(initialValue: T) : DataStore<T> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initialValue)

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        mutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
}
