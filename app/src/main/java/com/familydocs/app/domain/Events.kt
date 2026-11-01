package com.familydocs.app.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Event helpers shared by the screens and the reminder worker (same rules as the demo's part2e.js). Pure JVM. */
object Events {
    private val I = RegexOption.IGNORE_CASE
    private val RULES = listOf(
        Regex("jaipur|rajasthan|udaipur|jodhpur|agra|delhi|hampi|fort|palace|heritage|museum|rome|athens|acropolis|histor", I) to "heritage",
        Regex("desert|jaisalmer|sahara|dubai|thar|camel|dune", I) to "desert",
        Regex("tiger|safari|ranthambore|corbett|kanha|kaziranga|jungle|wildlife|national park|zoo", I) to "jungle",
        Regex("beach|goa|island|santorini|crete|maldives|coast|kerala|andaman|swim", I) to "beach",
        Regex("mountain|hike|trek|himalaya|manali|darjeeling|alps|ski|shimla|sikkim|ladakh|kashmir|olympus", I) to "mountain",
        Regex("birthday|b'?day", I) to "birthday",
        Regex("visa|passport|appointment|embassy|consulate|interview|biometric|vfs|renew|aadhaar|office|bank|tax", I) to "appointment",
        Regex("doctor|dentist|hospital|check-?up|clinic|vaccin|scan|surgery", I) to "health",
        Regex("anniversary|wedding|marriage|engagement", I) to "anniversary",
        Regex("puja|diwali|durga|holi|festival|christmas|easter|eid|new year|pongal|onam", I) to "festival",
        Regex("trip|flight|holiday|vacation|travel|tour|visit", I) to "trip",
    )

    /** Picture picked from the name ("Goa holiday" → beach) until the user chooses one. */
    fun artFor(name: String): String = RULES.firstOrNull { it.first.containsMatchIn(name) }?.second ?: "trip"

    /** Reminder choices: days before the start, -1 = off. */
    val REMINDERS = listOf(-1 to "Off", 0 to "On the day", 1 to "1 day before", 7 to "1 week before", 30 to "1 month before")
    fun reminderLabel(days: Int) = REMINDERS.firstOrNull { it.first == days }?.second ?: "$days days before"

    private val TRAVEL = setOf("trip", "mountain", "beach", "heritage", "desert", "jungle")
    /** A trip (rather than a birthday or an appointment): passports are checked against it. */
    fun isTravel(art: String) = art in TRAVEL

    /** Which vault documents of the people going are worth pinning to this kind of event. */
    fun suggestionPattern(art: String): Regex? = when {
        art in TRAVEL -> Regex("passport|visa|residen|permit|aadhaar|\\bid\\b|identity|insur", I)
        art == "appointment" -> Regex("passport|visa|residen|permit|aadhaar|\\bpan\\b", I)
        art == "health" -> Regex("insur|medical|vaccin", I)
        else -> null
    }

    fun parse(day: String?): LocalDate? = day?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** "in 12 days", "Tomorrow", "Today", "Happening now", "3 days ago"… */
    fun countdown(start: String, end: String?, today: LocalDate = LocalDate.now()): String {
        val s = parse(start) ?: return ""
        val e = parse(end) ?: s
        val d = ChronoUnit.DAYS.between(today, s)
        val de = ChronoUnit.DAYS.between(today, e)
        return when {
            d > 60 -> "in ${Math.round(d / 30.0)} months"
            d > 1 -> "in $d days"
            d == 1L -> "Tomorrow"
            d == 0L -> "Today"
            de >= 0 -> "Happening now"
            de == -1L -> "Yesterday"
            else -> "${-de} days ago"
        }
    }

    /** Shown on the home screen until a week after the event ends. */
    fun isCurrent(start: String, end: String?, today: LocalDate = LocalDate.now()): Boolean {
        val e = parse(end) ?: parse(start) ?: return false
        return ChronoUnit.DAYS.between(today, e) >= -7
    }

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val DAY_NO_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** "20–25 Nov 2026", "28 Nov – 3 Dec 2026", "2 Nov 2026". */
    fun dates(start: String, end: String?): String {
        val s = parse(start) ?: return start
        val e = parse(end)?.takeIf { it.isAfter(s) } ?: return DAY.format(s)
        return if (s.month == e.month && s.year == e.year) "${s.dayOfMonth}–${DAY.format(e)}" else "${DAY_NO_YEAR.format(s)} – ${DAY.format(e)}"
    }
}
