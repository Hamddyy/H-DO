package com.hdo.app

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

object St {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val PROCESSING = "processing"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val ERROR = "error"
    const val CANCELED = "canceled"
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

/** org.json returns the string "null" for JSON null; this helper returns "" instead. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

/** A search result / video / playlist item. */
data class Entry(
    val id: String,
    val title: String,
    val url: String,
    val duration: Long = 0,
    val thumb: String = "",
    val uploader: String = "",
    val views: Long = 0,
)

/** Everything needed to start a download (chosen in the options dialog or taken from defaults). */
data class Spec(
    val kind: String = "video",          // video | audio
    val quality: String = "best",        // best | 2160 | 1440 | 1080 | 720 | 480 | 360 ...
    val container: String = "mp4",
    val audioFormat: String = "mp3",
    val audioQuality: String = "192",    // best | 320 | 256 | 192 | 128 (kbps)
    val format: String = "",             // optional full yt-dlp format expression
    val subtitles: Boolean = false,
    val embed: Boolean = true,
    val sponsor: Boolean = false,
    val trimStart: Double = -1.0,
    val trimEnd: Double = -1.0,
    val scheduledAt: Long = 0,
) {
    companion object {
        fun defaults(s: Settings, audio: Boolean = false) = Spec(
            kind = if (audio) "audio" else "video", quality = s.defQuality, container = s.defContainer, audioFormat = s.defAudioFormat,
            audioQuality = s.defAudioQuality, subtitles = s.subtitles, embed = s.embed, sponsor = s.sponsor,
        )
    }
}

/** Data-saver cap: on a metered connection the default quality is capped at 360p (explicit user choices are untouched). */
fun effDefQuality(q: String, metered: Boolean, saver: Boolean): String =
    if (saver && metered && (q.toIntOrNull() ?: Int.MAX_VALUE) > 360) "360" else q

data class Task(
    val id: String,
    val url: String,
    val title: String = url,
    val thumb: String = "",
    val uploader: String = "",
    val duration: Long = 0,
    val site: String = "",
    val kind: String = "video",
    val quality: String = "best",
    val container: String = "mp4",
    val audioFormat: String = "mp3",
    val audioQuality: String = "192",
    val format: String = "",
    val trimStart: Double = -1.0,
    val trimEnd: Double = -1.0,
    val subtitles: Boolean = false,
    val embed: Boolean = true,
    val sponsor: Boolean = false,
    val status: String = St.QUEUED,
    val percent: Float = 0f,
    val info: String = "",
    val uri: String = "",
    val size: Long = 0,
    val sizeTotal: Long = 0,
    val expectSize: Long = 0,
    val sizeDone: Long = 0,
    val infoPath: String = "",
    val mime: String = "",
    val error: String = "",
    val hint: String = "",
    val scheduledAt: Long = 0,
    val created: Long = System.currentTimeMillis(),
    val finished: Long = 0,
    val folder: String = "",
    val retries: Int = 0,
) {
    val trimmed: Boolean get() = trimStart >= 0 && trimEnd > trimStart
    val isActive: Boolean get() = status == St.DOWNLOADING || status == St.PROCESSING
    val isPrivate: Boolean get() = uri.startsWith("file:")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("title", title).put("thumb", thumb).put("uploader", uploader)
        .put("duration", duration).put("site", site).put("kind", kind).put("quality", quality).put("container", container)
        .put("audioFormat", audioFormat).put("audioQuality", audioQuality).put("format", format)
        .put("trimStart", trimStart).put("trimEnd", trimEnd).put("subtitles", subtitles).put("embed", embed)
        .put("sponsor", sponsor).put("status", status).put("percent", percent.toDouble()).put("uri", uri)
        .put("size", size).put("sizeTotal", sizeTotal).put("expectSize", expectSize).put("sizeDone", sizeDone).put("infoPath", infoPath).put("mime", mime).put("error", error).put("hint", hint).put("scheduledAt", scheduledAt)
        .put("created", created).put("finished", finished).put("folder", folder).put("retries", retries)

    companion object {
        fun fromJson(o: JSONObject): Task {
            val kind = o.str("kind").ifBlank { "video" }
            return Task(
                id = o.str("id"), url = o.str("url"), title = o.str("title"), thumb = o.str("thumb"), uploader = o.str("uploader"),
                duration = o.optLong("duration"), site = o.str("site"), kind = kind,
                quality = o.str("quality").ifBlank { "best" }, container = o.str("container").ifBlank { "mp4" },
                audioFormat = o.str("audioFormat").ifBlank { "mp3" }, audioQuality = o.str("audioQuality").ifBlank { "192" },
                format = o.str("format"), trimStart = o.optDouble("trimStart", -1.0), trimEnd = o.optDouble("trimEnd", -1.0),
                subtitles = o.optBoolean("subtitles"), embed = o.optBoolean("embed", true), sponsor = o.optBoolean("sponsor"),
                status = o.str("status").ifBlank { St.QUEUED }, percent = o.optDouble("percent", 0.0).toFloat(), uri = o.str("uri"),
                size = o.optLong("size"), sizeTotal = o.optLong("sizeTotal"), expectSize = o.optLong("expectSize"), sizeDone = o.optLong("sizeDone"), infoPath = o.str("infoPath"), mime = o.str("mime"), error = o.str("error"), hint = o.str("hint"),
                scheduledAt = o.optLong("scheduledAt"), created = o.optLong("created", System.currentTimeMillis()),
                finished = o.optLong("finished"), folder = o.str("folder"), retries = o.optInt("retries"),
            )
        }
    }
}

data class Settings(
    // appearance
    val lang: String = if (Locale.getDefault().language == "ar") "ar" else "en",
    val theme: String = "auto",                 // auto | light | dark | black
    val accent: Int = 0xFF0EA5E9.toInt(),
    val fontScale: Float = 1f,
    // home / search
    val searchSource: String = "youtube",       // youtube | ytmusic | soundcloud
    val searchCount: Int = 10,
    val quickMode: Boolean = false,             // tap = start with defaults, long-press = options
    val saveHistory: Boolean = true,
    // downloads
    val defQuality: String = "480",
    val defContainer: String = "mp4",
    val defAudioFormat: String = "mp3",
    val defAudioQuality: String = "192",
    val folderMode: String = "type",            // flat | type | site | channel
    val nameTemplate: String = "title",         // title | title_uploader | date_title
    val embed: Boolean = true,
    val subtitles: Boolean = false,
    val subLangs: String = "ar,en",
    val sponsor: Boolean = false,
    val sponsorCats: String = "sponsor,selfpromo,interaction",
    val autoUpdateEngine: Boolean = true,
    // network
    val maxConcurrent: Int = 2,
    val fragConcurrency: Int = 8,           // parallel fragments/chunks inside one download
    val rateLimit: String = "",
    val wifiOnly: Boolean = false,
    val dataSaver: Boolean = true,          // cap default quality at 360p on a metered connection
    val proxy: String = "",
    val autoRetry: Boolean = true,
    val retries: Int = 3,
    // notifications
    val notifyDone: Boolean = true,
    val notifySound: Boolean = true,
    // storage & security
    val autoDeleteDays: Int = 0,
    val privateFolder: Boolean = false,
    val appLock: Boolean = false,
    val showIntro: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lang", lang).put("theme", theme).put("accent", accent).put("fontScale", fontScale.toDouble())
        .put("searchSource", searchSource).put("searchCount", searchCount).put("quickMode", quickMode).put("saveHistory", saveHistory)
        .put("defQuality", defQuality).put("defContainer", defContainer).put("defAudioFormat", defAudioFormat)
        .put("defAudioQuality", defAudioQuality).put("folderMode", folderMode).put("nameTemplate", nameTemplate)
        .put("embed", embed).put("subtitles", subtitles).put("subLangs", subLangs).put("sponsor", sponsor)
        .put("sponsorCats", sponsorCats).put("autoUpdateEngine", autoUpdateEngine)
        .put("maxConcurrent", maxConcurrent).put("rateLimit", rateLimit).put("wifiOnly", wifiOnly).put("proxy", proxy)
        .put("dataSaver", dataSaver).put("fragConcurrency", fragConcurrency)
        .put("autoRetry", autoRetry).put("retries", retries).put("notifyDone", notifyDone).put("notifySound", notifySound)
        .put("autoDeleteDays", autoDeleteDays).put("privateFolder", privateFolder).put("appLock", appLock).put("showIntro", showIntro)
        .put("migV", 1)

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            return Settings(
                lang = o.str("lang").ifBlank { d.lang }, theme = o.str("theme").ifBlank { d.theme },
                accent = o.optInt("accent", d.accent), fontScale = o.optDouble("fontScale", d.fontScale.toDouble()).toFloat(),
                searchSource = o.str("searchSource").ifBlank { d.searchSource }, searchCount = o.optInt("searchCount", d.searchCount),
                quickMode = o.optBoolean("quickMode", d.quickMode), saveHistory = o.optBoolean("saveHistory", d.saveHistory),
                defQuality = if (o.optInt("migV", 0) >= 1) o.str("defQuality").ifBlank { d.defQuality } else "480",
                defContainer = o.str("defContainer").ifBlank { d.defContainer },
                defAudioFormat = o.str("defAudioFormat").ifBlank { d.defAudioFormat },
                defAudioQuality = o.str("defAudioQuality").ifBlank { d.defAudioQuality },
                folderMode = o.str("folderMode").ifBlank { d.folderMode }, nameTemplate = o.str("nameTemplate").ifBlank { d.nameTemplate },
                embed = o.optBoolean("embed", d.embed), subtitles = o.optBoolean("subtitles", d.subtitles),
                subLangs = o.str("subLangs").ifBlank { d.subLangs }, sponsor = o.optBoolean("sponsor", d.sponsor),
                sponsorCats = o.str("sponsorCats").ifBlank { d.sponsorCats },
                autoUpdateEngine = o.optBoolean("autoUpdateEngine", d.autoUpdateEngine),
                maxConcurrent = o.optInt("maxConcurrent", d.maxConcurrent).coerceIn(1, 4), rateLimit = o.str("rateLimit"),
                fragConcurrency = o.optInt("fragConcurrency", d.fragConcurrency).coerceIn(1, 16),
                wifiOnly = o.optBoolean("wifiOnly", d.wifiOnly), proxy = o.str("proxy"),
                dataSaver = o.optBoolean("dataSaver", d.dataSaver),
                autoRetry = o.optBoolean("autoRetry", d.autoRetry), retries = o.optInt("retries", d.retries).coerceIn(0, 10),
                notifyDone = o.optBoolean("notifyDone", d.notifyDone), notifySound = o.optBoolean("notifySound", d.notifySound),
                autoDeleteDays = o.optInt("autoDeleteDays", d.autoDeleteDays), privateFolder = o.optBoolean("privateFolder", d.privateFolder),
                appLock = o.optBoolean("appLock", d.appLock), showIntro = o.optBoolean("showIntro", d.showIntro),
            )
        }
    }
}
