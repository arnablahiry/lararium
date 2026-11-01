package com.familydocs.app.events

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familydocs.app.FamilyDocsApp
import com.familydocs.app.MainActivity
import com.familydocs.app.R
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.Expiry
import com.familydocs.app.ocr.FieldKeys
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Quiet reminders before documents expire: about 6 months before, a month before, and on the day it runs out. Checked
 * once a day on the phone (nothing leaves it). Only your own documents, the Common vault's and the people you look
 * after here: everyone else's phone reminds them about theirs.
 */
object ExpiryReminders {
    private const val CHANNEL = "expiry_reminders"
    const val OPEN_DOCUMENT = "open_document"

    /** A document whose expiry is coming up (or just passed), with whose it is. */
    data class Due(val doc: DocumentEntity, val member: MemberEntity, val expiry: LocalDate, val level: Expiry.Level)

    fun expiryOf(d: DocumentEntity): LocalDate? =
        Expiry.parse(d.fields.firstOrNull { FieldKeys.canonical(it.label) == "expiry" && it.value.isNotBlank() }?.value)

    /**
     * Vault documents (not event tickets) of [members] expiring within about 6 months, or that ran out in the last
     * [expiredFor] days; soonest first.
     */
    fun due(docs: List<DocumentEntity>, members: List<MemberEntity>, today: LocalDate = LocalDate.now(), expiredFor: Long = 90): List<Due> {
        val byId = members.associateBy { it.id }
        return docs.asSequence().filter { it.eventId == null && !it.archived }.mapNotNull { d ->
            val m = byId[d.memberId] ?: return@mapNotNull null
            val e = expiryOf(d) ?: return@mapNotNull null
            val level = Expiry.level(e, today) ?: return@mapNotNull null
            if (level == Expiry.Level.EXPIRED && Expiry.daysLeft(e, today) < -expiredFor) return@mapNotNull null
            Due(d, m, e, level)
        }.sortedBy { it.expiry }.toList()
    }

    /** Checks every day (and once now, at app start). */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork("expiry-check", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS).build())
        wm.enqueueUniqueWork("expiry-check-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<Worker>().build())
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        @android.annotation.SuppressLint("MissingPermission") // checked by canNotify()
        override suspend fun doWork(): Result {
            val c = (applicationContext as FamilyDocsApp).container
            if (c.repo.accountNow() == null || !EventReminders.canNotify(applicationContext)) return Result.success()
            val dao = c.db.dao()
            val mine = dao.membersNow().filter { it.isSelf || it.shared || it.origin == null }
            val prefs = applicationContext.getSharedPreferences("expiry_reminders", Context.MODE_PRIVATE)
            val told = prefs.getStringSet("told", emptySet())!!.toMutableSet()
            // the expired ones only on the day (or the first check after it), not weeks later
            val list = due(dao.allDocumentsNow(), mine, expiredFor = 7)
            list.forEach { d ->
                val key = "${d.doc.id}|${d.expiry}|${d.level}"
                if (key in told) return@forEach
                notify(applicationContext, d)
                told += key
            }
            // forget documents that are gone or renewed
            val live = list.map { "${it.doc.id}|${it.expiry}|" }
            prefs.edit().putStringSet("told", told.filter { t -> live.any { t.startsWith(it) } }.toSet()).apply()
            return Result.success()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(context: Context, d: Due) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Expiring documents", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "About 6 months and a month before a passport, visa, licence or insurance runs out"
            })
        val whose = when { d.member.isSelf -> "Your"; d.member.shared -> "${d.member.name}:"; else -> "${d.member.name}'s" }
        val doc = if (d.member.isSelf || !d.member.shared) d.doc.name.replaceFirstChar { it.lowercase() } else d.doc.name
        val title = when (d.level) {
            Expiry.Level.EXPIRED -> "$whose $doc has expired"
            else -> "$whose $doc " + Expiry.label(d.expiry).replaceFirstChar { it.lowercase() }
        }
        val text = if (d.level == Expiry.Level.EXPIRED) "It ran out on ${Expiry.format(d.expiry)}."
            else "Valid until ${Expiry.format(d.expiry)}. Renewing can take a while, so it's worth starting now."
        val id = ("expiry|" + d.doc.id).hashCode()
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java).putExtra(OPEN_DOCUMENT, d.doc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_event)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            // on the lock screen only "a document is expiring" (which one shows once unlocked)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_event)
                .setContentTitle("A document is expiring soon").build())
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }
}
