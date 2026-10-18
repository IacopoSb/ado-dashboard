package io.github.iacoposb.adodash.ui

enum class DiffKind { CONTEXT, ADD, DELETE }

/**
 * A line of a file's diff: [oldNo] its number in the base (0 for an added line), [newNo] in the
 * pull request's version (0 for a deleted one).
 */
data class DiffLine(val kind: DiffKind, val oldNo: Int, val newNo: Int, val text: String)

/**
 * The line diff of two versions of a file: Myers' O(ND) algorithm in its linear space form
 * (the middle snake, divide and conquer), on lines interned as numbers, with the common head and
 * tail of each part skipped first. The result is a shortest edit script; deletions come before
 * insertions where both happen. Pure.
 */
object LineDiff {
    /** Past this, a part still to diff is shown as all deleted and all added: never a frozen page. */
    private const val DEFAULT_BUDGET = 40_000_000L

    fun diff(old: List<String>, new: List<String>, budget: Long = DEFAULT_BUDGET): List<DiffLine> {
        val ids = HashMap<String, Int>()
        val a = IntArray(old.size) { ids.getOrPut(old[it]) { ids.size } }
        val b = IntArray(new.size) { ids.getOrPut(new[it]) { ids.size } }
        val run = Run(a, b, budget)
        run.compare(0, a.size, 0, b.size)
        val out = ArrayList<DiffLine>(maxOf(a.size, b.size))
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && run.deleted[i] -> {
                    out += DiffLine(DiffKind.DELETE, i + 1, 0, old[i])
                    i++
                }
                j < b.size && run.inserted[j] -> {
                    out += DiffLine(DiffKind.ADD, 0, j + 1, new[j])
                    j++
                }
                else -> {
                    out += DiffLine(DiffKind.CONTEXT, i + 1, j + 1, new[j])
                    i++
                    j++
                }
            }
        }
        return out
    }

    /** A file's text as lines: any line ending, no empty line for the final newline. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\r\n", "\n", "\r")
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private class Run(val a: IntArray, val b: IntArray, var budget: Long) {
        val deleted = BooleanArray(a.size)
        val inserted = BooleanArray(b.size)

        fun compare(aLo0: Int, aHi0: Int, bLo0: Int, bHi0: Int) {
            var aLo = aLo0
            var aHi = aHi0
            var bLo = bLo0
            var bHi = bHi0
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
                aLo++
                bLo++
            }
            while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
                aHi--
                bHi--
            }
            when {
                aLo == aHi -> for (j in bLo until bHi) inserted[j] = true
                bLo == bHi -> for (i in aLo until aHi) deleted[i] = true
                else -> {
                    val split = bisect(aLo, aHi, bLo, bHi)
                    if (split == null || (split.first == aLo && split.second == bLo) || (split.first == aHi && split.second == bHi)) {
                        for (i in aLo until aHi) deleted[i] = true
                        for (j in bLo until bHi) inserted[j] = true
                    } else {
                        compare(aLo, split.first, bLo, split.second)
                        compare(split.first, aHi, split.second, bHi)
                    }
                }
            }
        }

        /**
         * Where the forward and the backward shortest paths of a[aLo, aHi) against b[bLo, bHi)
         * meet, as absolute indexes; null over budget.
         */
        private fun bisect(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Pair<Int, Int>? {
            val n = aHi - aLo
            val m = bHi - bLo
            val maxD = (n + m + 1) / 2
            val offset = maxD
            val length = 2 * maxD + 2
            val v1 = IntArray(length) { -1 }
            val v2 = IntArray(length) { -1 }
            v1[offset + 1] = 0
            v2[offset + 1] = 0
            val delta = n - m
            val front = delta % 2 != 0
            var k1start = 0
            var k1end = 0
            var k2start = 0
            var k2end = 0
            for (d in 0 until maxD) {
                budget -= (2L * d + 1) * 2
                if (budget < 0) return null
                var k1 = -d + k1start
                while (k1 <= d - k1end) {
                    val k1o = offset + k1
                    var x1 = if (k1 == -d || (k1 != d && v1[k1o - 1] < v1[k1o + 1])) v1[k1o + 1] else v1[k1o - 1] + 1
                    var y1 = x1 - k1
                    while (x1 < n && y1 < m && a[aLo + x1] == b[bLo + y1]) {
                        x1++
                        y1++
                    }
                    v1[k1o] = x1
                    if (x1 > n) {
                        k1end += 2
                    } else if (y1 > m) {
                        k1start += 2
                    } else if (front) {
                        val k2o = offset + delta - k1
                        if (k2o in 0 until length && v2[k2o] != -1 && x1 >= n - v2[k2o]) return aLo + x1 to bLo + y1
                    }
                    k1 += 2
                }
                var k2 = -d + k2start
                while (k2 <= d - k2end) {
                    val k2o = offset + k2
                    var x2 = if (k2 == -d || (k2 != d && v2[k2o - 1] < v2[k2o + 1])) v2[k2o + 1] else v2[k2o - 1] + 1
                    var y2 = x2 - k2
                    while (x2 < n && y2 < m && a[aHi - 1 - x2] == b[bHi - 1 - y2]) {
                        x2++
                        y2++
                    }
                    v2[k2o] = x2
                    if (x2 > n) {
                        k2end += 2
                    } else if (y2 > m) {
                        k2start += 2
                    } else if (!front) {
                        val k1o = offset + delta - k2
                        if (k1o in 0 until length && v1[k1o] != -1) {
                            val x1 = v1[k1o]
                            val y1 = offset + x1 - k1o
                            if (x1 >= n - x2) return aLo + x1 to bLo + y1
                        }
                    }
                    k2 += 2
                }
            }
            return null
        }
    }
}
