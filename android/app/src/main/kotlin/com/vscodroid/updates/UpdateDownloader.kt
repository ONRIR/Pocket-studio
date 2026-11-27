package com.vscodroid.updates

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads one APK to [dest]. The partial file is kept as `<dest>.part`, so a download that was
 * interrupted (connection lost, app closed, user paused) continues from where it stopped with an
 * HTTP Range request instead of starting over. Only runs when the user taps Download or Resume.
 */
object UpdateDownloader {
    class Cancelled : IOException("paused")

    fun partFile(dest: File) = File(dest.path + ".part")

    @Throws(IOException::class)
    fun download(
        release: UpdateRelease,
        dest: File,
        cancel: AtomicBoolean,
        onProgress: (Long) -> Unit,
    ) {
        if (dest.exists() && dest.length() == release.apkSize) {
            onProgress(release.apkSize)
            return
        }
        dest.parentFile?.mkdirs()
        val part = partFile(dest)
        var have = if (part.exists()) part.length() else 0L
        if (have > release.apkSize && release.apkSize > 0) { part.delete(); have = 0L }

        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "VSCodroid Neo")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code == 200 && have > 0) { part.delete(); have = 0L }
            if (code != 200 && code != 206) {
                if (code == 416) { part.delete() }
                throw IOException("The download server answered HTTP $code")
            }
            var done = have
            FileOutputStream(part, have > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancel.get()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 300) { lastReport = now; onProgress(done) }
                    }
                }
            }
            onProgress(done)
        } finally {
            conn.disconnect()
        }
        if (release.apkSize > 0 && part.length() != release.apkSize) {
            throw IOException("The download stopped early (${part.length()} of ${release.apkSize} bytes). Tap Resume.")
        }
        verify(release, part)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not save the downloaded file")
    }

    /** Checks the file against the release's published SHA-256, when the release has one. */
    private fun verify(release: UpdateRelease, file: File) {
        val url = release.shaUrl ?: return
        val expected: String? = try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "VSCodroid Neo")
                if (c.responseCode != 200) null
                else c.inputStream.bufferedReader().use { it.readText() }.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            null
        }
        if (expected == null || expected.length != 64) return
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IOException("The downloaded file did not match its checksum, so it was deleted. Try again.")
        }
    }
}
