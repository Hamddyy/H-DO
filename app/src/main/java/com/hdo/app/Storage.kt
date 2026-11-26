package com.hdo.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.content.FileProvider
import java.io.File

/**
 * Finished files live either in the public Downloads/H-DO folder (MediaStore, content:// uri)
 * or, when "private folder" is on, in the app's own folder with a .nomedia marker (file:// uri),
 * which keeps them out of the gallery and music apps.
 */
object Storage {
    fun privateDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "H-DO")
        d.mkdirs()
        val nm = File(d, ".nomedia")
        if (!nm.exists()) runCatching { nm.createNewFile() }
        return d
    }

    private fun subFolder(t: Task): String {
        val s = Store.settings.value
        val base = when (s.folderMode) {
            "flat" -> ""
            "site" -> Engine.sanitize(t.site.ifBlank { "Other" }, 40)
            "channel" -> Engine.sanitize(t.uploader.ifBlank { "Other" }, 40)
            else -> if (t.kind == "audio") "Audio" else "Video"
        }
        val extra = if (t.folder.isNotBlank()) Engine.sanitize(t.folder, 60) else ""
        return listOf(base, extra).filter { it.isNotBlank() }.joinToString("/")
    }

    /** Moves/copies a finished file to its final place and returns its uri string. */
    fun publish(ctx: Context, file: File, t: Task): String {
        val sub = subFolder(t)
        if (Store.settings.value.privateFolder) {
            val dir = if (sub.isBlank()) privateDir(ctx) else File(privateDir(ctx), sub).apply { mkdirs() }
            var dest = File(dir, file.name)
            var n = 1
            while (dest.exists()) { dest = File(dir, file.nameWithoutExtension + " (" + n++ + ")." + file.extension) }
            if (!file.renameTo(dest)) file.copyTo(dest, overwrite = false)
            return Uri.fromFile(dest).toString()
        }
        treeRoot(ctx)?.let { root -> publishTree(ctx, root, file, sub)?.let { return it } }
        val rel = Environment.DIRECTORY_DOWNLOADS + "/H-DO" + (if (sub.isBlank()) "" else "/$sub")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, Engine.mimeOf(file.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("cannot open output")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }


    // ---- user-chosen folder (Storage Access Framework) ----
    private fun isTreeDoc(u: Uri) = u.authority != MediaStore.AUTHORITY && DocumentsContract.isDocumentUri(appCtx, u)
    private lateinit var appCtx: Context

    /** The chosen folder as a document uri, or null when none is set or access was lost. */
    fun treeRoot(ctx: Context): Uri? {
        appCtx = ctx.applicationContext
        val s = Store.settings.value.saveTreeUri
        if (s.isBlank()) return null
        val tree = Uri.parse(s)
        val ok = ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!ok) return null
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { if (it.moveToFirst()) doc else null }
        } catch (e: Exception) { null }
    }

    /** Readable name of a chosen folder, e.g. "Phone storage/Movies/H-DO". */
    fun treeLabel(treeUri: String, lang: String): String = try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val vol = id.substringBefore(':'); val path = id.substringAfter(':', "")
        (if (vol == "primary") Strings.get("loc_phone", lang) else Strings.get("loc_sd", lang)) + (if (path.isBlank()) "" else "/$path")
    } catch (e: Exception) { treeUri }

    private fun childDir(ctx: Context, parent: Uri, name: String): Uri? {
        val r = ctx.contentResolver
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        r.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
        }
        return DocumentsContract.createDocument(r, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun publishTree(ctx: Context, root: Uri, file: File, sub: String): String? = try {
        var dir: Uri = root
        for (part in sub.split("/").filter { it.isNotBlank() }) dir = childDir(ctx, dir, part) ?: throw IllegalStateException("mkdir $part")
        val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, Engine.mimeOf(file.extension), file.name) ?: throw IllegalStateException("create failed")
        ctx.contentResolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("cannot open output")
        doc.toString()
    } catch (e: Exception) { Engine.logEvent(null, "Storage:tree", e.toString()); null }   // falls back to Downloads/H-DO

    private fun rootReachable(ctx: Context) = treeRoot(ctx) != null

    fun exists(ctx: Context, t: Task): Boolean = try {
        if (t.isPrivate) File(Uri.parse(t.uri).path ?: "").exists()
        else ctx.contentResolver.openFileDescriptor(Uri.parse(t.uri), "r")?.use { true } ?: false
    } catch (e: Exception) {
        // chosen folder (e.g. SD card removed) unreachable: do not drop the entry from the library
        !t.isPrivate && Uri.parse(t.uri).authority != MediaStore.AUTHORITY && !rootReachable(ctx)
    }

    fun delete(ctx: Context, t: Task) {
        try {
            if (t.isPrivate) File(Uri.parse(t.uri).path ?: "").delete()
            else if (Uri.parse(t.uri).authority != MediaStore.AUTHORITY) DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(t.uri))
            else ctx.contentResolver.delete(Uri.parse(t.uri), null, null)
        } catch (e: Exception) { Engine.logEvent(null, "Storage:74", e.toString()) }
    }

    /** A uri other apps can read (FileProvider for private files). */
    fun shareUri(ctx: Context, t: Task): Uri =
        if (t.isPrivate) FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(Uri.parse(t.uri).path ?: ""))
        else Uri.parse(t.uri)

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    /** @return the (possibly new) uri string, or null on failure. */
    fun rename(ctx: Context, t: Task, newTitle: String): String? = try {
        if (t.isPrivate) {
            val f = File(Uri.parse(t.uri).path ?: "")
            val dest = File(f.parentFile, Engine.sanitize(newTitle) + (if (f.extension.isNotEmpty()) "." + f.extension else ""))
            if (!dest.exists() && f.renameTo(dest)) Uri.fromFile(dest).toString() else null
        } else if (Uri.parse(t.uri).authority != MediaStore.AUTHORITY) {
            val uri = Uri.parse(t.uri)
            val ext0 = (displayName(ctx, uri) ?: "").substringAfterLast('.', "")
            DocumentsContract.renameDocument(ctx.contentResolver, uri, Engine.sanitize(newTitle) + if (ext0.isNotEmpty()) ".$ext0" else "")?.toString()
        } else {
            val uri = Uri.parse(t.uri)
            val ext = displayName(ctx, uri)?.substringAfterLast('.', "") ?: ""
            val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, Engine.sanitize(newTitle) + if (ext.isNotEmpty()) ".$ext" else "") }
            if (ctx.contentResolver.update(uri, v, null, null) > 0) t.uri else null
        }
    } catch (e: Exception) { null }

    fun thumbnail(ctx: Context, t: Task): Bitmap? {
        if (t.uri.isBlank()) return null
        try {
            if (t.isPrivate) {
                val f = File(Uri.parse(t.uri).path ?: "")
                return if (t.kind == "audio") ThumbnailUtils.createAudioThumbnail(f, Size(480, 300), null)
                else ThumbnailUtils.createVideoThumbnail(f, Size(480, 300), null)
            }
            return ctx.contentResolver.loadThumbnail(Uri.parse(t.uri), Size(480, 300), null)
        } catch (e: Exception) { Engine.logEvent(null, "Storage:109", e.toString()) }
        if (t.kind != "audio" && Uri.parse(t.uri).authority != MediaStore.AUTHORITY) try {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(ctx, Uri.parse(t.uri)); return r.getFrameAtTime(1_000_000) } finally { r.release() }
        } catch (e: Exception) { Engine.logEvent(null, "Storage:thumb", e.toString()) }
        return null
    }

    /** Files owned by this app that are in Downloads/H-DO or the private folder but not in the task list. */
    fun scanUntracked(ctx: Context, known: Set<String>): List<Task> {
        val out = ArrayList<Task>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DURATION,
            )
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/H-DO/%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(3) ?: ""
                    if (uri in known || !(mime.startsWith("video") || mime.startsWith("audio"))) continue
                    out.add(Task(id = newId(), url = "", title = name.substringBeforeLast('.'), kind = if (mime.startsWith("audio")) "audio" else "video",
                        status = St.DONE, percent = 100f, uri = uri, size = c.getLong(2), mime = mime, finished = c.getLong(4) * 1000,
                        created = c.getLong(4) * 1000, duration = c.getLong(5) / 1000))
                }
            }
        } catch (e: Exception) { Engine.logEvent(null, "Storage:135", e.toString()) }
        try {
            privateDir(ctx).walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                val uri = Uri.fromFile(f).toString()
                if (uri !in known) {
                    val mime = Engine.mimeOf(f.extension)
                    out.add(Task(id = newId(), url = "", title = f.nameWithoutExtension, kind = if (mime.startsWith("audio")) "audio" else "video",
                        status = St.DONE, percent = 100f, uri = uri, size = f.length(), mime = mime, finished = f.lastModified(), created = f.lastModified()))
                }
            }
        } catch (e: Exception) { Engine.logEvent(null, "Storage:145", e.toString()) }
        return out
    }
}
