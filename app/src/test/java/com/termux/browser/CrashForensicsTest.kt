package com.termux.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `write list read round trip`() {
        val root = folder.newFolder()
        val file = CrashFiles.write(root, "boom", mapOf("stack" to "line1\nline2"))!!
        assertTrue(file.isFile)
        val names = CrashFiles.list(root)
        assertEquals(1, names.size)
        val text = CrashFiles.read(root, names[0])!!
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("line1"))
    }

    @Test
    fun `unsafe names are rejected`() {
        val root = folder.newFolder()
        assertNull(CrashFiles.read(root, "../evil"))
        assertNull(CrashFiles.read(root, "a/b"))
        assertNull(CrashFiles.read(root, "missing.txt"))
        assertTrue(CrashFiles.list(root).isEmpty())
    }

    @Test
    fun `only newest five survive`() {
        val root = folder.newFolder()
        repeat(8) {
            CrashFiles.write(root, "s$it", mapOf("x" to "y"))
            Thread.sleep(2)
        }
        assertEquals(CrashFiles.MAX_FILES, CrashFiles.list(root).size)
    }
}

class RecorderTest {

    @Test
    fun `disabled recorder captures nothing`() {
        Recorder.enabled = false
        Recorder.clear()
        Recorder.record("activity", "hello")
        assertTrue(Recorder.since(0).isEmpty())
    }

    @Test
    fun `enabled recorder replays and bounds`() {
        Recorder.enabled = true
        Recorder.clear()
        try {
            repeat(600) { Recorder.record("activity", "event number $it") }
            val all = Recorder.since(0)
            assertTrue(all.size <= 500)
            val tail = Recorder.since(all.first().id)
            assertTrue(tail.size == all.size - 1)
        } finally {
            Recorder.enabled = false
            Recorder.clear()
        }
    }
}
