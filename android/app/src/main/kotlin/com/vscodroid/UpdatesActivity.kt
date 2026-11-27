package com.vscodroid

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.vscodroid.updates.UpdateChecker
import com.vscodroid.updates.UpdateDownloader
import com.vscodroid.updates.UpdateRelease
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.padForSystemBars
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VSCodroid Neo updates screen (added in this fork).
 *
 * Never updates by itself. It asks GitHub for the fork's releases only when the user taps
 * "Check for updates", lists every newer build with its notes and the download size, and
 * downloads only when the user taps Download. A download that stops can be resumed. When it
 * finishes the user taps Install, which hands the file to Android's own installer (the same
 * signing key and app id mean it installs over the current app and keeps the data).
 */
class UpdatesActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancel = AtomicBoolean(false)
    private var downloading = false

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var checkButton: Button
    private var releases: List<UpdateRelease> = emptyList()
    private var upstream: List<UpdateRelease> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        title = "Updates"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            padForSystemBars(dp(16))
        }
        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
        }
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Updates"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Installed: ${BuildConfig.VERSION_NAME}" +
                (if (BuildConfig.BUILD_NUMBER > 0) " (build ${BuildConfig.BUILD_NUMBER})" else "")
            setPadding(0, dp(4), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "VSCodroid Neo never updates by itself and never checks in the background. " +
                "Tap the button below to look for new versions. You see the size before anything downloads."
            setPadding(0, 0, 0, dp(12))
        })
        checkButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { check() }
        }
        root.addView(checkButton)
        status = TextView(this).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
    }

    override fun onDestroy() {
        cancel.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    private fun check() {
        if (downloading) return
        checkButton.isEnabled = false
        status.text = "Checking…"
        list.removeAllViews()
        worker.execute {
            val result = try { Result.success(UpdateChecker.newerThan(BuildConfig.BUILD_NUMBER, try { assets.open("toolchains/toolchain_cpp.zip").close(); true } catch (e: java.io.IOException) { false })) } catch (e: Exception) { Result.failure<List<UpdateRelease>>(e) }
            val up = try { UpdateChecker.upstreamNewer(BuildConfig.VERSION_NAME.substringBefore('-')) } catch (e: Exception) { emptyList() }
            main.post {
                if (isFinishing || isDestroyed) return@post
                checkButton.isEnabled = true
                result.onSuccess { found ->
                    releases = found
                    upstream = up
                    status.text = if (found.isEmpty()) "You are up to date." else
                        if (found.size == 1) "1 new version, recommended." else "${found.size} new versions, oldest first. The newest is recommended and includes all the others."
                    render()
                }.onFailure { e ->
                    status.text = "Could not check: ${e.message ?: e.javaClass.simpleName}. Nothing was downloaded."
                }
            }
        }
    }

    private fun apkFile(r: UpdateRelease): File {
        val base = getExternalFilesDir(null) ?: cacheDir
        return File(File(base, "updates"), "VSCodroid-Neo-${r.number}.apk")
    }

    private fun render() {
        list.removeAllViews()
        releases.forEachIndexed { index, r ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val last = index == releases.size - 1
            card.addView(TextView(this).apply { text = r.title + (if (r.date.isNotEmpty()) "  ·  ${r.date}" else "") + (if (last) "  ·  Recommended" else ""); textSize = 17f })
            card.addView(TextView(this).apply {
                text = if (r.notes.isEmpty()) "No notes for this version." else r.notes
                setPadding(0, dp(4), 0, dp(8))
            })
            if (last) addDownloadControls(card, r)
            list.addView(card)
        }
        if (upstream.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "Released by the original developer (VSCodroid), not built for VSCodroid Neo yet. " +
                    "These are only listed so you can read what is new. They are not downloaded here, " +
                    "because the original builds carry another signature and none of our changes. " +
                    "Each one appears above as a normal update once VSCodroid Neo has been built from it."
                setPadding(0, dp(16), 0, dp(8))
            })
            upstream.forEach { u ->
                list.addView(TextView(this).apply {
                    text = u.title + (if (u.date.isNotEmpty()) "  ·  ${u.date}" else "") + "\n" +
                        (if (u.notes.isEmpty()) "No notes." else u.notes)
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
        }
    }

    private fun sizeText(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    private fun addDownloadControls(card: LinearLayout, r: UpdateRelease) {
        val file = apkFile(r)
        val part = UpdateDownloader.partFile(file)
        val ready = file.exists() && r.apkSize > 0 && file.length() == r.apkSize
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        val info = TextView(this)
        val button = Button(this)
        card.addView(info)
        card.addView(bar)
        card.addView(button)
        when {
            ready -> {
                info.text = "Downloaded (${sizeText(r.apkSize)}). Ready to install."
                button.text = "Install"
                button.setOnClickListener { install(file) }
            }
            else -> {
                val have = if (part.exists()) part.length() else 0L
                info.text = if (have > 0) "Download size ${sizeText(r.apkSize)}. ${sizeText(have)} already downloaded."
                else "Download size ${sizeText(r.apkSize)}."
                button.text = if (have > 0) "Resume download" else "Download ${sizeText(r.apkSize)}"
                button.setOnClickListener { confirmAndStart(r, file, info, bar, button) }
            }
        }
    }

    private fun confirmAndStart(r: UpdateRelease, file: File, info: TextView, bar: ProgressBar, button: Button) {
        if (downloading) return
        val have = UpdateDownloader.partFile(file).let { if (it.exists()) it.length() else 0L }
        val need = r.apkSize - have
        val free = (getExternalFilesDir(null) ?: cacheDir).usableSpace
        if (r.apkSize > 0 && free < need + 50L * 1024 * 1024) {
            Toast.makeText(this, "Not enough free storage: need ${sizeText(need)} more.", Toast.LENGTH_LONG).show()
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        val metered = try { cm?.isActiveNetworkMetered == true } catch (e: SecurityException) { false }
        if (metered) {
            AlertDialog.Builder(this)
                .setTitle("Download over mobile data?")
                .setMessage("You are on a metered connection. This will use up to ${sizeText(need)} of data. You can pause and resume.")
                .setPositiveButton("Download") { _, _ -> start(r, file, info, bar, button) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            start(r, file, info, bar, button)
        }
    }

    private fun start(r: UpdateRelease, file: File, info: TextView, bar: ProgressBar, button: Button) {
        downloading = true
        cancel.set(false)
        checkButton.isEnabled = false
        bar.visibility = View.VISIBLE
        button.text = "Pause"
        button.setOnClickListener { cancel.set(true) }
        info.text = "Downloading…"
        worker.execute {
            var error: Throwable? = null
            try {
                UpdateDownloader.download(r, file, cancel) { done ->
                    main.post {
                        if (r.apkSize > 0) bar.progress = (done * 1000 / r.apkSize).toInt()
                        info.text = "Downloading ${sizeText(done)} of ${sizeText(r.apkSize)}"
                    }
                }
            } catch (e: Throwable) {
                error = e
            }
            main.post {
                downloading = false
                if (isFinishing || isDestroyed) return@post
                checkButton.isEnabled = true
                bar.visibility = View.GONE
                val err = error
                if (err == null) {
                    info.text = "Downloaded (${sizeText(r.apkSize)}). Ready to install."
                    button.text = "Install"
                    button.setOnClickListener { install(file) }
                } else {
                    val have = UpdateDownloader.partFile(file).let { if (it.exists()) it.length() else 0L }
                    info.text = if (err is UpdateDownloader.Cancelled) "Paused. ${sizeText(have)} of ${sizeText(r.apkSize)} saved."
                    else "Stopped: ${err.message ?: err.javaClass.simpleName}. ${sizeText(have)} saved, it will continue from there."
                    button.text = "Resume download"
                    button.setOnClickListener { confirmAndStart(r, file, info, bar, button) }
                }
            }
        }
    }

    private fun install(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow VSCodroid Neo to install apps, then come back and tap Install again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start the installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
