package io.gitshard.gitshard.git.jgit

import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.WindowCacheConfig
import java.util.concurrent.atomic.AtomicBoolean

object JGitConfig {
    const val STREAM_FILE_THRESHOLD_BYTES: Int = 2 * 1024 * 1024
    const val BIG_FILE_THRESHOLD_BYTES: Long = 2L * 1024 * 1024
    const val PACKED_GIT_LIMIT_BYTES: Long = 16L * 1024 * 1024
    const val PACKED_GIT_WINDOW_SIZE_BYTES: Int = 8 * 1024
    const val DELTA_BASE_CACHE_LIMIT_BYTES: Int = 4 * 1024 * 1024
    const val NETWORK_TIMEOUT_SECONDS: Int = 30

    private val installed = AtomicBoolean(false)

    fun installGlobalDefaults() {
        if (!installed.compareAndSet(false, true)) return

        WindowCacheConfig().apply {
            packedGitLimit = PACKED_GIT_LIMIT_BYTES
            packedGitWindowSize = PACKED_GIT_WINDOW_SIZE_BYTES
            streamFileThreshold = STREAM_FILE_THRESHOLD_BYTES
            deltaBaseCacheLimit = DELTA_BASE_CACHE_LIMIT_BYTES
            install()
        }
    }

    fun applyRepoDefaults(repository: Repository) {
        val config = repository.config
        var dirty = false

        if (config.getLong(ConfigConstants.CONFIG_CORE_SECTION, null, "bigFileThreshold", -1L) != BIG_FILE_THRESHOLD_BYTES) {
            config.setLong(ConfigConstants.CONFIG_CORE_SECTION, null, "bigFileThreshold", BIG_FILE_THRESHOLD_BYTES)
            dirty = true
        }
        if (config.getBoolean(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_FILEMODE, true)) {
            config.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_FILEMODE, false)
            dirty = true
        }
        if (config.getString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOCRLF) != "false") {
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOCRLF, "false")
            dirty = true
        }
        if (config.getBoolean(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_QUOTE_PATH, true)) {
            config.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_QUOTE_PATH, false)
            dirty = true
        }

        if (dirty) {
            config.save()
        }
    }
}
