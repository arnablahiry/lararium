package com.familydocs.app.domain

/**
 * Two people edited an event's notes at the same time: keeps both instead of the later one winning. Line by line,
 * against the last version both had ([base]): what you added stays (where you put it), what you deleted goes, and
 * their changes are kept as they made them. Pure JVM (unit-tested).
 */
object NotesMerge {
    fun merge(base: String, mine: String, theirs: String): String {
        if (mine == theirs || mine == base) return theirs
        if (theirs == base) return mine
        val baseLines = base.lines().filter { it.isNotBlank() }.toSet()
        val mineLines = mine.lines()
        val mineSet = mineLines.toSet()
        // their version, without the lines you deleted (that they kept unchanged)
        val out = theirs.lines().filter { !(it.isNotBlank() && it in baseLines && it !in mineSet) }.toMutableList()
        // your new lines, each after the line it followed in your version (or at the top)
        mineLines.forEachIndexed { i, line ->
            if (line.isBlank() || line in baseLines || line in out) return@forEachIndexed
            val prev = (i - 1 downTo 0).map { mineLines[it] }.firstOrNull { it.isNotBlank() && it in out }
            out.add(if (prev == null) 0 else out.indexOf(prev) + 1, line)
        }
        return out.joinToString("\n").trim('\n')
    }
}
