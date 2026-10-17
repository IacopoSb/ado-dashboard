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

    private fun teamPath(team: String?, vararg folders: String): String? =
        PipelineTree.teamFolder(PipelineTree.of(folders.mapIndexed { i, f -> p(i, "p$i", f) }), team)?.path

    @Test fun `the team's folder, whatever the case, at any depth`() {
        assertEquals("\\Backend", teamPath("backend", "\\Backend", "\\Frontend"))
        assertEquals("\\Team\\PTD", teamPath("PTD", "\\Team\\PTD", "\\Other"))
        // The shallowest wins
        assertEquals("\\PTD", teamPath("PTD", "\\Team\\PTD", "\\PTD"))
        // Spaces and punctuation aside
        assertEquals("\\Mobile-Apps", teamPath("Mobile Apps", "\\Mobile-Apps", "\\Mobile"))
    }

    @Test fun `a word of the team's name, or a folder containing it`() {
        assertEquals("\\PTD", teamPath("PTD Team", "\\PTD", "\\Other"))
        assertEquals("\\CI\\PTD pipelines", teamPath("PTD", "\\CI\\PTD pipelines", "\\Other"))
        // An exact name beats the rest, wherever it is
        assertEquals("\\A\\PTD", teamPath("PTD", "\\PTD-old", "\\A\\PTD"))
    }

    @Test fun `no team or no matching folder opens nothing`() {
        assertNull(teamPath(null, "\\Backend"))
        assertNull(teamPath("", "\\Backend"))
        assertNull(teamPath("Data", "\\Backend", "\\Frontend"))
        // A one-letter name matches only exactly
        assertNull(teamPath("A", "\\Alpha"))
    }

    @Test fun `opening a folder opens those above it`() {
        val nested = PipelineTree.teamFolder(PipelineTree.of(listOf(p(1, "x", "\\Team\\PTD\\CI"))), "CI")!!
        assertEquals(setOf("\\team", "\\team\\ptd", "\\team\\ptd\\ci"), PipelineTree.opening(nested))
        val rows = PipelineTree.rows(PipelineTree.of(listOf(p(1, "x", "\\Team\\PTD\\CI"))), PipelineTree.opening(nested), null)
        assertEquals(listOf("Team/1+", "PTD/1+", "CI/1+", "x"), rows.names())
    }
}
