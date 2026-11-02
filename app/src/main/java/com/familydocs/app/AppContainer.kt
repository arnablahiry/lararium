package com.familydocs.app

import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import com.familydocs.app.auth.GoogleAccountHelper
import com.familydocs.app.backup.BackupKeyStore
import com.familydocs.app.backup.BackupWorker
import com.familydocs.app.backup.DriveBackup
import com.familydocs.app.data.AppDatabase
import com.familydocs.app.data.FileStore
import com.familydocs.app.data.VaultRepository
import com.familydocs.app.ocr.TextReader
import com.familydocs.app.security.AppLock
import com.familydocs.app.security.CryptoManager
import com.familydocs.app.security.PasscodeManager
import com.familydocs.app.security.SettingsStore

/** Hand-rolled dependency container (no DI framework needed at this size). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val appContext: Context get() = app
    val crypto = CryptoManager(app)
    val files = FileStore(app, crypto)
    val db: AppDatabase by lazy { AppDatabase.build(app, crypto.databasePassphrase()) }
    val repo: VaultRepository by lazy {
        // Any change to the vault: back up to Drive / update shared groups a couple of minutes later.
        VaultRepository(db.dao(), files).also { r ->
            // (both check whether backup or group sharing is on): the share goes out within a second, the backup ~2 min later
            r.onChanged = { publishSoon(); BackupWorker.soon(app) }
            r.admin = { share } // a group admin's changes to other members' things
            r.groupId.value = settings.value.currentGroup
        }
    }
    val passcode = PasscodeManager(app).also { p -> p.onChanged = { if (settings.value.backupEnabled) BackupWorker.soon(app) } }
    /** A second PIN, only for showing or copying saved passwords (set with the first login you save). */
    val pwPin = PasscodeManager(app, "pw_pin")
    val settings = SettingsStore(app)
    val appLock = AppLock(settings)
    val textReader = TextReader()
    val google = GoogleAccountHelper(app)
    /** Outlives any screen: sign out and delete account keep running after the vault UI disappears. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    /** In-app updates from the GitHub releases (checked at app start; Settings › About shows and installs them). */
    val updater = com.familydocs.app.update.AppUpdater(app)
    /** An invite that opened the app (tapped link or shared text), waiting for Home or sign-up to use it. */
    val incomingInvite = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A group just joined (at sign-up): Home opens its "not synced yet" sheet once. */
    val openSyncSheet = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Originals behind cropped pictures, for "Edit photo". */
    val photoSources by lazy { com.familydocs.app.data.PhotoSources(app, files) }
    /** The "what's new" note on Home after an update. */
    val whatsNew by lazy { com.familydocs.app.update.WhatsNew(app) }
    /** Optional backup password for logins (Settings › Backup). */
    val loginLock = com.familydocs.app.backup.LoginLock(app, crypto).also { l -> l.onChanged = { if (settings.value.backupEnabled) BackupWorker.soon(app) } }
    val backup: DriveBackup by lazy { DriveBackup(app, repo, files, BackupKeyStore(app, crypto), passcode, loginLock) }
    /** Groups shared between accounts (encrypted shares in each member's Drive). */
    val share: com.familydocs.app.backup.GroupShare by lazy { com.familydocs.app.backup.GroupShare(app, db.dao(), files, crypto) }
    /** Changes made in the desktop app, taken over here. */
    val desk: com.familydocs.app.backup.DeskInbox by lazy { com.familydocs.app.backup.DeskInbox(app, repo, db.dao(), files, backup.keys) }

    // ---- your changes reach the group within a second: shared right here (the background job is only the fallback) ----
    private val publishRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    /** Drive access, kept for a while so each change doesn't ask Google again. */
    private var driveToken: Pair<String, Long>? = null

    /** What Home's sync line shows: sharing your changes, up to date (when), or what's wrong. */
    data class SyncStatus(val sharing: Boolean = false, val sharedAt: Long = 0L, val checkedAt: Long = 0L,
                          val problem: String? = null, val needsGoogle: Boolean = false, val offline: Boolean = false)
    val syncStatus = kotlinx.coroutines.flow.MutableStateFlow(SyncStatus())

    /** Just added a renewed document: the older ones it replaces, offered to be archived (the new one's name, their ids). */
    val archiveOffer = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, List<String>>?>(null)

    /** A document to open (from an expiry reminder), once the vault is showing. */
    val openDocument = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Shares your changes now (a burst of changes, like typing notes, goes out as one). */
    fun publishSoon() { syncStatus.value = syncStatus.value.copy(sharing = true); publishRequests.tryEmit(Unit) }

    /** After a look at the others' shares: when, or that Drive couldn't be reached. */
    fun checked(reachable: Boolean) {
        syncStatus.value = if (reachable) syncStatus.value.copy(checkedAt = System.currentTimeMillis(), offline = false) else syncStatus.value.copy(offline = true)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startPublishing() = appScope.launch {
        publishRequests.debounce(500).collect { publishOnce() }
    }

    private suspend fun publishOnce() {
        if (repo.groupsNow().none { it.shareIndex != null || it.peerList.isNotEmpty() }) { syncStatus.value = syncStatus.value.copy(sharing = false); return }
        val ok = runCatching {
            val token = driveToken?.takeIf { System.currentTimeMillis() < it.second }?.first ?: run {
                val auth = backup.authorize()
                if (auth.hasResolution()) { // needs the user (Settings); the background job couldn't either
                    syncStatus.value = syncStatus.value.copy(sharing = false, problem = "Google Drive needs you · tap", needsGoogle = true)
                    return
                }
                (auth.accessToken ?: error("No Drive access")).also { driveToken = it to System.currentTimeMillis() + 40 * 60_000L }
            }
            share.publishNow(token)
        }.isSuccess
        syncStatus.value = if (ok) syncStatus.value.copy(sharing = false, sharedAt = System.currentTimeMillis(), problem = null, needsGoogle = false, offline = false)
            else syncStatus.value.copy(sharing = false, problem = "Couldn't share your changes · tap to retry", needsGoogle = false)
        if (!ok) { driveToken = null; BackupWorker.shareNow(app) }
    }

    init { startPublishing() }


    /** Shows group [id] on Home and remembers it. */
    fun selectGroup(id: String?) { repo.groupId.value = id; settings.update { it.copy(currentGroup = id) } }

    /** After start-up or a restore: make sure the current group exists (else the first one). */
    suspend fun settleGroup() {
        val gs = repo.groupsNow()
        val want = settings.value.currentGroup
        val id = gs.firstOrNull { it.id == want }?.id ?: gs.firstOrNull()?.id
        if (id != repo.groupId.value || id != want) selectGroup(id)
    }

    /** Turns the daily Drive backup (plus one after each change) on or off; [now] also queues one right away. */
    fun setBackups(on: Boolean, now: Boolean = false) {
        settings.update { it.copy(backupEnabled = on) }
        BackupWorker.schedule(app, on)
        if (on && now) BackupWorker.runNow(app)
    }

    /** Sign out / delete account: forget Google, then remove everything Lararium keeps on this phone. */
    suspend fun wipeThisPhone() {
        BackupWorker.schedule(app, false)
        androidx.work.WorkManager.getInstance(app).cancelAllWork() // event reminders too
        runCatching { google.signOut() }
        backup.keys.clear()
        backup.forgetManifest() // the next backup (on any account) checks everything again
        desk.forget()
        repo.wipe()
        settings.reset()
        repo.groupId.value = null
        pwPin.clear()
        com.familydocs.app.ui.components.VaultImages.clear()
        photoSources.clear()
        loginLock.clear()
        passcode.clear() // last: this is what switches the app back to the welcome screen
    }
}
