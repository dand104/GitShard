package io.gitshard.gitshard.testutil

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.rules.ExternalResource
import java.io.File
import java.nio.file.Files

class IsolatedJGitRule : ExternalResource() {
    private var previous: SystemReader? = null
    private var isolatedDir: File? = null

    override fun before() {
        val dir = Files.createTempDirectory("gitshard-jgit-env-").toFile()
        isolatedDir = dir
        val current = SystemReader.getInstance()
        previous = current
        SystemReader.setInstance(IsolatedSystemReader(current, dir))
    }

    override fun after() {
        previous?.let(SystemReader::setInstance)
        previous = null
        isolatedDir?.deleteRecursively()
        isolatedDir = null
    }

    private class IsolatedSystemReader(
        delegate: SystemReader,
        private val isolatedDir: File,
    ) : SystemReader.Delegate(delegate) {
        override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(isolatedDir, "user.gitconfig"), fs)

        override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(isolatedDir, "system.gitconfig"), fs)

        override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(isolatedDir, "jgit.config"), fs)

        override fun getenv(variable: String): String? = when (variable) {
            "GIT_CONFIG_GLOBAL",
            "GIT_CONFIG_SYSTEM",
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_COUNT",
            "XDG_CONFIG_HOME",
                -> null
            "HOME" -> isolatedDir.absolutePath
            else -> super.getenv(variable)
        }
    }
}
