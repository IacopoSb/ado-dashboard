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
