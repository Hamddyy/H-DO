package com.hdo.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small JSON in the GitHub repo, fetched when the app opens: the real "library update" channel for a
 * compiled extractor. A new extractor version always needs a new APK - this file is how the app finds
 * out and points the user at it, plus a free-text notice (e.g. "YouTube broke downloads, update").
 */
object RemoteCfg {
    private const val URL = "https://raw.githubusercontent.com/Hamddyy/H-DO/main/app-config.json"

    data class Cfg(
        val latestVersionCode: Int, val latestVersion: String, val apkUrl: String, val pageUrl: String,
        val noticeAr: String, val noticeEn: String,
    )

    fun refresh(ctx: Context): Cfg? {
        return try {
            val c = (URL(URL).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 6000 }
            if (c.responseCode != 200) return null
            val txt = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ctx.getSharedPreferences("hdo", Context.MODE_PRIVATE).edit().putString("remoteCfg", txt).apply()
            parse(txt)
        } catch (e: Exception) { null }
    }

    fun cached(ctx: Context): Cfg? =
        ctx.getSharedPreferences("hdo", Context.MODE_PRIVATE).getString("remoteCfg", null)?.let { parse(it) }

    private fun parse(txt: String): Cfg? = try {
        val o = JSONObject(txt)
        Cfg(
            o.optInt("latestVersionCode"), o.str("latestVersion"), o.str("apkUrl"),
            o.str("pageUrl").ifBlank { "https://github.com/${Config.UPDATE_REPO}/releases" },
            o.str("noticeAr"), o.str("noticeEn"),
        )
    } catch (e: Exception) { null }
}
