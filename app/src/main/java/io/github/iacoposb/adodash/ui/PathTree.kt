package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Iteration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A node of a project's iterations or areas; [path] as work items read it
 * ("Project\\Release 3\\Sprint 42"), the project itself the root. Areas have no dates.
 */
data class PathNode(
    val name: String,
    val path: String,
    val start: Instant? = null,
    val finish: Instant? = null,
    val children: List<PathNode> = emptyList(),
)

/** A line of the tree as the picker shows it: a node, or the past sprints of a folder. */
sealed interface PathLine {
    val level: Int

    /** Unique in the tree: what the picker keys the line by and keeps in its expanded set. */
    val key: String
}

/** A node: [open] when its children are shown below it. */
data class PathRow(val node: PathNode, override val level: Int, val expandable: Boolean, val open: Boolean, val match: Boolean) : PathLine {
    override val key: String get() = node.path
}

/** The [count] children of [parent] that are over, gathered in one line; [open] shows them below it. */
data class PastRow(val parent: String, val count: Int, override val level: Int, val open: Boolean) : PathLine {
    override val key: String get() = PathTree.pastKey(parent)
}

/** Nodes whose name contains [query], and the ones shown for them: those with their ancestors. */
data class PathSearch(val query: String, val matches: Set<String>, val visible: Set<String>)

/** The iteration and area trees of the work item details: building, showing and searching them. */
object PathTree {
    /** Iterations end at the start of their last day: that day still counts. */
    private const val DAY_SECONDS = 24 * 60 * 60L

    /**
     * The tree of [iterations] (any order, as the API lists them) under [project]. Siblings go by
     * their start, a folder's being its earliest sprint's; those without dates last.
     */
    fun fromIterations(project: String, iterations: List<Iteration>): PathNode {
        class Builder(val name: String, val path: String, var start: Instant? = null, var finish: Instant? = null) {
            val children = mutableListOf<Builder>()
        }
        val root = Builder(project, project)
        val byPath = mutableMapOf(project.lowercase() to root)
        fun node(path: String): Builder = byPath.getOrPut(path.lowercase()) {
            val parent = node(path.substringBeforeLast('\\', project))
            Builder(path.substringAfterLast('\\'), path).also { parent.children += it }
        }
        iterations.forEach { iteration ->
            val parts = iteration.path.split('\\').filter(String::isNotEmpty)
            // Under the project, whatever case the API gave it
            val path = (listOf(project) + parts.drop(1)).joinToString("\\")
            node(path).apply {
                start = iteration.start
                finish = iteration.finish
            }
        }
        fun Builder.earliest(): Instant? = start ?: children.mapNotNull { it.earliest() }.minOrNull()
        fun Builder.build(): PathNode = PathNode(
            name, path, start, finish,
            children.sortedWith(compareBy(nullsLast()) { it.earliest() }).map { it.build() },
        )
        return root.build()
    }

    fun fromArea(root: AreaNode): PathNode = PathNode(root.name, root.path, children = root.children.map(::fromArea))

    /** The path below the project ("Release 3", "Sprint 42"); the project's own path, its name. */
    fun segments(path: String): List<String> {
        val parts = path.split('\\').filter(String::isNotEmpty)
        return parts.drop(1).ifEmpty { parts.take(1) }
    }

    /** "Release 3 › Sprint 42". */
    fun label(path: String): String = segments(path).joinToString(" › ")

    /** Its dates include [now]. */
    fun isCurrent(node: PathNode, now: Instant): Boolean {
        val start = node.start ?: return false
        val finish = node.finish ?: return false
        return start <= now && now <= finish.plusSeconds(DAY_SECONDS)
    }

    /** Over before [now]: its own end, or a folder's latest sprint's. */
    fun isPast(node: PathNode, now: Instant): Boolean {
        fun latest(n: PathNode): Instant? = n.finish ?: n.children.mapNotNull(::latest).maxOrNull()
        val end = latest(node) ?: return false
        return end.plusSeconds(DAY_SECONDS) < now
    }

    /** "6 Oct – 17 Oct" in [now]'s year, with the years otherwise; null without dates. */
    fun dates(node: PathNode, now: Instant, locale: Locale): String? {
        val start = node.start ?: return null
        val finish = node.finish ?: return null
        val year = now.atOffset(ZoneOffset.UTC).year
        val sameYear = start.atOffset(ZoneOffset.UTC).year == year && finish.atOffset(ZoneOffset.UTC).year == year
        // Azure DevOps keeps the days at midnight UTC: shown in UTC so they never move by one
        val format = DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy", locale).withZone(ZoneOffset.UTC)
        return "${format.format(start).trimEnd('.')} – ${format.format(finish).trimEnd('.')}"
    }

    /** The nodes from the root down to [path]'s, [path]'s included; null when it is not in the tree. */
    fun lineage(root: PathNode, path: String): List<PathNode>? {
        if (root.path.equals(path, ignoreCase = true)) return listOf(root)
        root.children.forEach { child -> lineage(child, path)?.let { return listOf(root) + it } }
        return null
    }

    /** All the nodes, depth first. */
    fun all(root: PathNode): List<PathNode> = listOf(root) + root.children.flatMap(::all)

    /** The key of [parent]'s past sprints line in the expanded set: never a path. */
    fun pastKey(parent: String): String = "\u0000past:$parent"

    /**
     * The children of [node] gathered as past, with [now]: those over, when some are not (a
     * folder all over is muted itself and lists its sprints as they are).
     */
    fun pastChildren(node: PathNode, now: Instant?): List<PathNode> {
        if (now == null) return emptyList()
        val past = node.children.filter { isPast(it, now) }
        return if (past.size == node.children.size) emptyList() else past
    }

    /**
     * Open at first: the root, and the way to the [selected] node and to the current iterations
     * (the nodes themselves stay closed), past sprints lines on that way included.
     */
    fun initiallyExpanded(root: PathNode, selected: String, now: Instant?): Set<String> {
        val targets = listOf(selected) + (now?.let { n -> all(root).filter { isCurrent(it, n) }.map { it.path } }.orEmpty())
        val out = mutableSetOf(root.path)
        targets.forEach { target ->
            val way = lineage(root, target) ?: return@forEach
            way.zipWithNext().forEach { (parent, child) ->
                out += parent.path
                if (child in pastChildren(parent, now)) out += pastKey(parent.path)
            }
        }
        return out
    }

    /** Null for an empty [query]. */
    fun search(root: PathNode, query: String): PathSearch? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val matches = mutableSetOf<String>()
        val visible = mutableSetOf<String>()
        fun walk(node: PathNode, ancestors: List<PathNode>) {
            if (node.name.contains(q, ignoreCase = true)) {
                matches += node.path
                visible += node.path
                ancestors.forEach { visible += it.path }
            }
            node.children.forEach { walk(it, ancestors + node) }
        }
        walk(root, emptyList())
        return PathSearch(q, matches, visible)
    }

    /**
     * The lines shown: the children of the [expanded] nodes, those over before [now] gathered
     * first in a line of their own, or while searching the matches with their ancestors, all
     * open and nothing gathered.
     */
    fun rows(root: PathNode, expanded: Set<String>, search: PathSearch?, now: Instant? = null): List<PathLine> {
        val out = mutableListOf<PathLine>()
        fun walk(node: PathNode, level: Int) {
            if (search != null && node.path !in search.visible) return
            val children = if (search != null) node.children.filter { it.path in search.visible } else node.children
            val open = children.isNotEmpty() && (search != null || node.path in expanded)
            out += PathRow(node, level, expandable = children.isNotEmpty(), open = open, match = search?.matches?.contains(node.path) == true)
            if (!open) return
            val past = if (search == null) pastChildren(node, now) else emptyList()
            if (past.isNotEmpty()) {
                val pastOpen = pastKey(node.path) in expanded
                out += PastRow(node.path, past.size, level + 1, pastOpen)
                if (pastOpen) past.forEach { walk(it, level + 2) }
            }
            children.filter { it !in past }.forEach { walk(it, level + 1) }
        }
        walk(root, 0)
        return out
    }
}
