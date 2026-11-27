package com.vscodroid.updates

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * VSCandro update source (added in this fork). Reads the releases of the fork's own GitHub
 * repository. It only runs when the user opens the Updates screen and taps Check; nothing
 * here runs in the background, and nothing is downloaded without a tap.
 */
data class UpdateRelease(
    val tag: String,
    val number: Int,
    val title: String,
    val notes: String,
    val date: String,
    val apkUrl: String,
    val apkSize: Long,
    val shaUrl: String?,
)

object UpdateChecker {
    const val REPO = "ONRIR/VSCandro"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** Releases tagged build-N whose number is above [installed], oldest first, so the last one is the newest and the recommended one. */
    @Throws(IOException::class)
    fun newerThan(installed: Int): List<UpdateRelease> {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "VSCandro")
            val code = conn.responseCode
            if (code != 200) throw IOException("GitHub answered HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(text).filter { it.number > installed }.sortedBy { it.number }
        } finally {
            conn.disconnect()
        }
    }

    private const val UPSTREAM_API = "https://api.github.com/repos/rmyndharis/VSCodroid/releases?per_page=30"

    private fun versionParts(v: String): List<Int> =
        v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    private fun isNewer(a: String, b: String): Boolean {
        val x = versionParts(a)
        val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /**
     * Releases by the original developer (tags like v1.12.0) newer than [installedVersion].
     * Information only: their APKs are signed with another key and lack this app's changes,
     * so they are listed with their notes but never offered for download here.
     */
    @Throws(IOException::class)
    fun upstreamNewer(installedVersion: String): List<UpdateRelease> {
        val conn = URL(UPSTREAM_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "VSCandro")
            if (conn.responseCode != 200) throw IOException("GitHub answered HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<UpdateRelease>()
            val re = Regex("^v\\d+\\.\\d+\\.\\d+$")
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (r.optBoolean("draft") || r.optBoolean("prerelease") || !re.matches(tag)) continue
                if (!isNewer(tag, installedVersion)) continue
                out.add(UpdateRelease(tag, 0, r.optString("name").ifBlank { tag }, r.optString("body").trim(),
                    r.optString("published_at").take(10), "", 0L, null))
            }
            return out.sortedWith(Comparator { a, b -> if (isNewer(a.tag, b.tag)) 1 else if (isNewer(b.tag, a.tag)) -1 else 0 })
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: String): List<UpdateRelease> {
        val out = ArrayList<UpdateRelease>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.optString("tag_name")
            val number = tag.removePrefix("build-").toIntOrNull() ?: continue
            if (!tag.startsWith("build-")) continue
            var apkUrl: String? = null
            var apkSize = 0L
            var shaUrl: String? = null
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (name.endsWith(".apk") && apkUrl == null) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size")
                } else if (name.endsWith(".apk.sha256")) {
                    shaUrl = a.optString("browser_download_url")
                }
            }
            val url = apkUrl
            if (url.isNullOrEmpty()) continue
            out.add(
                UpdateRelease(
                    tag = tag,
                    number = number,
                    title = r.optString("name").ifBlank { tag },
                    notes = r.optString("body").trim(),
                    date = r.optString("published_at").take(10),
                    apkUrl = url,
                    apkSize = apkSize,
                    shaUrl = shaUrl,
                )
            )
        }
        return out
    }
}
