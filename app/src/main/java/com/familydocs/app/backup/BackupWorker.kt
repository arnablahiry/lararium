package com.familydocs.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familydocs.app.FamilyDocsApp
import java.util.concurrent.TimeUnit

/**
 * Drive backup: once a day on any connection, and a couple of minutes after a document (or login, person, event)
 * is added, changed or deleted. Only what changed is uploaded. Skips quietly if Google Drive access needs the user
 * (Settings shows "Back up now").
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FamilyDocsApp).container
        val shareOnly = inputData.getBoolean(SHARE_ONLY, false)
        val backup = c.settings.value.backupEnabled && !shareOnly
        val sharing = c.repo.groupsNow().any { it.shareIndex != null || it.peerList.isNotEmpty() }
        // the desktop app reads your backup, so its changes only come in while backups are on
        val desk = c.settings.value.backupEnabled
        if (!backup && !sharing && !desk) return Result.success()
        val auth = runCatching { c.backup.authorize() }.getOrElse { return Result.retry() }
        if (auth.hasResolution()) return Result.success() // needs the user; Settings shows "Back up now"
        val token = auth.accessToken ?: return Result.retry()
        // Changes made in the desktop app: taken over first, so this backup and share already carry them.
        if (desk) runCatching { c.desk.find(DriveClient(token)); c.desk.poll() }
        // Shared groups: publish your share and read everyone else's (a failure here never fails the backup).
        if (sharing) runCatching { c.share.sync(token) }
        if (!backup) return Result.success()
        val started = System.currentTimeMillis()
        return runCatching { c.backup.backup(token, c.settings.value.viewableCopies) }
            .onSuccess {
                c.settings.update { s -> s.copy(lastBackup = System.currentTimeMillis()) }
                // the desktop's changes are in Drive now: it can stop showing them as its own
                runCatching { c.desk.ack(DriveClient(token), started) }
            }
            .fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        private const val NAME = "familydocs-drive-backup"
        private const val SHARE_ONLY = "share_only"

        /** In the background, every 15 minutes (Android's shortest): shared groups (only does anything when one is shared). */
        fun scheduleChecks(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(SHARE_ONLY to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            // UPDATE: phones that had the old 3-hour schedule move onto this one
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("$NAME-checks", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** At app start: read what the other members of shared groups changed (and publish yours). */
        fun syncShares(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(androidx.work.workDataOf(SHARE_ONLY to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-share", androidx.work.ExistingWorkPolicy.KEEP, req)
        }

        /** One backup as soon as there's a connection (after sign-up, or before signing out). */
        fun runNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }
        /**
         * After a change: one backup ~2 minutes later. Each new change restarts the wait, so adding several
         * documents in a row ends in a single backup.
         */
        /**
         * Right after a change: your group share goes out a few seconds later (a burst of edits, like typing notes,
         * ends in one upload), and the others' shares are read at the same time. The full backup still waits ~2 min.
         */
        fun shareNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(4, TimeUnit.SECONDS)
                .setInputData(androidx.work.workDataOf(SHARE_ONLY to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-share-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        /** Desktop changes were just taken over: backed up shortly (sooner than [soon]), so the desktop sees them confirmed. */
        fun afterDesk(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-desk", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        fun soon(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-soon", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        /** Daily backup. UPDATE also moves installs that had the old weekly Wi-Fi-only schedule onto this one. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); wm.cancelUniqueWork("$NAME-soon"); return }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
