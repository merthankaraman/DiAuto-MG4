package com.andrerinas.openheadunit.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingDeque

/** App-owned history survives the very noisy BYD system log buffer. Producers never do disk I/O. */
internal object DiagnosticJournal {
    private const val MAX_FILE_BYTES = 1024 * 1024L
    private const val MAX_LINES = 6000
    private val pending = LinkedBlockingDeque<String>(2048)
    private val recent = ArrayDeque<String>()
    private val diskLock = Any()
    @Volatile private var directory: File? = null
    /** Off with the logs setting: nothing more is queued for disk. */
    @Volatile var enabled = false

    @Synchronized fun init(context: Context) {
        if (directory != null) return
        val dir = File(context.filesDir, "diagnostics")
        directory = dir
        Thread({
            while (true) {
                val line = try { pending.take() } catch (_: InterruptedException) { return@Thread }
                // Export still has the memory history when storage is full or unavailable.
                runCatching {
                    synchronized(diskLock) {
                        dir.mkdirs()
                        val current = File(dir, "current.log")
                        if (current.length() >= MAX_FILE_BYTES) {
                            val previous = File(dir, "previous.log")
                            previous.delete()
                            if (!current.renameTo(previous)) current.writeText("")
                        }
                        current.appendText(line + "\n", Charsets.UTF_8)
                    }
                }
            }
        }, "DiAuto-diagnostic-journal").apply { isDaemon = true; start() }
    }

    fun record(priority: Int, message: String) {
        if (directory == null || !enabled) return
        // DEBUG/VERBOSE can be many lines per frame; journaling them stalls the producer path
        // (redact + synchronized deque + disk queue) and shows up as lost FPS on the head unit.
        // Crash/diagnostic exports still get INFO and above, which is what we need.
        if (priority < Log.INFO) return
        // Keep the timestamp before queueing; avoid retaining credentials/payloads even on disk.
        val timestamp = System.currentTimeMillis()
        message.lineSequence().take(40).forEach { raw ->
            val safe = DiagnosticReportRedactor.redact(raw, "", "") ?: return@forEach
            val line = "$timestamp [$priority] $safe"
            synchronized(recent) {
                if (recent.size == MAX_LINES) recent.removeFirst()
                recent.addLast(line)
            }
            if (!pending.offer(line)) { pending.poll(); pending.offer(line) }
        }
    }

    /** Called on the export worker, never the UI thread. Include queued lines without a flush wait. */
    fun snapshot(): List<String> {
        val stored = synchronized(diskLock) {
            directory?.let { dir ->
                listOf("previous.log", "current.log").flatMap { name ->
                    runCatching { File(dir, name).readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
                }
            }.orEmpty()
        }
        val memory = synchronized(recent) { recent.toList() }
        return (stored + memory).distinct().takeLast(MAX_LINES)
    }
}
