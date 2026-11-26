package com.hdo.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-app update: downloads the release APK from GitHub, then hands it to Android's installer. */
object Updater {
    fun dir(ctx: Context) = File(ctx.filesDir, "updates")   // filesDir, not cache: Android may clear cache while the user grants install permission

    /** Blocking; call from Dispatchers.IO. Returns the downloaded APK, or throws. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hdo_update", Context.MODE_PRIVATE)
    fun skippedTag(ctx: Context): String = prefs(ctx).getString("skip", "") ?: ""
    fun skip(ctx: Context, tag: String) { prefs(ctx).edit().putString("skip", tag).apply(); cleanup(ctx, null) }

    private const val REL = "Download/H-DO/updates/"
    private val DL = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    private fun name(tag: String) = "H-DO-$tag.apk"

    /** Update files this app put in Downloads/H-DO/updates: (uri, display name, size). */
    private fun listAll(ctx: Context): List<Triple<Uri, String, Long>> {
        val out = ArrayList<Triple<Uri, String, Long>>()
        runCatching {
            ctx.contentResolver.query(DL, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.IS_PENDING + "=0", arrayOf(REL), null)?.use { c ->
                while (c.moveToNext()) out.add(Triple(ContentUris.withAppendedId(DL, c.getLong(0)), c.getString(1) ?: "", c.getLong(2)))
            }
        }
        return out
    }

    /** The finished download for [tag] (in Downloads/H-DO/updates, or the private fallback), or null. */
    fun cached(ctx: Context, tag: String): Uri? {
        listAll(ctx).firstOrNull { it.second == name(tag) && it.third > 1_000_000 }?.let { return it.first }
        return File(dir(ctx), name(tag)).takeIf { it.isFile && it.length() > 1_000_000 }?.let { Uri.fromFile(it) }
    }

    /** Deletes every downloaded update except the one for [keepTag] (null = delete all). */
    fun cleanup(ctx: Context, keepTag: String?) {
        listAll(ctx).forEach { if (keepTag == null || it.second != name(keepTag)) runCatching { ctx.contentResolver.delete(it.first, null, null) } }
        dir(ctx).listFiles()?.forEach { if (keepTag == null || it.name != name(keepTag)) it.delete() }
    }

    /** Blocking; call from Dispatchers.IO. Saves into Downloads/H-DO/updates (one copy), falls back to app storage. */
    fun download(ctx: Context, tag: String, url: String, onProgress: (Float) -> Unit): Uri {
        cleanup(ctx, null)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
        }
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        val r = ctx.contentResolver
        val item = runCatching {
            r.insert(DL, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name(tag))
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, REL)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        }.getOrNull()
        var part: File? = null
        val out: java.io.OutputStream = (item?.let { runCatching { r.openOutputStream(it) }.getOrNull() }) ?: run {
            item?.let { runCatching { r.delete(it, null, null) } }
            val d = dir(ctx); d.mkdirs(); part = File(d, name(tag) + ".part"); part!!.outputStream()
        }
        val useItem = part == null
        var done = 0L
        try {
            c.inputStream.use { inp ->
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("incomplete download")
            if (done < 1_000_000) throw IllegalStateException("file too small")
        } catch (e: Throwable) {
            if (useItem) runCatching { r.delete(item!!, null, null) } else part?.delete()
            throw e
        }
        if (useItem) {
            r.update(item!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return item
        }
        val fin = File(dir(ctx), name(tag))
        if (!part!!.renameTo(fin)) { part!!.delete(); throw IllegalStateException("rename failed") }
        return Uri.fromFile(fin)
    }

    /** True when the installer was opened; false when the user must first allow installs from H-DO. */
    fun install(ctx: Context, apk: Uri): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = if (apk.scheme == "file") FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(apk.path!!)) else apk
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
