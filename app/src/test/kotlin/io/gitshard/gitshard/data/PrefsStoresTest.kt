package io.gitshard.gitshard.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.data.prefs.AppPreferences
import io.gitshard.gitshard.data.prefs.CredentialsReadResult
import io.gitshard.gitshard.data.prefs.CredentialsStore
import io.gitshard.gitshard.data.prefs.ThemeMode
import io.gitshard.gitshard.data.prefs.VaultConfig
import io.gitshard.gitshard.data.prefs.VaultRegistrySerializer
import io.gitshard.gitshard.data.prefs.VaultRegistryState
import io.gitshard.gitshard.data.prefs.VaultRegistryStore
import io.gitshard.gitshard.git.GitCredentials
import io.gitshard.gitshard.testutil.InMemoryDataStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

class PrefsStoresTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `app preferences persists values and clamps bounds`() = runTest {
        val prefs = AppPreferences(InMemoryDataStore<Preferences>(emptyPreferences()))

        prefs.setThemeMode(ThemeMode.DARK)
        prefs.setEditorFontSizeSp(40)
        prefs.setSyncIntervalMinutes(3)
        prefs.setDynamicColor(false)

        val current = prefs.current()
        assertEquals(ThemeMode.DARK, current.themeMode)
        assertEquals(28, current.editorFontSizeSp)
        assertEquals(15, current.syncIntervalMinutes)
        assertEquals(false, current.dynamicColor)
    }

    @Test
    fun `vault registry tracks active vault and falls back on removal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry = VaultRegistryStore(
            dataStore = InMemoryDataStore(VaultRegistryState()),
            dispatchers = AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
        )
        val dir1 = tempFolder.newFolder("vault-1")
        val dir2 = tempFolder.newFolder("vault-2")

        registry.upsert(VaultConfig(id = "v1", name = "Work", rootPath = dir1.path))
        registry.upsert(VaultConfig(id = "v2", name = "Personal", rootPath = dir2.path), setActive = true)

        assertEquals("v2", registry.getActiveVault()?.id)

        registry.remove("v2")
        assertEquals("v1", registry.getActiveVault()?.id)

        registry.remove("v1")
        assertNull(registry.getActiveVault())
    }

    @Test
    fun `vault registry serializer round-trips state and rejects malformed json`() = runTest {
        val state = VaultRegistryState(
            vaults = listOf(
                VaultConfig(
                    id = "v1",
                    name = "Notes",
                    rootPath = "/vaults/notes",
                    remoteUrl = "https://example.com/notes.git",
                    defaultBranch = "main",
                ),
            ),
            activeVaultId = "v1",
        )

        val output = ByteArrayOutputStream()
        VaultRegistrySerializer.writeTo(state, output)
        val restored = VaultRegistrySerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)

        val empty = VaultRegistrySerializer.readFrom(ByteArrayInputStream(ByteArray(0)))
        assertEquals(VaultRegistryState(), empty)

        val corrupted = runCatching {
            VaultRegistrySerializer.readFrom(ByteArrayInputStream("{broken".toByteArray()))
        }.exceptionOrNull()
        assertTrue(corrupted is CorruptionException)
    }

    @Test
    fun `credentials store binds aad to vaultId and handles decryption failures gracefully`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var failDecrypt = false
        val store = CredentialsStore(
            dataStore = InMemoryDataStore(emptyPreferences()),
            encrypt = { plaintext, aad -> "$aad::$plaintext" },
            decrypt = { encoded, aad ->
                if (failDecrypt) throw GeneralSecurityException("Keystore key invalidated")
                val prefix = "$aad::"
                if (!encoded.startsWith(prefix)) throw GeneralSecurityException("AAD mismatch")
                encoded.removePrefix(prefix)
            },
            dispatchers = AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
        )

        val token = GitCredentials.HttpsToken(token = "ghp_secret", username = "dand104")
        store.save("vault-1", token)
        assertEquals(token, store.get("vault-1"))
        assertEquals(GitCredentials.None, store.get("vault-2"))

        failDecrypt = true
        val failed = store.read("vault-1")
        assertTrue(failed is CredentialsReadResult.DecryptionFailed)
        assertEquals(GitCredentials.None, store.get("vault-1"))

        failDecrypt = false
        store.save("vault-1", GitCredentials.None)
        assertEquals(CredentialsReadResult.Empty, store.read("vault-1"))
    }
}
