package io.gitshard.gitshard.data

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.data.prefs.NewFileLocation
import io.gitshard.gitshard.data.prefs.NoteConfigStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteConfigStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `preserves unknown desktop keys and only patches modified fields`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NoteConfigStore(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val vaultRoot = tempFolder.newFolder("vault")
        val appJson = File(vaultRoot, ".obsidian/app.json").apply {
            parentFile?.mkdirs()
            writeText(
                """
                {
                  "vimMode": true,
                  "customPluginSetting": { "nested": 42 },
                  "newFileLocation": "root"
                }
                """.trimIndent(),
            )
        }

        store.updateAppConfig(vaultRoot) {
            it.copy(
                newFileLocation = NewFileLocation.FOLDER,
                newFileFolderPath = "Inbox",
            )
        }

        val parsed = Json.parseToJsonElement(appJson.readText()).jsonObject
        assertTrue(parsed["vimMode"]!!.jsonPrimitive.boolean)
        assertEquals("42", parsed["customPluginSetting"]!!.jsonObject["nested"]!!.jsonPrimitive.content)
        assertEquals("folder", parsed["newFileLocation"]!!.jsonPrimitive.content)
        assertEquals("Inbox", parsed["newFileFolderPath"]!!.jsonPrimitive.content)
        assertFalse("strictLineBreaks" in parsed)

        val reloaded = store.readAppConfig(vaultRoot)
        assertEquals(NewFileLocation.FOLDER, reloaded.newFileLocation)
        assertEquals("Inbox", reloaded.newFileFolderPath)
    }

    @Test
    fun `returns sensible defaults when obsidian folder is absent and writes daily notes config`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NoteConfigStore(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val vaultRoot = tempFolder.newFolder("fresh-vault")

        val initial = store.readDailyNotesConfig(vaultRoot)
        assertEquals("YYYY-MM-DD", initial.format)
        assertEquals("", initial.folder)

        store.updateDailyNotesConfig(vaultRoot) {
            it.copy(folder = "Daily", template = "Templates/Daily")
        }

        val updated = store.readDailyNotesConfig(vaultRoot)
        assertEquals("Daily", updated.folder)
        assertEquals("Templates/Daily", updated.template)
    }

    @Test(expected = Exception::class)
    fun `refuses to silently overwrite corrupted obsidian json on update`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = NoteConfigStore(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val vaultRoot = tempFolder.newFolder("broken-vault")
        File(vaultRoot, ".obsidian/app.json").apply {
            parentFile?.mkdirs()
            writeText("{ not valid json")
        }

        store.updateAppConfig(vaultRoot) { it.copy(strictLineBreaks = true) }
    }
}
