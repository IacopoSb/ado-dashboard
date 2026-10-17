package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.Pipeline

/**
 * A folder of a project's pipelines, as Azure DevOps' "All" view shows them: [path] "\\" for
 * the root, "\\Backend\\CI" below it; its folders first, then its pipelines, both by name.
 */
data class PipelineFolder(
    val name: String,
    val path: String,
    val folders: List<PipelineFolder> = emptyList(),
    val pipelines: List<Pipeline> = emptyList(),
) {
    /** The pipelines in it and in its folders. */
    val count: Int get() = pipelines.size + folders.sumOf { it.count }
}

/** A line of the pipelines list: a folder or a pipeline, [level] deep below the root. */
sealed interface PipelineLine {
    val level: Int
    val key: String
}

/** [open] when its content is shown below it; [count] its pipelines, or those matching. */
data class FolderLine(val folder: PipelineFolder, override val level: Int, val open: Boolean, val count: Int) : PipelineLine {
    override val key: String get() = "f:${PipelineTree.normalize(folder.path)}"
}

data class PipelineItem(val pipeline: Pipeline, override val level: Int) : PipelineLine {
    override val key: String get() = "p:${pipeline.id}"
}

/** The pipelines of a project in their folders: building, showing and searching them. Pure. */
object PipelineTree {
    const val ROOT = "\\"

    /** "\\Backend\\CI", whatever the case and the slashes around it: the folder's identity. */
    fun normalize(path: String): String =
        ROOT + path.split('\\').filter(String::isNotBlank).joinToString("\\").lowercase()

    fun of(all: List<Pipeline>): PipelineFolder {
        class Builder(val name: String, val path: String) {
            val folders = linkedMapOf<String, Builder>()
            val pipelines = mutableListOf<Pipeline>()
        }
        val root = Builder("", ROOT)
        all.forEach { p ->
            val parts = p.folder.split('\\').filter(String::isNotBlank)
            var node = root
            parts.forEach { part ->
                // The first spelling met names the folder: Azure DevOps ignores the case
                node = node.folders.getOrPut(part.lowercase()) {
                    Builder(part, if (node === root) "$ROOT$part" else "${node.path}\\$part")
                }
            }
            node.pipelines += p
        }
        val byName = String.CASE_INSENSITIVE_ORDER
        fun Builder.build(): PipelineFolder = PipelineFolder(
            name, path,
            folders.values.sortedWith(compareBy(byName) { it.name }).map { it.build() },
            pipelines.sortedWith(compareBy(byName) { it.name }),
        )
        return root.build()
    }

    /**
     * The folder of [team]'s pipelines, at any depth ("\\PTD", "\\Team\\PTD"): named as the team
     * (case, spaces and punctuation aside), else a word of the team's name ("PTD" for "PTD
     * Team"), else one whose name contains the team's ("PTD pipelines"). The shallowest first.
     */
    fun teamFolder(root: PipelineFolder, team: String?): PipelineFolder? {
        val name = squash(team ?: return null)
        if (name.isEmpty()) return null
        val words = team.split(Regex("[^\\p{L}\\p{N}]+")).map(::squash).filter { it.length >= 2 }.toSet()
        val all = mutableListOf<Pair<PipelineFolder, Int>>()
        walkSub(root, 0, all)
        fun tier(f: PipelineFolder): Int? {
            val folder = squash(f.name)
            return when {
                folder == name -> 0
                folder in words -> 1
                name.length >= 2 && folder.contains(name) -> 2
                else -> null
            }
        }
        return all.mapNotNull { (f, depth) -> tier(f)?.let { Triple(f, it, depth) } }
            .minWithOrNull(compareBy<Triple<PipelineFolder, Int, Int>>({ it.second }, { it.third }, { it.first.path.lowercase() }))
            ?.first
    }

    /** Every folder below [f] with its depth, parents first. */
    private fun walkSub(f: PipelineFolder, depth: Int, out: MutableList<Pair<PipelineFolder, Int>>) {
        f.folders.forEach { sub ->
            out += sub to depth
            walkSub(sub, depth + 1, out)
        }
    }

    /** The [normalize]d paths that show [folder] open: its own and those above it. */
    fun opening(folder: PipelineFolder): Set<String> {
        val parts = folder.path.split('\\').filter(String::isNotBlank)
        return (1..parts.size).map { n -> normalize(parts.take(n).joinToString("\\")) }.toSet()
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The ids of the pipelines whose name contains [query]; null for an empty one. */
    fun matches(root: PipelineFolder, query: String): Set<Int>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        fun walk(f: PipelineFolder): List<Int> =
            f.pipelines.filter { it.name.contains(q, ignoreCase = true) }.map { it.id } + f.folders.flatMap(::walk)
        return walk(root).toSet()
    }

    /**
     * The lines shown: the root's content, and that of the [expanded] folders (by
     * [normalize]d path); while searching only the [matches] with their folders, all open.
     */
    fun rows(root: PipelineFolder, expanded: Set<String>, matches: Set<Int>?): List<PipelineLine> {
        val out = mutableListOf<PipelineLine>()
        fun matching(f: PipelineFolder): Int =
            if (matches == null) f.count else f.pipelines.count { it.id in matches } + f.folders.sumOf(::matching)
        fun walk(f: PipelineFolder, level: Int) {
            f.folders.forEach { sub ->
                val count = matching(sub)
                if (matches != null && count == 0) return@forEach
                val open = matches != null || normalize(sub.path) in expanded
                out += FolderLine(sub, level, open, count)
                if (open) walk(sub, level + 1)
            }
            f.pipelines.forEach { p -> if (matches == null || p.id in matches) out += PipelineItem(p, level) }
        }
        walk(root, 0)
        return out
    }
}
