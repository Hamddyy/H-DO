package com.hdo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-app update: downloads the release APK from GitHub, then hands it to Android's installer. */
object Updater {
    fun dir(ctx: Context) = File(ctx.cacheDir, "updates")

    /** Blocking; call from Dispatchers.IO. Returns the downloaded APK, or throws. */
    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val d = dir(ctx); d.mkdirs(); d.listFiles()?.forEach { it.delete() }
        val out = File(d, "H-DO-update.apk")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
        }
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        var done = 0L
        c.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        if (total > 0 && done != total) { out.delete(); throw IllegalStateException("incomplete download") }
        if (done < 1_000_000) { out.delete(); throw IllegalStateException("file too small") }
        return out
    }

    /** True when the installer was opened; false when the user must first allow installs from H-DO. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
