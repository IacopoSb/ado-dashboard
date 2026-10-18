package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.PrThread

/** A row of a file's diff as the Files tab shows it. */
sealed interface DiffRow {
    /** "@@ -84,6 +84,13 @@" above each run of lines shown. */
    data class Header(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int) : DiffRow {
        val text get() = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
    }

    /** [index] is the line's place in the whole diff. */
    data class Line(val index: Int, val line: DiffLine) : DiffRow

    /** "⋯ [count] unchanged lines" hidden from [start] (an index in the whole diff); tapped, shown. */
    data class Gap(val start: Int, val count: Int) : DiffRow

    /** The threads on the line at [index], under it. */
    data class Threads(val index: Int, val threads: List<PrThread>) : DiffRow

    /** The composer of a new thread on the line at [index], under it. */
    data class NewThread(val index: Int) : DiffRow
}

/** A row of the side by side view: deletions on the left, additions on the right. */
sealed interface SideRow {
    data class Header(val header: DiffRow.Header) : SideRow
    data class Gap(val gap: DiffRow.Gap) : SideRow
    data class Pair(val left: DiffRow.Line?, val right: DiffRow.Line?) : SideRow
    data class Threads(val threads: DiffRow.Threads) : SideRow
    data class NewThread(val row: DiffRow.NewThread) : SideRow
}

/** Threads placed on a file's diff: by line index, and those whose line is not in it. */
data class PlacedThreads(val byIndex: Map<Int, List<PrThread>>, val elsewhere: List<PrThread>)

/** Where a comment goes in a file: the line's number on the right (new) or on the left (deleted) side. */
data class LineRef(val line: Int, val left: Boolean)

/** The hunks of a diff, its hidden stretches, its threads and the side by side pairing. Pure. */
object DiffRows {
    /** Unchanged lines shown around each change. */
    const val CONTEXT = 3

    /**
     * The rows of [lines]: each change with [context] lines around it, as are the lines in
     * [pinned] (those with threads or the composer), a header atop each run shown, the rest as
     * gaps unless their start is in [expanded]. [threads] and [composerAt] go under their lines.
     */
    fun rows(
        lines: List<DiffLine>,
        expanded: Set<Int> = emptySet(),
        threads: Map<Int, List<PrThread>> = emptyMap(),
        composerAt: Int? = null,
        context: Int = CONTEXT,
    ): List<DiffRow> {
        val n = lines.size
        val shown = BooleanArray(n)
        val anchors = lines.indices.filter { lines[it].kind != DiffKind.CONTEXT } + threads.keys + listOfNotNull(composerAt)
        anchors.filter { it in 0 until n }.forEach { i ->
            for (j in maxOf(0, i - context)..minOf(n - 1, i + context)) shown[j] = true
        }
        // A hidden stretch is named by its first line before anything is expanded
        var i = 0
        while (i < n) {
            if (shown[i]) {
                i++
                continue
            }
            var end = i
            while (end < n && !shown[end]) end++
            if (i in expanded) for (j in i until end) shown[j] = true
            i = end
        }
        val rows = mutableListOf<DiffRow>()
        i = 0
        while (i < n) {
            var end = i
            while (end < n && shown[end] == shown[i]) end++
            if (!shown[i]) {
                rows += DiffRow.Gap(i, end - i)
            } else {
                rows += header(lines, i, end)
                for (j in i until end) {
                    rows += DiffRow.Line(j, lines[j])
                    threads[j]?.takeIf { it.isNotEmpty() }?.let { rows += DiffRow.Threads(j, it) }
                    if (composerAt == j) rows += DiffRow.NewThread(j)
                }
            }
            i = end
        }
        return rows
    }

    /** "@@ -a,b +c,d @@" of lines [from, to); an empty side starts at the line before, as in git. */
    private fun header(lines: List<DiffLine>, from: Int, to: Int): DiffRow.Header {
        val run = lines.subList(from, to)
        val olds = run.filter { it.oldNo > 0 }
        val news = run.filter { it.newNo > 0 }
        val oldBefore = lines.subList(0, from).lastOrNull { it.oldNo > 0 }?.oldNo ?: 0
        val newBefore = lines.subList(0, from).lastOrNull { it.newNo > 0 }?.newNo ?: 0
        return DiffRow.Header(
            olds.firstOrNull()?.oldNo ?: oldBefore, olds.size,
            news.firstOrNull()?.newNo ?: newBefore, news.size,
        )
    }

    /** The line of [ref] in [lines]: on the right side an added or unchanged line, on the left a deleted or unchanged one. */
    fun indexOf(lines: List<DiffLine>, ref: LineRef): Int? {
        val i = if (ref.left) {
            lines.indexOfFirst { it.oldNo == ref.line && it.kind != DiffKind.ADD }
        } else {
            lines.indexOfFirst { it.newNo == ref.line && it.kind != DiffKind.DELETE }
        }
        return i.takeIf { it >= 0 }
    }

    /** [threads] on the file under their lines; those without a line, or one not in the diff, apart. */
    fun place(lines: List<DiffLine>, threads: List<PrThread>): PlacedThreads {
        val byIndex = mutableMapOf<Int, MutableList<PrThread>>()
        val elsewhere = mutableListOf<PrThread>()
        threads.forEach { t ->
            val index = t.line?.let { indexOf(lines, LineRef(it, t.leftSide)) }
            if (index == null) elsewhere += t else byIndex.getOrPut(index) { mutableListOf() } += t
        }
        return PlacedThreads(byIndex, elsewhere)
    }

    /** [rows] side by side: unchanged lines on both sides, each run of deletions next to the additions after it. */
    fun sideBySide(rows: List<DiffRow>): List<SideRow> {
        val out = mutableListOf<SideRow>()
        val deleted = mutableListOf<DiffRow.Line>()
        val added = mutableListOf<DiffRow.Line>()
        fun flush() {
            for (k in 0 until maxOf(deleted.size, added.size)) out += SideRow.Pair(deleted.getOrNull(k), added.getOrNull(k))
            deleted.clear()
            added.clear()
        }
        rows.forEach { row ->
            when (row) {
                is DiffRow.Line -> when (row.line.kind) {
                    DiffKind.DELETE -> {
                        if (added.isNotEmpty()) flush()
                        deleted += row
                    }
                    DiffKind.ADD -> added += row
                    DiffKind.CONTEXT -> {
                        flush()
                        out += SideRow.Pair(row, row)
                    }
                }
                is DiffRow.Header -> {
                    flush()
                    out += SideRow.Header(row)
                }
                is DiffRow.Gap -> {
                    flush()
                    out += SideRow.Gap(row)
                }
                is DiffRow.Threads -> {
                    flush()
                    out += SideRow.Threads(row)
                }
                is DiffRow.NewThread -> {
                    flush()
                    out += SideRow.NewThread(row)
                }
            }
        }
        flush()
        return out
    }

    /** Lines added and deleted. */
    fun counts(lines: List<DiffLine>): kotlin.Pair<Int, Int> =
        lines.count { it.kind == DiffKind.ADD } to lines.count { it.kind == DiffKind.DELETE }
}
