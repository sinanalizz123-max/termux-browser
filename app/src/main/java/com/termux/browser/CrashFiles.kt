package com.termux.browser

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durable crash forensics. Written synchronously inside the uncaught-
 * exception handler (best effort, guarded), so the evidence survives the
 * death of the process that created it. Bounded: newest 5 files.
 */
object CrashFiles {
    const val DIR_NAME = "crashes"
    const val MAX_FILES = 5
    const val MAX_BYTES = 64 * 1024

    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")

    fun dir(root: File): File = File(root, DIR_NAME)

    fun write(root: File, summary: String, sections: Map<String, String>): File? {
        return runCatching {
            val dir = dir(root).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = File(dir, "crash-$stamp.txt")
            val body = buildString {
                appendLine("summary=$summary")
                appendLine("at=$stamp")
                for ((name, content) in sections) {
                    appendLine("--- $name ---")
                    appendLine(content.take(MAX_BYTES / 4))
                }
            }
            file.writeText(body.take(MAX_BYTES))
            prune(dir)
            file
        }.getOrNull()
    }

    fun list(root: File): List<String> {
        val dir = dir(root)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    /** Null when the name is unsafe or the file is absent. */
    fun read(root: File, name: String): String? {
        if (!SAFE_NAME.matches(name) || name.contains("..")) return null
        val file = File(dir(root), name)
        if (!file.isFile) return null
        return runCatching { file.readText().take(MAX_BYTES + 1024) }.getOrNull()
    }

    fun prune(root: File) {
        val dir = if (root.name == DIR_NAME && root.isDirectory) root else dir(root)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }
}
