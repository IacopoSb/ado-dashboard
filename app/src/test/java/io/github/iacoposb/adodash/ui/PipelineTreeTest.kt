package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTreeTest {
    private fun p(id: Int, name: String, folder: String = "\\") = Pipeline(id, name, folder, "Contoso")

    /** As the API lists them: by name, folders mixed in, one folder spelled two ways. */
    private val pipelines = listOf(
        p(1, "backend-ci", "\\Backend"),
        p(2, "backend-deploy", "\\Backend\\Release"),
        p(3, "Backend-nightly", "\\backend"),
        p(4, "docs"),
        p(5, "infra", ""),
        p(6, "web-ci", "\\Frontend\\"),
    )
    private val tree = PipelineTree.of(pipelines)

    private fun List<PipelineLine>.names() = map {
        when (it) {
            is FolderLine -> "${it.folder.name}/${it.count}${if (it.open) "+" else ""}"
            is PipelineItem -> it.pipeline.name
        }
    }

    @Test fun `folders first, then pipelines, both by name, any case or slashes`() {
        assertEquals(listOf("Backend", "Frontend"), tree.folders.map { it.name })
        assertEquals(listOf("docs", "infra"), tree.pipelines.map { it.name })
        val backend = tree.folders[0]
        assertEquals("\\Backend", backend.path)
        assertEquals(listOf("backend-ci", "Backend-nightly"), backend.pipelines.map { it.name })
        assertEquals("\\Backend\\Release", backend.folders.single().path)
        assertEquals(3, backend.count)
        assertEquals(6, tree.count)
        assertEquals("\\backend\\release", PipelineTree.normalize("\\BACKEND\\Release\\"))
        assertEquals("\\", PipelineTree.normalize(""))
    }

    @Test fun `folders closed until expanded`() {
        assertEquals(listOf("Backend/3", "Frontend/1", "docs", "infra"), PipelineTree.rows(tree, emptySet(), null).names())
        val open = PipelineTree.rows(tree, setOf("\\backend"), null)
        assertEquals(listOf("Backend/3+", "Release/1", "backend-ci", "Backend-nightly", "Frontend/1", "docs", "infra"), open.names())
        assertEquals(listOf(0, 1, 1, 1, 0, 0, 0), open.map { it.level })
        val deeper = PipelineTree.rows(tree, setOf("\\backend", "\\backend\\release"), null)
        assertEquals(listOf(0, 1, 2, 1, 1, 0, 0, 0), deeper.map { it.level })
        assertEquals(deeper.size, deeper.map { it.key }.toSet().size)
    }

    @Test fun `search by name shows the matches with their folders, open`() {
        val matches = PipelineTree.matches(tree, " BACKEND-")!!
        assertEquals(setOf(1, 2, 3), matches)
        assertEquals(
            listOf("Backend/3+", "Release/1+", "backend-deploy", "backend-ci", "Backend-nightly"),
            PipelineTree.rows(tree, emptySet(), matches).names(),
        )
        // Folders' names are not searched; counts are of the matches
        assertEquals(listOf("Backend/1+", "Release/1+", "backend-deploy"), PipelineTree.rows(tree, emptySet(), PipelineTree.matches(tree, "deploy")).names())
        assertTrue(PipelineTree.rows(tree, emptySet(), PipelineTree.matches(tree, "frontend")).isEmpty())
        assertNull(PipelineTree.matches(tree, "  "))
    }

    @Test fun `no pipelines, no lines`() {
        assertTrue(PipelineTree.rows(PipelineTree.of(emptyList()), emptySet(), null).isEmpty())
    }
}
