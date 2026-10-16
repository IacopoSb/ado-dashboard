package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.api.AreaNode

enum class Check { ON, PARTIAL, OFF }

/**
 * The projects-and-areas tree of [Filters.areas] over the known [roots] (one per project).
 * A selected node stands for its whole subtree, so a node is shown checked when it or an
 * ancestor is selected, and a node whose children are all checked becomes selected itself.
 * Every change returns the smallest selection, null when every project is whole.
 */
class AreaSelection(val roots: List<AreaNode>, val selected: Set<String>?) {

    fun covers(path: String) = selected == null || selected.any { Filters.isUnder(path, it) }

    fun check(node: AreaNode): Check = when {
        covers(node.path) -> Check.ON
        selected.orEmpty().any { Filters.isUnder(it, node.path) } -> Check.PARTIAL
        else -> Check.OFF
    }

    /** Areas without sub-areas below [node]: what "x of y areas" counts. A bare project has none. */
    fun leaves(node: AreaNode): List<AreaNode> =
        if (node.children.isEmpty()) listOf(node) else node.children.flatMap(::leaves)

    fun areaCount(root: AreaNode) = if (root.children.isEmpty()) 0 else leaves(root).size

    fun selectedAreaCount(root: AreaNode) =
        if (root.children.isEmpty()) 0 else leaves(root).count { covers(it.path) }

    /** Projects with something selected. */
    val selectedProjects get() = roots.count { check(it) != Check.OFF }

    val selectedAreas get() = roots.sumOf(::selectedAreaCount)

    fun toggle(node: AreaNode): Set<String>? = set(listOf(node), check(node) != Check.ON)

    /** Checks or unchecks every node of [nodes] with its subtree, leaving the rest as it is. */
    fun set(nodes: List<AreaNode>, on: Boolean): Set<String>? = rebuild { path ->
        if (nodes.any { Filters.isUnder(path, it.path) }) on else covers(path)
    }

    /** Only [node], from a long press. */
    fun only(node: AreaNode): Set<String>? = rebuild { Filters.isUnder(it, node.path) }

    fun all(): Set<String>? = null

    fun none(): Set<String> = emptySet()

    private fun rebuild(wanted: (String) -> Boolean): Set<String>? {
        fun whole(node: AreaNode): Boolean =
            if (node.children.isEmpty()) wanted(node.path) else node.children.all(::whole)
        fun collect(node: AreaNode): List<String> =
            if (whole(node)) listOf(node.path) else node.children.flatMap(::collect)
        if (roots.isNotEmpty() && roots.all(::whole)) return null
        return roots.flatMap(::collect).toSet()
    }

    /**
     * Nodes whose name contains [query], with the ancestors that lead to them; null for no
     * search. The ancestors are shown expanded, the matches as they are.
     */
    fun search(query: String): SearchResult? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val matches = mutableListOf<AreaNode>()
        val visible = mutableSetOf<String>()
        fun walk(node: AreaNode, ancestors: List<AreaNode>) {
            if (node.name.contains(q, ignoreCase = true)) {
                matches += node
                visible += node.path
                ancestors.forEach { visible += it.path }
            }
            node.children.forEach { walk(it, ancestors + node) }
        }
        roots.forEach { walk(it, emptyList()) }
        return SearchResult(q, matches, visible)
    }

    data class SearchResult(val query: String, val matches: List<AreaNode>, val visible: Set<String>) {
        fun matchesUnder(root: AreaNode) = matches.count { Filters.isUnder(it.path, root.path) }
        fun isMatch(node: AreaNode) = matches.any { it.path == node.path }
    }
}
