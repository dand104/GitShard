package io.gitshard.gitshard.data.prefs

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.git.GitAuthor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@Serializable
data class VaultConfig(
    val id: String,
    val name: String,
    val rootPath: String,
    val remoteUrl: String? = null,
    val defaultBranch: String = "main",
    val authorName: String = GitAuthor.DEFAULT.name,
    val authorEmail: String = GitAuthor.DEFAULT.email,
    val createdAtEpochMillis: Long = 0L,
) {
    val rootDir: File
        get() = File(rootPath)

    val author: GitAuthor
        get() = GitAuthor(name = authorName, email = authorEmail)
}

@Serializable
data class VaultRegistryState(
    val vaults: List<VaultConfig> = emptyList(),
    val activeVaultId: String? = null,
) {
    val activeVault: VaultConfig?
        get() = vaults.firstOrNull { it.id == activeVaultId } ?: vaults.firstOrNull()
}

internal object VaultRegistrySerializer : Serializer<VaultRegistryState> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: VaultRegistryState = VaultRegistryState()

    override suspend fun readFrom(input: InputStream): VaultRegistryState {
        val raw = input.readBytes().decodeToString()
        if (raw.isBlank()) return defaultValue
        return try {
            json.decodeFromString(VaultRegistryState.serializer(), raw)
        } catch (e: SerializationException) {
            throw CorruptionException("Unable to read vault registry", e)
        }
    }

    override suspend fun writeTo(t: VaultRegistryState, output: OutputStream) {
        val encoded = json.encodeToString(VaultRegistryState.serializer(), t)
        output.write(encoded.toByteArray(Charsets.UTF_8))
    }
}

class VaultRegistryStore(
    private val dataStore: DataStore<VaultRegistryState>,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    val state: Flow<VaultRegistryState> = dataStore.data

    val activeVault: Flow<VaultConfig?> = state.map { it.activeVault }

    suspend fun getAllVaults(): List<VaultConfig> = state.first().vaults

    suspend fun getVault(vaultId: String): VaultConfig? =
        state.first().vaults.firstOrNull { it.id == vaultId }

    suspend fun getActiveVault(): VaultConfig? = state.first().activeVault

    suspend fun upsert(vault: VaultConfig, setActive: Boolean = false) {
        val canonicalRootPath = withContext(dispatchers.io) { File(vault.rootPath).canonicalPath }
        val normalized = vault.copy(
            name = vault.name.trim().ifEmpty { File(canonicalRootPath).name.ifEmpty { "Vault" } },
            rootPath = canonicalRootPath,
            remoteUrl = vault.remoteUrl?.trim()?.takeIf { it.isNotEmpty() },
            defaultBranch = vault.defaultBranch.trim().ifEmpty { "main" },
        )
        dataStore.updateData { current ->
            val idx = current.vaults.indexOfFirst { it.id == normalized.id }
            val updatedList = if (idx >= 0) {
                current.vaults.toMutableList().apply { this[idx] = normalized }
            } else {
                current.vaults + normalized
            }
            val nextActiveId = when {
                setActive -> normalized.id
                current.activeVaultId != null && updatedList.any { it.id == current.activeVaultId } -> current.activeVaultId
                else -> normalized.id
            }
            current.copy(vaults = updatedList, activeVaultId = nextActiveId)
        }
    }

    suspend fun setActiveVault(vaultId: String) {
        dataStore.updateData { current ->
            if (current.vaults.none { it.id == vaultId }) {
                current
            } else {
                current.copy(activeVaultId = vaultId)
            }
        }
    }

    suspend fun remove(vaultId: String) {
        dataStore.updateData { current ->
            val remaining = current.vaults.filterNot { it.id == vaultId }
            val nextActive = when {
                current.activeVaultId != vaultId -> current.activeVaultId
                else -> remaining.firstOrNull()?.id
            }
            current.copy(vaults = remaining, activeVaultId = nextActive)
        }
    }

    fun generateVaultId(): String = UUID.randomUUID().toString()

    companion object {
        const val FILE_NAME = "vault_registry.json"

        fun create(
            context: Context,
            scope: CoroutineScope,
            dispatchers: AppDispatchers = AppDispatchers(),
        ): VaultRegistryStore {
            val store = DataStoreFactory.create(
                serializer = VaultRegistrySerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { VaultRegistryState() },
                scope = scope,
                produceFile = { context.applicationContext.dataStoreFile(FILE_NAME) },
            )
            return VaultRegistryStore(store, dispatchers)
        }
    }
}
