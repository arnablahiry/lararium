package com.familydocs.app.domain

/**
 * Picks a placeholder icon + colour for a document from the name the user typed.
 * Order matters: the first matching rule wins ("Vaccination certificate" → vaccine, not certificate).
 * Kept free of Android imports so it can be unit-tested on the JVM.
 */
enum class DocIcon { HOUSE, BOLT, FINGERPRINT, CARD, PASSPORT, PLANE, VISA, RESIDENCE, CAR, BALLOT, SHIELD, SYRINGE, BANK, CAP, CERTIFICATE, MEDICAL, RECEIPT, ID, FILE, KEY, PEOPLE, TICKET, BED, TRAIN, BIKE, FOOD, DRINKS }

data class DocType(
    val key: String,
    val label: String,
    val icon: DocIcon,
    /** ARGB colour, e.g. 0xFFD9822B */
    val hue: Long,
    val matched: Boolean = true,
    val initials: String = "",
)

object DocTypes {
    private val I = RegexOption.IGNORE_CASE

    private val RULES: List<Pair<Regex, DocType>> = listOf(
        // Event documents (tickets, bookings, passes) first, so "Car rental" isn't read as a rental agreement.
        Regex("emergency|medical id|\\bice card", I) to DocType("emergency", "Emergency card", DocIcon.MEDICAL, 0xFFB8323A),
        Regex("flight|boarding|airline|\\bpnr\\b|e-?ticket", I) to DocType("flight", "Flight ticket", DocIcon.PLANE, 0xFF2B7BB9),
        Regex("transport pass|travel ?card|metro|oyster|bus pass|rail ?pass|interrail|eurail|\\bpass\\b", I) to DocType("pass", "Transport pass", DocIcon.CARD, 0xFF2F8F83),
        Regex("\\btrain\\b|\\brail(way)?\\b|\\bbus\\b|coach|ferry", I) to DocType("train", "Train / bus ticket", DocIcon.TRAIN, 0xFFB0603A),
        Regex("ticket|admission|concert|museum|\\bmatch\\b", I) to DocType("ticket", "Event ticket", DocIcon.TICKET, 0xFFC2477F),
        // drinks and food before hotels (a "dinner reservation" isn't a hotel booking)
        Regex("\\bdrinks?\\b|\\bbar\\b|\\bpub\\b|cocktail|\\bwine\\b|winery|brewery|\\bbeer\\b|tasting|caf[eé]|coffee", I) to DocType("drinks", "Drinks", DocIcon.DRINKS, 0xFF9A4F8E),
        Regex("restaurant|dinner|lunch|breakfast|brunch|\\bfood\\b|\\bmeal\\b|\\btable\\b|taverna|trattoria|bistro|street food", I) to DocType("food", "Restaurant booking", DocIcon.FOOD, 0xFFC4572E),
        Regex("hotel|hostel|airbnb|\\bbooking\\b|reservation|accommodation|check-?in", I) to DocType("hotel", "Hotel booking", DocIcon.BED, 0xFF8A55A8),
        Regex("car rental|car hire|rent(al)? ?a? ?car|hertz|avis|europcar|sixt|\\btaxi\\b|\\bcab\\b|uber|parking", I) to DocType("rental", "Car rental", DocIcon.CAR, 0xFFC07A2B),
        Regex("\\bbike\\b|bicycle|cycle hire|e-?bike|scooter|moped|motorbike|motorcycle", I) to DocType("bike", "Bike rental", DocIcon.BIKE, 0xFF3F8C5A),
        Regex("aadh?aa?r|uidai|आधार", I) to DocType("aadhaar", "Aadhaar", DocIcon.FINGERPRINT, 0xFFD9822B),
        Regex("\\bpan\\b|permanent account", I) to DocType("pan", "PAN Card", DocIcon.CARD, 0xFF2F6FB0),
        Regex("passport|διαβατ|pasaporte|reisepass", I) to DocType("passport", "Passport", DocIcon.PASSPORT, 0xFF284A80),
        Regex("visa|schengen|βίζα|θεώρηση", I) to DocType("visa", "Visa", DocIcon.VISA, 0xFF7A4FB5),
        Regex("\\brent|lease|tenan|\\bdeed\\b|property|ενοίκ|μίσθωσ", I) to DocType("property", "Rental Agreement", DocIcon.HOUSE, 0xFF6B5B95),
        Regex("\\bbill\\b|electric|utility|wi-?fi|broadband|internet|\\bgas\\b|water|ΔΕΗ", I) to DocType("bill", "Electricity Bill", DocIcon.BOLT, 0xFFC98A12),
        Regex("residen|permit|άδεια|aufenthalt|permesso|\\btrc\\b|\\bbrp\\b|green card|\\boci\\b", I) to DocType("residence", "Residence Permit", DocIcon.RESIDENCE, 0xFF2F7F7A),
        Regex("driv|licen[cs]e|\\bdl\\b", I) to DocType("driving", "Driving Licence", DocIcon.CAR, 0xFFB5523A),
        Regex("voter|\\bepic\\b|election", I) to DocType("voter", "Voter ID", DocIcon.BALLOT, 0xFF5E7029),
        Regex("insur|mediclaim|ayushman|policy|\\behic\\b|health card", I) to DocType("insurance", "Health Insurance", DocIcon.SHIELD, 0xFF3E8E5E),
        Regex("vaccin|covid|immuni", I) to DocType("vaccine", "Vaccination Record", DocIcon.SYRINGE, 0xFFC0467A),
        Regex("bank|passbook|cheque|debit|credit|iban|\\bupi\\b", I) to DocType("bank", "Bank Passbook", DocIcon.BANK, 0xFF5B56C9),
        Regex("degree|diploma|marksheet|transcript|school|universit|\\bph\\.?d|\\bm\\.?sc|\\bb\\.?sc|\\bb\\.?tech", I) to DocType("education", "Degree Certificate", DocIcon.CAP, 0xFF8A6D1F),
        Regex("birth|marriage|death|certificate|domicile|caste|income", I) to DocType("certificate", "Birth Certificate", DocIcon.CERTIFICATE, 0xFF9A5B2E),
        Regex("medical|prescription|blood|report|\\blab\\b", I) to DocType("medical", "Medical Report", DocIcon.MEDICAL, 0xFFC2453D),
        // Greek tax number: one card with the social security number too (ΑΦΜ + ΑΜΚΑ). AMKA on its own stays Social Security.
        Regex("\\bafm\\b|ΑΦΜ", I) to DocType("afmamka", "AFM & AMKA", DocIcon.RECEIPT, 0xFF47708A),
        Regex("social security|\\bssn\\b|\\bamka\\b|ΑΜΚΑ|national insurance|\\bnino?\\b|sozialversicherung|seguridad social|s[ée]curit[ée] sociale|\\besic\\b|\\bepfo?\\b|\\buan\\b", I) to DocType("social", "Social Security", DocIcon.PEOPLE, 0xFF4F7A96),
        Regex("\\btax\\b|\\bitr\\b|form ?16|\\bgst\\b|\\bafm\\b|ΑΦΜ", I) to DocType("tax", "Tax Document", DocIcon.RECEIPT, 0xFF47708A),
        Regex("\\bid\\b|identity|ταυτότητα|national", I) to DocType("id", "National ID", DocIcon.ID, 0xFF4B5D7A),
    )

    private val FALLBACK = longArrayOf(0xFF4B6BAF, 0xFFA0573C, 0xFF2F7F7A, 0xFF7A4FB5, 0xFF8A6D1F, 0xFFB04A6E, 0xFF3F7C4B, 0xFF5868A8)

    val SUGGESTIONS = listOf(
        "Aadhaar", "PAN Card", "Passport", "Visa", "Residence Permit", "Driving Licence", "Voter ID",
        "Social Security", "AFM & AMKA", "Health Insurance", "Birth Certificate", "Bank Passbook", "Vaccination Record", "Degree Certificate", "Emergency card",
    )

    /** Types offered when adding a document to an event (they set the name, so the icon and colour follow). */
    val EVENT_SUGGESTIONS = listOf("Flight ticket", "Train / bus ticket", "Event ticket", "Transport pass", "Hotel booking", "Car rental", "Bike rental", "Restaurant booking", "Drinks", "Travel insurance")

    /** Empty field templates used when OCR finds nothing (or the user skips the file). */
    val TEMPLATES: Map<String, List<String>> = mapOf(
        "flight" to listOf("Booking ref (PNR)", "Flight No.", "From", "To", "Departure", "Seat"),
        "train" to listOf("Booking ref", "Train / Bus No.", "From", "To", "Departure", "Seat / Coach"),
        "ticket" to listOf("Ticket No.", "Event / Place", "Date", "Seat"),
        "pass" to listOf("Pass No.", "Valid From", "Valid Until", "Zones"),
        "hotel" to listOf("Booking ref", "Hotel", "Address", "Check-in", "Check-out"),
        "rental" to listOf("Booking ref", "Company", "Pick-up", "Drop-off"),
        "bike" to listOf("Booking ref", "Company", "Pick-up", "Drop-off"),
        "food" to listOf("Booking ref", "Restaurant", "Date & time", "Guests"),
        "drinks" to listOf("Booking ref", "Place", "Date & time", "Guests"),
        "aadhaar" to listOf("Aadhaar Number", "Name", "Date of Birth", "Gender", "Address"),
        "pan" to listOf("PAN", "Name", "Father's Name", "Date of Birth"),
        "passport" to listOf("Passport No.", "Surname", "Given Names", "Nationality", "Date of Birth", "Date of Issue", "Date of Expiry"),
        "visa" to listOf("Visa No.", "Type", "Valid From", "Valid Until", "Entries"),
        "residence" to listOf("Permit No.", "Type of Permit", "Date of Issue", "Valid Until", "Nationality"),
        "driving" to listOf("Licence No.", "Date of Issue", "Valid Until", "Vehicle Classes"),
        "voter" to listOf("EPIC No.", "Name"),
        "insurance" to listOf("Policy No.", "Insurer", "Valid From", "Valid Until"),
        "bank" to listOf("Account No.", "IFSC / IBAN", "Branch"),
        "social" to listOf("Social Security No.", "Name", "Issued On"),
        "tax" to listOf("Tax No.", "Name"),
        "afmamka" to listOf("AFM", "AMKA", "Name"),
        "id" to listOf("ID No.", "Surname", "Given Names", "Date of Birth", "Date of Issue", "Valid Until"),
        "education" to listOf("Degree", "Subject / Field", "Specialisation", "University", "Year of Passing", "Grade"),
        "property" to listOf("Contract No.", "Landlord", "Address", "Monthly Rent", "Valid Until"),
        "bill" to listOf("Account No.", "Provider", "Amount", "Due Date"),
        "other" to listOf("Document No.", "Name", "Issued On", "Valid Until"),
        "emergency" to listOf("Blood group", "Allergies", "Medication", "Conditions", "Emergency contact", "Contact phone", "Doctor", "Insurance No."),
    )

    /** Kinds that get renewed: adding a new one offers to archive the old one (same person, same country). */
    val RENEWABLE = setOf("passport", "visa", "residence", "driving", "insurance", "id", "voter", "pan", "aadhaar", "emergency")

    /** Labels whose values are masked until the user taps the eye icon. */
    val SENSITIVE = Regex("social security no|\\bssn\\b|\\bamka\\b|aadhaar number|\\bvid\\b|passport no|permit no|document no|visa no|licence no|account no|iban|policy no|card no|\\bpan\\b|epic", I)

    fun isSensitive(label: String) = SENSITIVE.containsMatchIn(label)

    private val DATE_LABEL = Regex("\\bdate|\\bvalid|expir|until|issued(?!\\s*by)|birth|born|\\bdob\\b|\\bfrom\\b|έκδοσ|λήξ|ισχύ|γέννησ|ημερομηνία", RegexOption.IGNORE_CASE)
    private val DATE_VALUE = Regex("^\\s*\\d{1,2}/\\d{1,2}/\\d{4}\\s*$")
    /** A field that holds a date (by its label, or a DD/MM/YYYY value): it gets a calendar to pick from. */
    fun isDate(label: String, value: String = "") = DATE_LABEL.containsMatchIn(label) || DATE_VALUE.matches(value)

    /** National ID cards show the issuing country's flag on their card unless turned off; other documents only when turned on. */
    fun flagByDefault(name: String) = forName(name).key in FLAG_TYPES

    /** National ID cards: these show the issuing country's flag faintly behind the card art unless turned off. */
    val FLAG_TYPES = setOf("passport", "aadhaar", "visa", "residence", "id", "pan", "voter", "social", "tax", "afmamka")

    fun forName(name: String): DocType {
        val n = name.trim()
        RULES.firstOrNull { it.first.containsMatchIn(n) }?.let { return it.second }
        val initials = n.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
        return DocType("other", n, DocIcon.FILE, FALLBACK[Math.floorMod(fnv(n), FALLBACK.size)], matched = false, initials = initials)
    }

    fun template(name: String): List<String> = TEMPLATES[forName(name).key] ?: TEMPLATES.getValue("other")

    private fun fnv(s: String): Int {
        var h = -0x7ee3623b // 2166136261
        for (c in s) { h = h xor c.code; h *= 16777619 }
        return h
    }
}
