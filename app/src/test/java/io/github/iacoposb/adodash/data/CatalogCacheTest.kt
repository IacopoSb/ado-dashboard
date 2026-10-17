package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.Fakes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, "catalog.json")
    private fun cache() = CatalogCache(file)

    @Test fun `a saved catalog reads back as it was, also from a new instance`() = runTest {
        cache().saveCatalog("contoso", Fakes.catalog, at = 1_000)
        cache().saveUsedTypes("contoso", setOf("Bug", "Task"))
        val read = cache().load("contoso")!!
        assertEquals(Fakes.catalog, read.catalog)
        assertEquals(1_000L, read.savedAt)
        assertEquals(setOf("Bug", "Task"), read.usedTypes)
    }

    @Test fun `the organization is matched without case`() = runTest {
        cache().saveCatalog("Contoso", Fakes.catalog, at = 1)
        assertEquals(Fakes.catalog, cache().load("contoso")?.catalog)
    }

    @Test fun `another organization finds nothing, and saving for it drops the old one`() = runTest {
        val cache = cache()
        cache.saveCatalog("contoso", Fakes.catalog, at = 1)
        cache.saveUsedTypes("contoso", setOf("Bug"))
        assertNull(cache.load("fabrikam"))

        cache.saveUsedTypes("fabrikam", setOf("Task"))
        assertNull(cache.load("contoso"))
        val fabrikam = cache.load("fabrikam")!!
        assertNull(fabrikam.catalog)
        assertEquals(setOf("Task"), fabrikam.usedTypes)
    }

    @Test fun `used types come before the catalog and stay when it is saved`() = runTest {
        val cache = cache()
        cache.saveUsedTypes("contoso", setOf("Epic"))
        assertNull(cache.load("contoso")!!.catalog)
        cache.saveCatalog("contoso", Fakes.catalog, at = 5)
        assertEquals(setOf("Epic"), cache.load("contoso")!!.usedTypes)
    }

    @Test fun `cleared or unreadable, nothing is found`() = runTest {
        val cache = cache()
        cache.saveCatalog("contoso", Fakes.catalog, at = 1)
        cache.clear()
        assertNull(cache.load("contoso"))

        file.writeText("{not json")
        assertNull(cache.load("contoso"))
        // And it can be written again over the broken file
        cache.saveCatalog("contoso", Fakes.catalog, at = 2)
        assertEquals(2L, cache.load("contoso")!!.savedAt)
    }
}
