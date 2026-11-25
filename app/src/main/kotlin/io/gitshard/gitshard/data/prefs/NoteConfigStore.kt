package io.gitshard.gitshard.data.prefs

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.AtomicFileWriter
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

enum class NewFileLocation(val rawValue: String) {
    ROOT("root"),
    CURRENT("current"),
    FOLDER("folder");

    companion object {
        fun fromRaw(raw: String?): NewFileLocation =
            entries.firstOrNull { it.rawValue == raw } ?: ROOT
    }
}

data class NoteAppConfig(
    val newFileLocation: NewFileLocation = NewFileLocation.ROOT,
    val newFileFolderPath: String = "",
    val attachmentFolderPath: String = "",
    val useMarkdownLinks: Boolean = false,
    val strictLineBreaks: Boolean = false,
    val showUnsupportedFiles: Boolean = false,
)

data class DailyNotesConfig(
    val format: String = DEFAULT_FORMAT,
    val folder: String = "",
    val template: String = "",
) {
    companion object {
        const val DEFAULT_FORMAT = "YYYY-MM-DD"
    }
}

data class TemplatesConfig(
    val folder: String = "",
    val dateFormat: String = DEFAULT_DATE_FORMAT,
    val timeFormat: String = DEFAULT_TIME_FORMAT,
) {
    companion object {
        const val DEFAULT_DATE_FORMAT = "YYYY-MM-DD"
        const val DEFAULT_TIME_FORMAT = "HH:mm"
    }
}

class NoteConfigStore(
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    suspend fun readAppConfig(vaultRoot: File): NoteAppConfig =
        readConfig(vaultRoot, APP_JSON, ::parseAppConfig)

    suspend fun updateAppConfig(
        vaultRoot: File,
        transform: (NoteAppConfig) -> NoteAppConfig,
    ) = updateConfig(vaultRoot, APP_JSON, ::parseAppConfig, transform) { before, after ->
        buildMap {
            if (after.newFileLocation != before.newFileLocation) {
                put("newFileLocation", JsonPrimitive(after.newFileLocation.rawValue))
            }
            if (after.newFileFolderPath != before.newFileFolderPath) {
                put("newFileFolderPath", JsonPrimitive(after.newFileFolderPath))
            }
            if (after.attachmentFolderPath != before.attachmentFolderPath) {
                put("attachmentFolderPath", JsonPrimitive(after.attachmentFolderPath))
            }
            if (after.useMarkdownLinks != before.useMarkdownLinks) {
                put("useMarkdownLinks", JsonPrimitive(after.useMarkdownLinks))
            }
            if (after.strictLineBreaks != before.strictLineBreaks) {
                put("strictLineBreaks", JsonPrimitive(after.strictLineBreaks))
            }
            if (after.showUnsupportedFiles != before.showUnsupportedFiles) {
                put("showUnsupportedFiles", JsonPrimitive(after.showUnsupportedFiles))
            }
        }
    }

    suspend fun readDailyNotesConfig(vaultRoot: File): DailyNotesConfig =
        readConfig(vaultRoot, DAILY_NOTES_JSON, ::parseDailyNotesConfig)

    suspend fun updateDailyNotesConfig(
        vaultRoot: File,
        transform: (DailyNotesConfig) -> DailyNotesConfig,
    ) = updateConfig(vaultRoot, DAILY_NOTES_JSON, ::parseDailyNotesConfig, transform) { before, after ->
        buildMap {
            if (after.format != before.format) put("format", JsonPrimitive(after.format))
            if (after.folder != before.folder) put("folder", JsonPrimitive(after.folder))
            if (after.template != before.template) put("template", JsonPrimitive(after.template))
        }
    }

    suspend fun readTemplatesConfig(vaultRoot: File): TemplatesConfig =
        readConfig(vaultRoot, TEMPLATES_JSON, ::parseTemplatesConfig)

    suspend fun updateTemplatesConfig(
        vaultRoot: File,
        transform: (TemplatesConfig) -> TemplatesConfig,
    ) = updateConfig(vaultRoot, TEMPLATES_JSON, ::parseTemplatesConfig, transform) { before, after ->
        buildMap {
            if (after.folder != before.folder) put("folder", JsonPrimitive(after.folder))
            if (after.dateFormat != before.dateFormat) put("dateFormat", JsonPrimitive(after.dateFormat))
            if (after.timeFormat != before.timeFormat) put("timeFormat", JsonPrimitive(after.timeFormat))
        }
    }

    private suspend fun <T> readConfig(
        vaultRoot: File,
        fileName: String,
        parse: (JsonObject) -> T,
    ): T = withContext(dispatchers.io) {
        parse(readObjectLenient(configFile(vaultRoot, fileName)))
    }

    private suspend fun <T> updateConfig(
        vaultRoot: File,
        fileName: String,
        parse: (JsonObject) -> T,
        transform: (T) -> T,
        diff: (before: T, after: T) -> Map<String, JsonElement?>,
    ) = withContext(dispatchers.io) {
        val file = configFile(vaultRoot, fileName)
        val existing = readObjectStrict(file)
        val before = parse(existing)
        val patch = diff(before, transform(before))
        if (patch.isNotEmpty()) {
            writeMergedObject(file, existing, patch)
        }
    }

    private fun parseAppConfig(obj: JsonObject): NoteAppConfig = NoteAppConfig(
        newFileLocation = NewFileLocation.fromRaw(obj.stringOrNull("newFileLocation")),
        newFileFolderPath = obj.stringOrNull("newFileFolderPath").orEmpty(),
        attachmentFolderPath = obj.stringOrNull("attachmentFolderPath").orEmpty(),
        useMarkdownLinks = obj.booleanOrNull("useMarkdownLinks") ?: false,
        strictLineBreaks = obj.booleanOrNull("strictLineBreaks") ?: false,
        showUnsupportedFiles = obj.booleanOrNull("showUnsupportedFiles") ?: false,
    )

    private fun parseDailyNotesConfig(obj: JsonObject): DailyNotesConfig = DailyNotesConfig(
        format = obj.stringOrNull("format")?.takeIf { it.isNotBlank() } ?: DailyNotesConfig.DEFAULT_FORMAT,
        folder = obj.stringOrNull("folder").orEmpty(),
        template = obj.stringOrNull("template").orEmpty(),
    )

    private fun parseTemplatesConfig(obj: JsonObject): TemplatesConfig = TemplatesConfig(
        folder = obj.stringOrNull("folder").orEmpty(),
        dateFormat = obj.stringOrNull("dateFormat")?.takeIf { it.isNotBlank() }
            ?: TemplatesConfig.DEFAULT_DATE_FORMAT,
        timeFormat = obj.stringOrNull("timeFormat")?.takeIf { it.isNotBlank() }
            ?: TemplatesConfig.DEFAULT_TIME_FORMAT,
    )

    private fun configFile(vaultRoot: File, fileName: String): File {
        require('/' !in fileName && '\\' !in fileName && fileName != ".." && fileName.isNotBlank()) {
            "Invalid .obsidian config file name: '$fileName'"
        }
        return File(File(vaultRoot, OBSIDIAN_DIR), fileName)
    }

    private fun readObjectLenient(file: File): JsonObject =
        runCatching { readObjectStrict(file) }.getOrDefault(EMPTY_OBJECT)

    private fun readObjectStrict(file: File): JsonObject {
        if (!file.exists() || !file.isFile) return EMPTY_OBJECT
        val raw = file.readText(Charsets.UTF_8)
        if (raw.isBlank()) return EMPTY_OBJECT
        val parsed = json.parseToJsonElement(raw)
        return parsed as? JsonObject
            ?: throw IllegalStateException("Expected JSON object in ${file.name}")
    }

    private fun writeMergedObject(
        file: File,
        existing: JsonObject,
        patch: Map<String, JsonElement?>,
    ) {
        val merged = LinkedHashMap<String, JsonElement>(existing.size + patch.size)
        merged.putAll(existing)
        for ((key, value) in patch) {
            if (value == null) {
                merged.remove(key)
            } else {
                merged[key] = value
            }
        }
        val encoded = json.encodeToString(JsonObject.serializer(), JsonObject(merged)) + "\n"
        AtomicFileWriter.writeText(file, encoded)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    companion object {
        const val OBSIDIAN_DIR = ".obsidian"
        const val APP_JSON = "app.json"
        const val DAILY_NOTES_JSON = "daily-notes.json"
        const val TEMPLATES_JSON = "templates.json"

        private val EMPTY_OBJECT = JsonObject(emptyMap())

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }
}
