package io.gitshard.gitshard.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.gitshard.gitshard.data.prefs.AppPreferences
import io.gitshard.gitshard.data.prefs.AppSettings
import io.gitshard.gitshard.git.SyncResult
import java.util.concurrent.TimeUnit

class GitSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val syncCoordinator: VaultSyncCoordinator,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val forceRun = inputData.getBoolean(KEY_FORCE, false)
        if (!forceRun && !appPreferences.current().autoSyncEnabled) {
            return Result.success()
        }

        val targetVaultId = inputData.getString(KEY_VAULT_ID)?.takeIf { it.isNotBlank() }
        val results = if (targetVaultId != null) {
            listOf(syncCoordinator.sync(targetVaultId))
        } else {
            syncCoordinator.syncAll().values
        }
        return resolveWorkResult(results, runAttemptCount)
    }

    companion object {
        const val KEY_VAULT_ID = "vault_id"
        const val KEY_FORCE = "force"
        internal const val MAX_RETRY_ATTEMPTS = 3

        internal fun resolveWorkResult(
            results: Collection<SyncResult>,
            runAttemptCount: Int,
        ): Result {
            val errors = results.filterIsInstance<SyncResult.Error>()
            if (errors.isEmpty()) return Result.success()
            return if (errors.any { it.isRetryable } && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

class GitSyncWorkerFactory(
    private val syncCoordinator: VaultSyncCoordinator,
    private val appPreferences: AppPreferences,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        GitSyncWorker::class.java.name -> GitSyncWorker(
            appContext = appContext,
            params = workerParameters,
            syncCoordinator = syncCoordinator,
            appPreferences = appPreferences,
        )
        else -> null
    }
}

object GitSyncScheduler {
    const val PERIODIC_WORK_NAME = "gitshard_periodic_sync"
    const val ONE_TIME_WORK_ALL = "gitshard_sync_all"
    const val ONE_TIME_WORK_PREFIX = "gitshard_sync_vault_"
    const val WORK_TAG = "gitshard_sync"

    private const val INITIAL_BACKOFF_SECONDS = 30L

    fun schedulePeriodic(
        workManager: WorkManager,
        settings: AppSettings,
    ) {
        if (!settings.autoSyncEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val intervalMinutes = settings.syncIntervalMinutes
            .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
            .toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkTypeFor(settings.syncOnMeteredNetwork))
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueue(
        workManager: WorkManager,
        vaultId: String? = null,
        syncOnMeteredNetwork: Boolean = true,
        force: Boolean = true,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkTypeFor(syncOnMeteredNetwork))
            .build()

        val request = OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setConstraints(constraints)
            .setInputData(buildInputData(vaultId = vaultId, force = force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(uniqueWorkName(vaultId), ExistingWorkPolicy.KEEP, request)
    }

    private fun buildInputData(vaultId: String?, force: Boolean): Data =
        Data.Builder()
            .putBoolean(GitSyncWorker.KEY_FORCE, force)
            .apply { if (!vaultId.isNullOrBlank()) putString(GitSyncWorker.KEY_VAULT_ID, vaultId) }
            .build()

    private fun uniqueWorkName(vaultId: String?): String =
        if (!vaultId.isNullOrBlank()) "$ONE_TIME_WORK_PREFIX$vaultId" else ONE_TIME_WORK_ALL

    private fun networkTypeFor(allowMetered: Boolean): NetworkType =
        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
}
