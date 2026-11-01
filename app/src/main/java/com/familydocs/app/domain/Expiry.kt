package com.familydocs.app.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Expiry dates of documents: when one is coming up (reminders, the "Expiring soon" strip on Home) and whether a
 * passport is still good enough for a trip. Pure JVM (unit-tested).
 */
object Expiry {
    /** Warned this long before (about 6 months), then again a month before. */
    const val SOON_DAYS = 183
    const val MONTH_DAYS = 31

    enum class Level { SIX_MONTHS, MONTH, EXPIRED }

    private val DMY = Regex("^\\s*(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})\\s*$")
    private val YMD = Regex("^\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*$")
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

    /** "12/05/2027", "12.05.2027", "12-05-2027" or "2027-05-12"; null for anything else. */
    fun parse(value: String?): LocalDate? {
        value ?: return null
        DMY.find(value)?.destructured?.let { (d, m, y) -> return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }
        YMD.find(value)?.destructured?.let { (y, m, d) -> return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }
        return null
    }

    fun daysLeft(expiry: LocalDate, today: LocalDate = LocalDate.now()) = ChronoUnit.DAYS.between(today, expiry)

    /** How close [expiry] is: null while it's more than about 6 months away. */
    fun level(expiry: LocalDate, today: LocalDate = LocalDate.now()): Level? {
        val d = daysLeft(expiry, today)
        return when {
            d < 0 -> Level.EXPIRED
            d <= MONTH_DAYS -> Level.MONTH
            d <= SOON_DAYS -> Level.SIX_MONTHS
            else -> null
        }
    }

    /** "Expired 3 Mar 2026", "Expires today", "Expires tomorrow", "Expires in 12 days", "Expires in 4 months". */
    fun label(expiry: LocalDate, today: LocalDate = LocalDate.now()): String {
        val d = daysLeft(expiry, today)
        return when {
            d < 0 -> "Expired ${DAY.format(expiry)}"
            d == 0L -> "Expires today"
            d == 1L -> "Expires tomorrow"
            d <= 45 -> "Expires in $d days"
            else -> { val m = Math.round(d / 30.4).toInt(); "Expires in $m month${if (m == 1) "" else "s"}" }
        }
    }

    fun format(date: LocalDate): String = DAY.format(date)

    /**
     * A passport that runs out less than 6 months after a trip ends: many countries refuse entry with one. Null when
     * it's fine (or when it isn't a trip abroad's kind of event).
     */
    fun tripProblem(expiry: LocalDate, tripEnd: LocalDate): String? = when {
        expiry.isBefore(tripEnd) || expiry == tripEnd -> "expires before the trip ends"
        expiry.isBefore(tripEnd.plusMonths(6)) -> "expires less than 6 months after the trip"
        else -> null
    }

    private val PASSPORT = Regex("passport|διαβατήριο", RegexOption.IGNORE_CASE)
    fun isPassport(docName: String) = PASSPORT.containsMatchIn(docName)
}
