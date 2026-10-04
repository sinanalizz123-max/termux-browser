package com.termux.browser

import java.io.File

/**
 * Crash-safe plugin storage, keyed per provider id. Activation protocol:
 * 1. Files land in a uniquely-named staging directory (never the live one).
 * 2. Everything is validated there (schema + signature + hashes).
 * 3. Staging is renamed into place, then a tiny per-id pointer file is
 *    swapped atomically (same-directory rename). The previous generation
 *    is retained.
 * 4. On every read, the active generation is re-validated; a broken one
 *    falls back to the previous known-good generation.
 * A crash at any stage leaves either the old plugin active or no change.
 */
class PluginStore(private val root: File) {

    companion object {
        private val SAFE_ID = Regex("[a-z0-9-]{1,32}")
    }

    data class Pointer(val id: String, val version: Int, val dir: String)

    private fun versionsDir(): File = File(root, "versions")
    private fun activeFile(id: String): File = File(root, "active-$id.json")
    private fun previousFile(id: String): File = File(root, "previous-$id.json")

    private fun checkId(id: String) {
        require(SAFE_ID.matches(id)) { "bad plugin id" }
    }

    /**
     * Pre-activation gate: downgrades and duplicate versions are rejected
     * before any filesystem change. Explicit rollback is the only path
     * back to an older version.
     */
    @Synchronized
    fun canInstall(bundleId: String, version: Int): String? {
        checkId(bundleId)
        val active = readPointer(bundleId)
        if (active != null && version <= active.version) {
            return "downgrade rejected"
        }
        val target = File(versionsDir(), "$bundleId-v$version")
        if (target.exists()) return "version already installed"
        return null
    }

    /**
     * Activates a fully validated staging directory: renames it into place,
     * swaps the pointer, retains the previous generation.
     */
    @Synchronized
    fun activate(bundleId: String, version: Int, stagedDir: File): String? {
        checkId(bundleId)
        versionsDir().mkdirs()
        val target = File(versionsDir(), "$bundleId-v$version")
        if (target.exists()) return "version already installed"
        if (!stagedDir.renameTo(target)) return "activation failed"
        val previous = readPointer(bundleId)
        if (previous != null) {
            runCatching { previousFile(bundleId).writeText(pointerJson(previous)) }
        }
        writePointer(bundleId, Pointer(bundleId, version, target.name))
        pruneOld(bundleId, version)
        return null
    }

    @Synchronized
    fun rollback(bundleId: String): String? {
        checkId(bundleId)
        val previous = readPointerFile(previousFile(bundleId)) ?: return "nothing to roll back to"
        val dir = File(versionsDir(), previous.dir)
        if (!dir.isDirectory) return "previous generation missing"
        writePointer(bundleId, previous)
        return null
    }

    /** All active generations, each re-validated (broken falls back). */
    @Synchronized
    fun active(): List<Pointer> {
        val ids = root.listFiles()
            ?.filter { it.isFile && it.name.startsWith("active-") && it.name.endsWith(".json") }
            ?.mapNotNull {
                it.name.removePrefix("active-").removeSuffix(".json")
                    .takeIf { id -> SAFE_ID.matches(id) }
            } ?: return emptyList()
        return ids.mapNotNull { activeFor(it) }
    }

    @Synchronized
    fun activeFor(bundleId: String): Pointer? {
        checkId(bundleId)
        val pointer = readPointer(bundleId) ?: return null
        val dir = File(versionsDir(), pointer.dir)
        if (!dir.isDirectory || !File(dir, "manifest.json").isFile) {
            val previous = readPointerFile(previousFile(bundleId))
            if (previous != null && File(versionsDir(), previous.dir).isDirectory) {
                writePointer(bundleId, previous)
                return previous
            }
            return null
        }
        return pointer
    }

    fun versionDir(pointer: Pointer): File = File(versionsDir(), pointer.dir)

    /** Unique staging directory for one install attempt. Caller cleans up. */
    fun newStaging(): File {
        val dir = File(root, "staging-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun readPointer(bundleId: String): Pointer? =
        readPointerFile(activeFile(bundleId))

    private fun readPointerFile(file: File): Pointer? {
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                ?: return null
            val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
                ?.toIntOrNull() ?: return null
            val dir = Regex("\"dir\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                ?: return null
            if (dir.contains("/") || dir.contains("..")) return null
            if (!SAFE_ID.matches(id)) return null
            Pointer(id, version, dir)
        }.getOrNull()
    }

    private fun pointerJson(pointer: Pointer): String =
        """{"id":"${pointer.id}","version":${pointer.version},"dir":"${pointer.dir}"}"""

    private fun writePointer(bundleId: String, pointer: Pointer) {
        val target = activeFile(bundleId)
        val tmp = File(root, "${target.name}.tmp")
        tmp.writeText(pointerJson(pointer))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            runCatching { tmp.delete() }
        }
    }

    private fun pruneOld(bundleId: String, keepVersion: Int) {
        versionsDir().listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("$bundleId-v") && it.name != "$bundleId-v$keepVersion" }
            ?.sortedByDescending { it.name }
            ?.drop(1)
            ?.forEach { dir -> runCatching { dir.deleteRecursively() } }
    }
}
