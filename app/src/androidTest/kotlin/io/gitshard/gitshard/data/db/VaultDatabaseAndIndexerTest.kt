package io.gitshard.gitshard.data.db

import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.data.NoteService
import io.gitshard.gitshard.data.VaultIndexer
import io.gitshard.gitshard.data.VaultStorage
import io.gitshard.gitshard.data.prefs.NoteConfigStore
import io.gitshard.gitshard.data.prefs.VaultConfig
import io.gitshard.gitshard.data.prefs.VaultRegistrySerializer
import io.gitshard.gitshard.data.prefs.VaultRegistryStore
import io.gitshard.gitshard.markdown.TaskMutationResult
import io.gitshard.gitshard.markdown.TemplateExpander
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class VaultDatabaseAndIndexerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: VaultDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var storage: VaultStorage
    private lateinit var indexer: VaultIndexer
    private lateinit var config: NoteConfigStore
    private lateinit var registry: VaultRegistryStore
    private lateinit var vaultDir: File

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storage = VaultStorage()
        indexer = VaultIndexer(db, storage)
        config = NoteConfigStore()
        val registryStore = DataStoreFactory.create(
            serializer = VaultRegistrySerializer,
            scope = scope,
            produceFile = { File(tempFolder.root, "registry.json") },
        )
        registry = VaultRegistryStore(registryStore)
        vaultDir = tempFolder.newFolder("vault")
        registry.upsert(VaultConfig(id = "v1", name = "Test", rootPath = vaultDir.path), setActive = true)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun createNoteService(
        clock: Clock = Clock.fixed(Instant.parse("2026-11-25T10:30:00Z"), ZoneOffset.UTC),
        onVaultMutated: (String) -> Unit = {},
    ): NoteService = NoteService(
        vaultRegistry = registry,
        storage = storage,
        indexer = indexer,
        config = config,
        templateExpander = TemplateExpander(clock = clock, locale = Locale.US),
        clock = clock,
        onVaultMutated = onVaultMutated,
    )

    @Test
    fun syncsDiskIndexesFtsResolvesBacklinksAndTogglesTasks() = runBlocking {
        storage.writeText(
            vaultDir,
            VaultPath.of("Inbox/Plan.md"),
            """
            ---
            status: active
            tags: [project/android]
            ---
            # Café Launch Plan
            Linked to [[Architecture]] and ![[diagram.png]].
            - [ ] Build indexer #project/db 📅 2026-11-26
            """.trimIndent() + "\n",
        )

        val firstSync = indexer.syncWithDisk("v1", vaultDir)
        assertEquals(1, firstSync.indexedCount)
        assertEquals(2, db.graphDao().observeBrokenLinks("v1").first().size)

        val secondSync = indexer.syncWithDisk("v1", vaultDir)
        assertEquals(0, secondSync.indexedCount)
        assertEquals(1, secondSync.unchangedCount)
        assertEquals(0, secondSync.relinkedCount)

        // Adding/removing an attachment reconciles links even when no .md files change.
        val diagramFile = File(vaultDir, "Assets/diagram.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50))
        }
        val afterAttachmentAdded = indexer.syncWithDisk("v1", vaultDir)
        assertEquals(0, afterAttachmentAdded.indexedCount)
        assertEquals(1, afterAttachmentAdded.relinkedCount)
        assertEquals(1, db.graphDao().observeBrokenLinks("v1").first().size)

        assertTrue(diagramFile.delete())
        val afterAttachmentDeleted = indexer.syncWithDisk("v1", vaultDir)
        assertEquals(1, afterAttachmentDeleted.relinkedCount)
        assertEquals(2, db.graphDao().observeBrokenLinks("v1").first().size)

        diagramFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50))
        indexer.reindexPaths("v1", vaultDir, listOf(VaultPath.of("Assets/diagram.png")))
        assertEquals(1, db.graphDao().observeBrokenLinks("v1").first().size)

        val ftsQuery = requireNotNull(FtsQueryBuilder.buildPrefixQuery("caf"))
        val found = db.noteDao().searchFts(
            vaultId = "v1",
            ftsQuery = ftsQuery,
            tag = "project",
            propertyKey = "status",
            propertyValue = "active",
        ).first()
        assertEquals(listOf("Inbox/Plan.md"), found.map { it.path })

        val mutated = ArrayList<String>()
        val noteService = createNoteService(onVaultMutated = mutated::add)

        noteService.saveNote("v1", VaultPath.of("Architecture.md"), "# Architecture\n")
        assertTrue(db.graphDao().observeBrokenLinks("v1").first().isEmpty())
        val backlinks = db.graphDao().observeBacklinks("v1", "Architecture.md").first()
        assertEquals(listOf("Inbox/Plan.md"), backlinks.map { it.sourcePath })

        val openTasks = db.taskDao().observeTasks("v1", includeCompleted = false, tag = "project").first()
        assertEquals(1, openTasks.size)
        val toggle = noteService.toggleTask("v1", VaultPath.of("Inbox/Plan.md"), openTasks.single().lineNumber)
        assertTrue(toggle is TaskMutationResult.Updated && toggle.isCompleted)
        assertEquals(0, db.taskDao().observeOpenTaskCount("v1").first())

        config.updateDailyNotesConfig(vaultDir) {
            it.copy(folder = "Daily", format = "YYYY-MM-DD", template = "Templates/Daily")
        }
        storage.writeText(vaultDir, VaultPath.of("Templates/Daily.md"), "# {{title}}\nCreated {{time}}\n")

        val daily = noteService.openOrCreateDailyNote("v1", LocalDate.of(2026, 11, 25))
        assertTrue(daily.created)
        assertEquals(VaultPath.of("Daily/2026-11-25.md"), daily.path)
        assertEquals("# 2026-11-25\nCreated 10:30\n", noteService.readNote("v1", daily.path))
        assertFalse(noteService.openOrCreateDailyNote("v1", LocalDate.of(2026, 11, 25)).created)
        assertTrue(mutated.isNotEmpty())
    }

    @Test
    fun isolatesMultipleVaultsAcrossFtsBacklinksTasksAndClear() = runBlocking {
        val vaultDir2 = tempFolder.newFolder("vault-2")
        registry.upsert(VaultConfig(id = "v2", name = "Second", rootPath = vaultDir2.path))
        val noteService = createNoteService()

        noteService.saveNote("v1", VaultPath.of("Target.md"), "# Target One\n")
        noteService.saveNote("v1", VaultPath.of("Home.md"), "# Alpha Vault\nSee [[Target]]\n- [ ] Task V1 #work\n")

        noteService.saveNote("v2", VaultPath.of("Target.md"), "# Target Two\n")
        noteService.saveNote("v2", VaultPath.of("Home.md"), "# Beta Vault\nSee [[Target]]\n- [ ] Task V2 #work\n")

        val v1Search = db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("beta"))).first()
        assertTrue(v1Search.isEmpty())
        val v2Search = db.noteDao().searchFts("v2", requireNotNull(FtsQueryBuilder.buildPrefixQuery("beta"))).first()
        assertEquals(listOf("Home.md"), v2Search.map { it.path })

        assertEquals(listOf("Task V1 #work"), db.taskDao().observeTasks("v1").first().map { it.text })
        assertEquals(listOf("Task V2 #work"), db.taskDao().observeTasks("v2").first().map { it.text })
        assertEquals(1, db.graphDao().observeBacklinks("v1", "Target.md").first().size)
        assertEquals(1, db.graphDao().observeBacklinks("v2", "Target.md").first().size)

        indexer.clearVaultIndex("v1")

        assertTrue(db.noteDao().observeAllNotes("v1").first().isEmpty())
        assertTrue(db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("alpha"))).first().isEmpty())

        assertEquals(setOf("Home.md", "Target.md"), db.noteDao().observeAllNotes("v2").first().map { it.path }.toSet())
        val v2StillIndexed = db.noteDao().searchFts("v2", requireNotNull(FtsQueryBuilder.buildPrefixQuery("beta"))).first()
        assertEquals(listOf("Home.md"), v2StillIndexed.map { it.path })
        assertEquals(1, db.graphDao().observeBacklinks("v2", "Target.md").first().size)
        assertEquals(1, db.taskDao().observeOpenTaskCount("v2").first())
    }

    @Test
    fun updatesAndCleansFtsAndBacklinksAcrossEditRenameAndDelete() = runBlocking {
        val noteService = createNoteService()
        val target = VaultPath.of("Spec.md")
        val renamed = VaultPath.of("Archive/SpecV2.md")
        val referrer = VaultPath.of("Index.md")

        noteService.saveNote("v1", target, "# Original\nObsoleteKeyword here\n")
        noteService.saveNote("v1", referrer, "Link to [[Spec]]\n")

        assertEquals(1, db.graphDao().observeBacklinks("v1", target.value).first().size)
        assertEquals(
            listOf("Spec.md"),
            db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("obsolete"))).first().map { it.path },
        )

        noteService.saveNote("v1", target, "# Updated\nFreshKeyword here\n")
        assertTrue(
            db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("obsolete"))).first().isEmpty(),
        )
        assertEquals(
            listOf("Spec.md"),
            db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("fresh"))).first().map { it.path },
        )

        noteService.renameNote("v1", target, renamed)
        assertEquals(
            listOf(renamed.value),
            db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("fresh"))).first().map { it.path },
        )
        assertEquals(1, db.graphDao().observeBrokenLinks("v1").first().size)

        assertTrue(noteService.deleteNote("v1", renamed))
        assertTrue(
            db.noteDao().searchFts("v1", requireNotNull(FtsQueryBuilder.buildPrefixQuery("fresh"))).first().isEmpty(),
        )
        assertEquals(listOf(referrer.value), db.noteDao().observeAllNotes("v1").first().map { it.path })
    }

    @Test
    fun matchesHierarchicalTagsWithoutPrefixCollisionsAndEscapesLikeWildcards() = runBlocking {
        val noteService = createNoteService()

        noteService.saveNote("v1", VaultPath.of("Exact.md"), "# Exact #project\n- [ ] Exact task #project\n")
        noteService.saveNote("v1", VaultPath.of("Child.md"), "# Child #project/db\n- [ ] Child task #project/db\n")
        noteService.saveNote("v1", VaultPath.of("Sibling.md"), "# Sibling #projection\n- [ ] Sibling task #projection\n")
        noteService.saveNote("v1", VaultPath.of("100%_done.md"), "# 100%_done\n")
        noteService.saveNote("v1", VaultPath.of("1000Xdone.md"), "# 1000Xdone\n")

        val filteredNotes = db.noteDao().filterNotes("v1", tag = "project").first().map { it.path }.toSet()
        assertEquals(setOf("Exact.md", "Child.md"), filteredNotes)

        val ftsFiltered = db.noteDao().searchFts(
            vaultId = "v1",
            ftsQuery = requireNotNull(FtsQueryBuilder.buildPrefixQuery("task")),
            tag = "project",
        ).first().map { it.path }.toSet()
        assertEquals(setOf("Exact.md", "Child.md"), ftsFiltered)

        val graphByTag = db.graphDao().observeNotesByTag("v1", "project").first().map { it.path }.toSet()
        assertEquals(setOf("Exact.md", "Child.md"), graphByTag)

        val tasksByTag = db.taskDao().observeTasks("v1", tag = "project").first().map { it.notePath }.toSet()
        assertEquals(setOf("Exact.md", "Child.md"), tasksByTag)

        val suggested = db.noteDao().suggestNotes("v1", "100%_").first().map { it.path }
        assertEquals(listOf("100%_done.md"), suggested)
    }

    @Test
    fun handlesCreateNoteCollisionsSanitizationAndNestedOrMalformedDailyFormats() = runBlocking {
        val noteService = createNoteService()

        val first = noteService.createNote("v1", "Title")
        val second = noteService.createNote("v1", "Title")
        assertEquals(VaultPath.of("Title.md"), first)
        assertEquals(VaultPath.of("Title 1.md"), second)

        val sanitized = noteService.createNote("v1", "../a:b?*")
        assertEquals(VaultPath.of("a b.md"), sanitized)

        config.updateDailyNotesConfig(vaultDir) {
            it.copy(folder = "Daily", format = "YYYY/MM/DD", template = "Templates/Missing")
        }
        val nestedDaily = noteService.openOrCreateDailyNote("v1", LocalDate.of(2026, 11, 25))
        assertTrue(nestedDaily.created)
        assertEquals(VaultPath.of("Daily/2026/11/25.md"), nestedDaily.path)
        assertEquals("# 25\n\n", noteService.readNote("v1", nestedDaily.path))

        config.updateDailyNotesConfig(vaultDir) {
            it.copy(folder = "../..", format = "../../..", template = "")
        }
        val fallbackDaily = noteService.openOrCreateDailyNote("v1", LocalDate.of(2026, 11, 25))
        assertTrue(fallbackDaily.created)
        assertEquals(VaultPath.of("2026-11-25.md"), fallbackDaily.path)
    }
}
