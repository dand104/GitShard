package io.gitshard.gitshard.data.sync

import androidx.work.ListenableWorker
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.git.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class GitSyncWorkerTest {

    @Test
    fun `resolves worker result from sync outcomes and retry budget`() {
        assertEquals(
            ListenableWorker.Result.success(),
            GitSyncWorker.resolveWorkResult(listOf(SyncResult.UpToDate), runAttemptCount = 0),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            GitSyncWorker.resolveWorkResult(
                listOf(SyncResult.Conflicts(setOf(VaultPath.of("Home.md")))),
                runAttemptCount = 0,
            ),
        )

        val retryableError = SyncResult.Error("Offline", isRetryable = true)
        val permanentError = SyncResult.Error("Unauthorized", isRetryable = false)

        assertEquals(
            ListenableWorker.Result.retry(),
            GitSyncWorker.resolveWorkResult(
                listOf(SyncResult.UpToDate, retryableError),
                runAttemptCount = GitSyncWorker.MAX_RETRY_ATTEMPTS - 1,
            ),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            GitSyncWorker.resolveWorkResult(
                listOf(retryableError),
                runAttemptCount = GitSyncWorker.MAX_RETRY_ATTEMPTS,
            ),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            GitSyncWorker.resolveWorkResult(listOf(permanentError), runAttemptCount = 0),
        )
    }
}
