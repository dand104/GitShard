package io.gitshard.gitshard.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.KeystoreCipher
import io.gitshard.gitshard.git.GitCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface CredentialsReadResult {
    data object Empty : CredentialsReadResult
    data class Available(val credentials: GitCredentials) : CredentialsReadResult
    data class DecryptionFailed(val cause: Throwable) : CredentialsReadResult
}

@Serializable
private sealed interface StoredCredentials {
    @Serializable
    @SerialName("https_token")
    data class HttpsToken(
        val token: String,
        val username: String,
    ) : StoredCredentials

    @Serializable
    @SerialName("https_password")
    data class HttpsPassword(
        val username: String,
        val password: String,
    ) : StoredCredentials

    @Serializable
    @SerialName("ssh_key")
    data class SshKey(
        val privateKeyPem: String,
        val passphrase: String? = null,
    ) : StoredCredentials
}

class CredentialsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val encrypt: (plaintext: String, aad: String) -> String,
    private val decrypt: (encoded: String, aad: String) -> String,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    constructor(
        dataStore: DataStore<Preferences>,
        cipher: KeystoreCipher = KeystoreCipher(),
        dispatchers: AppDispatchers = AppDispatchers(),
    ) : this(
        dataStore = dataStore,
        encrypt = { plaintext, aad -> cipher.encryptString(plaintext, aad = aad) },
        decrypt = { encoded, aad -> cipher.decryptString(encoded, aad = aad) },
        dispatchers = dispatchers,
    )

    suspend fun save(vaultId: String, credentials: GitCredentials) {
        require(vaultId.isNotBlank()) { "vaultId must not be blank" }
        val key = keyFor(vaultId)
        if (credentials is GitCredentials.None) {
            dataStore.edit { it.remove(key) }
            return
        }
        val encoded = withContext(dispatchers.io) {
            val serialized = json.encodeToString(StoredCredentials.serializer(), credentials.toStored())
            encrypt(serialized, vaultId)
        }
        dataStore.edit { it[key] = encoded }
    }

    suspend fun read(vaultId: String): CredentialsReadResult {
        val encoded = dataStore.data.first()[keyFor(vaultId)] ?: return CredentialsReadResult.Empty
        return withContext(dispatchers.io) {
            runCatching {
                val raw = decrypt(encoded, vaultId)
                json.decodeFromString(StoredCredentials.serializer(), raw).toDomain()
            }.fold(
                onSuccess = { CredentialsReadResult.Available(it) },
                onFailure = { CredentialsReadResult.DecryptionFailed(it) },
            )
        }
    }

    suspend fun get(vaultId: String): GitCredentials = when (val result = read(vaultId)) {
        is CredentialsReadResult.Available -> result.credentials
        CredentialsReadResult.Empty,
        is CredentialsReadResult.DecryptionFailed,
            -> GitCredentials.None
    }

    suspend fun clear(vaultId: String) {
        dataStore.edit { it.remove(keyFor(vaultId)) }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun keyFor(vaultId: String): Preferences.Key<String> =
        stringPreferencesKey("$KEY_PREFIX$vaultId")

    private fun GitCredentials.toStored(): StoredCredentials = when (this) {
        is GitCredentials.HttpsToken -> StoredCredentials.HttpsToken(token = token, username = username)
        is GitCredentials.HttpsPassword -> StoredCredentials.HttpsPassword(username = username, password = password)
        is GitCredentials.SshKey -> StoredCredentials.SshKey(privateKeyPem = privateKeyPem, passphrase = passphrase)
        GitCredentials.None -> error("GitCredentials.None cannot be serialized")
    }

    private fun StoredCredentials.toDomain(): GitCredentials = when (this) {
        is StoredCredentials.HttpsToken -> GitCredentials.HttpsToken(token = token, username = username)
        is StoredCredentials.HttpsPassword -> GitCredentials.HttpsPassword(username = username, password = password)
        is StoredCredentials.SshKey -> GitCredentials.SshKey(privateKeyPem = privateKeyPem, passphrase = passphrase)
    }

    companion object {
        const val STORE_NAME = "vault_credentials"
        private const val KEY_PREFIX = "cred_"

        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        fun create(
            context: Context,
            scope: CoroutineScope,
            cipher: KeystoreCipher = KeystoreCipher(),
            dispatchers: AppDispatchers = AppDispatchers(),
        ): CredentialsStore {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(STORE_NAME) },
            )
            return CredentialsStore(store, cipher, dispatchers)
        }
    }
}
