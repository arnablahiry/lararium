package com.familydocs.app

import kotlinx.coroutines.launch
import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

class FamilyDocsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appLock)
        // Keeps the daily backup scheduled (and moves older weekly schedules onto it).
        if (container.settings.value.backupEnabled) com.familydocs.app.backup.BackupWorker.schedule(this, true)
        container.appScope.launch { container.updater.check() } // quiet check for a newer release
        container.appScope.launch { container.settleGroup() }
        com.familydocs.app.backup.BackupWorker.syncShares(this) // shared groups: pick up what the others changed
        com.familydocs.app.backup.BackupWorker.scheduleChecks(this) // shared groups every 15 minutes in the background
        com.familydocs.app.events.ExpiryReminders.schedule(this) // documents about to expire: checked daily
        // While Lararium is open (and unlocked): the others' changes come in within seconds. Each look is one tiny
        // request per member; only a share that changed is downloaded and read.
        val process = androidx.lifecycle.ProcessLifecycleOwner.get()
        process.lifecycleScope.launch {
            process.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var round = 0
                while (true) {
                    if (!container.appLock.locked.value && container.repo.groupsNow().any { it.peerList.isNotEmpty() }) {
                        runCatching { container.share.poll() }.onSuccess { container.checked(it.reachable) }
                        if (container.share.wantsPublish) container.publishSoon() // someone changed one of your events
                        // every 5 minutes a full sync as well (it also retries anything of yours that didn't go out)
                        if (round++ % 60 == 0) com.familydocs.app.backup.BackupWorker.syncShares(this@FamilyDocsApp)
                    }
                    kotlinx.coroutines.delay(5_000)
                }
            }
        }
    }
}
