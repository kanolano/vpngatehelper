package com.tb.vpngate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists the last successfully fetched feed as raw CSV.
 *
 * This is intentionally a plain file rather than a database. The feed is ~100 rows that are
 * replaced wholesale on every refresh: there are no partial updates, no queries and no
 * migrations, so a schema and a code generator would buy nothing. The file's own mtime is the
 * "last updated" timestamp, which avoids keeping a second source of truth in sync.
 *
 * Construct with [inDirectory] rather than passing a path directly -- a single-File constructor
 * reads ambiguously at the call site ("is that the file or the folder?") and silently treating a
 * directory as the target makes every write fail with no signal.
 */
class ServerCache internal constructor(private val file: File) {

    suspend fun read(): Cached? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) return@withContext null
        runCatching { Cached(file.readText(), file.lastModified()) }.getOrNull()
    }

    /** Returns false if the feed could not be persisted; the caller carries on regardless. */
    suspend fun write(csv: String): Boolean = withContext(Dispatchers.IO) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return@withContext false

        // Write-then-rename so an interrupted write cannot leave a truncated feed behind that
        // would later parse as a short, plausible-looking server list.
        val tmp = File(parent, "${file.name}.tmp")
        runCatching {
            tmp.writeText(csv)
            if (!tmp.renameTo(file)) {
                // Rename can fail across some filesystems; fall back to a direct write.
                file.writeText(csv)
            }
            true
        }.getOrElse {
            false
        }.also {
            if (tmp.exists()) tmp.delete()
        }
    }

    data class Cached(val csv: String, val fetchedAtMillis: Long)

    companion object {
        private const val DEFAULT_NAME = "servers.csv"

        /** Caches into [directory], which is created if it does not exist yet. */
        fun inDirectory(directory: File, name: String = DEFAULT_NAME): ServerCache =
            ServerCache(File(directory, name))
    }
}
