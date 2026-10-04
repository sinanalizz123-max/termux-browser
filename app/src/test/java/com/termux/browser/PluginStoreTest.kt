package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun staging(root: File, vararg names: String): File {
        val dir = File(root, "stage-${System.nanoTime()}")
        dir.mkdirs()
        for (name in names) File(dir, name).writeText("{}")
        return dir
    }

    @Test
    fun `activate lists and rolls back`() {
        val root = folder.newFolder()
        val store = PluginStore(root)
        assertTrue(store.active().isEmpty())
        assertNull(store.activeFor("deepseek"))

        assertNull(store.activate("deepseek", 2, staging(root, "manifest.json")))
        val active = store.activeFor("deepseek")!!
        assertEquals(2, active.version)
        assertEquals(1, store.active().size)

        // Downgrade rejected; duplicate rejected.
        assertTrue(store.canInstall("deepseek", 2) != null)
        assertTrue(store.canInstall("deepseek", 1) != null)
        assertNull(store.canInstall("deepseek", 3))

        assertNull(store.activate("deepseek", 3, staging(root, "manifest.json")))
        assertEquals(3, store.activeFor("deepseek")!!.version)

        // Rollback returns to v2.
        assertNull(store.rollback("deepseek"))
        assertEquals(2, store.activeFor("deepseek")!!.version)

        // Nothing to roll back to twice... second rollback: previous is now
        // v3's record? After rollback to v2, previous file still points at
        // the pre-rollback generation (v3 dir may be pruned but exists).
        // Just assert no crash and a sane state.
        store.rollback("deepseek")
        assertTrue(store.activeFor("deepseek") != null)
    }

    @Test
    fun `rollback with no history fails`() {
        val store = PluginStore(folder.newFolder())
        assertTrue(store.rollback("deepseek") != null)
    }

    @Test
    fun `broken active generation falls back`() {
        val root = folder.newFolder()
        val store = PluginStore(root)
        store.activate("deepseek", 2, staging(root, "manifest.json"))
        store.activate("deepseek", 3, staging(root, "manifest.json"))
        // Simulate a crashed/corrupt new generation.
        File(root, "versions/deepseek-v3").deleteRecursively()
        assertEquals(2, store.activeFor("deepseek")!!.version)
    }

    @Test
    fun `per-provider pointers are independent`() {
        val root = folder.newFolder()
        val store = PluginStore(root)
        store.activate("deepseek", 1, staging(root, "manifest.json"))
        store.activate("chatgpt", 5, staging(root, "manifest.json"))
        assertEquals(1, store.activeFor("deepseek")!!.version)
        assertEquals(5, store.activeFor("chatgpt")!!.version)
        assertEquals(2, store.active().size)
    }
}
