package com.example.photossaver

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams photos from the NAS over plain HTTP. The NAS serves the photo folder with a
 * `manifest.txt` at its root — one relative image path per line. We fetch the manifest
 * (just the list), then download individual photos on demand, keeping a small rolling
 * cache so the whole library is eligible every time without a big upfront download.
 */
class PhotoFetcher(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, "photos").also { it.mkdirs() }

    // How many recently-shown photos to keep on disk as a fallback / smooth transitions.
    private val maxCached = 40

    private fun base(baseUrl: String) = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /**
     * Fetch the photo list. Each manifest line is either `relpath` or `relpath|YYYYMMDD`.
     * The date enables anniversary themes; when absent we derive year/month from the
     * YYYY/MM folder path so age/month themes still work.
     */
    fun fetchManifest(baseUrl: String): List<PhotoEntry> {
        val text = httpGetText("${base(baseUrl)}manifest.txt") ?: return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { parseEntry(it) }
            .toList()
    }

    private fun parseEntry(line: String): PhotoEntry {
        val bar = line.indexOf('|')
        val path = if (bar >= 0) line.substring(0, bar) else line
        var y = 0; var m = 0; var d = 0
        if (bar >= 0) {
            val ds = line.substring(bar + 1).trim()
            if (ds.length == 8 && ds.all { it.isDigit() }) {
                y = ds.substring(0, 4).toInt(); m = ds.substring(4, 6).toInt(); d = ds.substring(6, 8).toInt()
            }
        }
        if (y == 0) {   // fall back to the folder structure (YYYY/MM/...)
            val parts = path.split('/')
            parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1900..2100 }?.let { y = it }
            parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 }?.let { m = it }
        }
        return PhotoEntry(path, y, m, d)
    }

    /** Download a single photo on demand (or return it from cache). Trims the cache. */
    fun getPhoto(baseUrl: String, relPath: String): File? {
        val encoded = relPath.split("/").joinToString("/") { Uri.encode(it) }
        val url = base(baseUrl) + encoded
        val file = File(cacheDir, "${url.hashCode()}.img")
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())   // mark recently used
            return file
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "PhotosScreensaver/1.0")
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            if (conn.responseCode != 200) return null
            file.writeBytes(conn.inputStream.use { it.readBytes() })
            trimCache()
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.length() > 0 } ?: return
        if (files.size <= maxCached) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxCached)
            .forEach { it.delete() }
    }

    private fun httpGetText(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "PhotosScreensaver/1.0")
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) { null }

    fun getCachedPhotos(): List<File> =
        cacheDir.listFiles()?.filter { it.length() > 0 }?.toList() ?: emptyList()

    fun clearCache() = cacheDir.listFiles()?.forEach { it.delete() }

    /** Quick connectivity test for setup: pull a few photos. Returns how many succeeded. */
    fun testFetch(baseUrl: String, count: Int = 3): Int {
        val picks = fetchManifest(baseUrl).shuffled().take(count)
        return picks.count { getPhoto(baseUrl, it.path) != null }
    }
}

/** One photo from the manifest: its relative path and best-known capture date (0 = unknown). */
data class PhotoEntry(val path: String, val year: Int, val month: Int, val day: Int)
