package com.hdo.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Chips(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Label(label)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (v, text) -> FilterChip(selected == v, { onSelect(v) }, { Text(text) }) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, desc: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label)
            if (desc.isNotBlank()) Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

private fun clearTemp(ctx: Context): Int {
    val root = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "tmp")
    val keep = Store.tasks.value.filter { it.isActive || it.status == St.QUEUED || it.status == St.PAUSED }.map { it.id }.toSet()
    var n = 0
    root.listFiles()?.forEach { if (it.name !in keep) { it.deleteRecursively(); n++ } }
    return n
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val s by Store.settings.collectAsState()
    val tasks by Store.tasks.collectAsState()
    var rate by remember { mutableStateOf(s.rateLimit) }
    var proxy by remember { mutableStateOf(s.proxy) }
    var subLangs by remember { mutableStateOf(s.subLangs) }
    var hasCookies by remember { mutableStateOf(Engine.cookiesFile(ctx).exists()) }
    val usedBytes = tasks.filter { it.status == St.DONE }.sumOf { it.size }

    // only used when Config.SHOW_HIDDEN_SETTINGS is true
    val pickCookies = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = try { Engine.importCookies(ctx, uri) } catch (e: Exception) { false }
            hasCookies = Engine.cookiesFile(ctx).exists()
            toast(ctx, Strings.get(if (ok) "saved" else "ck_bad", lang))
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportBackup().toByteArray()) }
            toast(ctx, Strings.get("saved", lang))
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("empty")
                Store.restoreBackup(txt)
            }.isSuccess
            toast(ctx, if (ok) Strings.get("restored", lang) else "✖")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Group(t("g_appearance")) {
            Chips(t("language"), listOf("ar" to "العربية", "en" to "English"), s.lang) { v -> Store.setSettings { it.copy(lang = v) } }
            Chips(t("theme"), listOf("auto" to t("th_auto"), "light" to t("th_light"), "dark" to t("th_dark"), "black" to t("th_black")), s.theme) { v -> Store.setSettings { it.copy(theme = v) } }
            Label(t("accent"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ACCENTS.forEach { c ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                            .border(3.dp, if (s.accent == c) MaterialTheme.colorScheme.onBackground else Color.Transparent, CircleShape)
                            .clickable { Store.setSettings { it.copy(accent = c) } },
                    )
                }
            }
            Chips(t("font_size"), listOf("0.85" to t("fs_small"), "1.0" to t("fs_normal"), "1.15" to t("fs_large"), "1.3" to t("fs_xlarge")), s.fontScale.toString()) { v ->
                Store.setSettings { it.copy(fontScale = v.toFloat()) }
            }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("hdo-diag", Engine.diagText(ctx)))
                toast(ctx, Strings.get("diag_copied", lang))
            }, modifier = Modifier.padding(top = 4.dp)) { Text(t("diag_copy")) }
        }

        Group(t("g_home")) {
            Chips(t("search_source"), listOf("youtube" to "YouTube", "ytmusic" to "YT Music", "soundcloud" to "SoundCloud"), s.searchSource) { v -> Store.setSettings { it.copy(searchSource = v) } }
            Chips(t("results_count"), listOf("10" to "10", "20" to "20", "30" to "30"), s.searchCount.toString()) { v -> Store.setSettings { it.copy(searchCount = v.toInt()) } }
            SwitchRow(t("save_history"), s.saveHistory) { c -> Store.setSettings { it.copy(saveHistory = c) }; if (!c) Store.clearHistory() }
            OutlinedButton(onClick = { Store.clearHistory(); toast(ctx, Strings.get("saved", lang)) }, modifier = Modifier.padding(top = 4.dp)) { Text(t("clear_history")) }
        }

        Group(t("g_downloads")) {
            Chips(t("def_quality"), listOf("best" to t("best_quality"), "1080" to "1080p", "720" to "720p", "480" to "480p", "360" to "360p"), s.defQuality) { v -> Store.setSettings { it.copy(defQuality = v) } }
            Chips(t("container"), listOf("mp4" to "mp4", "mkv" to "mkv", "webm" to "webm"), s.defContainer) { v -> Store.setSettings { it.copy(defContainer = v) } }
            Chips(t("afmt"), listOf("mp3" to "mp3", "m4a" to "m4a", "opus" to "opus", "flac" to "flac", "wav" to "wav"), s.defAudioFormat) { v -> Store.setSettings { it.copy(defAudioFormat = v) } }
            Chips(t("audio_quality"), listOf("best" to t("best_quality"), "320" to "320", "256" to "256", "192" to "192", "128" to "128"), s.defAudioQuality) { v -> Store.setSettings { it.copy(defAudioQuality = v) } }
            Chips(t("folders"), listOf("type" to t("f_type"), "site" to t("f_site"), "channel" to t("f_channel"), "flat" to t("f_flat")), s.folderMode) { v -> Store.setSettings { it.copy(folderMode = v) } }
            Chips(t("file_name"), listOf("title" to t("n_title"), "title_uploader" to t("n_title_up"), "date_title" to t("n_date")), s.nameTemplate) { v -> Store.setSettings { it.copy(nameTemplate = v) } }
            SwitchRow(t("t_embed"), s.embed, t("embed_d")) { c -> Store.setSettings { it.copy(embed = c) } }
            SwitchRow(t("t_subs"), s.subtitles) { c -> Store.setSettings { it.copy(subtitles = c) } }
            Field(t("sublangs"), subLangs) { v -> subLangs = v; Store.setSettings { it.copy(subLangs = v.trim()) } }
            SwitchRow(t("t_sb"), s.sponsor) { c -> Store.setSettings { it.copy(sponsor = c) } }
            if (s.sponsor) {
                Label(t("sb_cats"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("sponsor", "selfpromo", "interaction", "intro", "outro"), key = { it }) { c ->
                        val on = s.sponsorCats.split(",").contains(c)
                        FilterChip(on, {
                            val cur = s.sponsorCats.split(",").filter { it.isNotBlank() }
                            val nu = if (on) cur - c else cur + c
                            Store.setSettings { it.copy(sponsorCats = nu.joinToString(",").ifBlank { "sponsor" }) }
                        }, { Text(c) })
                    }
                }
            }
            SwitchRow(t("auto_engine"), s.autoUpdateEngine, t("auto_engine_d")) { c -> Store.setSettings { it.copy(autoUpdateEngine = c) } }
        }

        Group(t("g_network")) {
            Chips(t("conc"), (1..4).map { it.toString() to it.toString() }, s.maxConcurrent.toString()) { v -> Store.setSettings { it.copy(maxConcurrent = v.toInt()) } }
            Chips(t("frags"), listOf(1, 2, 4, 8, 16).map { it.toString() to it.toString() }, s.fragConcurrency.toString()) { v -> Store.setSettings { it.copy(fragConcurrency = v.toInt()) } }
            Field(t("rate"), rate) { v -> rate = v; Store.setSettings { it.copy(rateLimit = v.trim()) } }
            Field(t("proxy"), proxy) { v -> proxy = v; Store.setSettings { it.copy(proxy = v.trim()) } }
            SwitchRow(t("wifi_only"), s.wifiOnly) { c -> Store.setSettings { it.copy(wifiOnly = c) } }
            SwitchRow(t("data_saver"), s.dataSaver) { c -> Store.setSettings { it.copy(dataSaver = c) } }
            SwitchRow(t("auto_retry"), s.autoRetry, t("auto_retry_d")) { c -> Store.setSettings { it.copy(autoRetry = c) } }
            if (s.autoRetry) Chips(t("retries"), listOf("1", "3", "5", "10").map { it to it }, s.retries.toString()) { v -> Store.setSettings { it.copy(retries = v.toInt()) } }
        }

        Group(t("g_notifications")) {
            SwitchRow(t("notify_done"), s.notifyDone) { c -> Store.setSettings { it.copy(notifyDone = c) } }
            SwitchRow(t("notify_sound"), s.notifySound) { c -> Store.setSettings { it.copy(notifySound = c) } }
        }

        Group(t("g_storage")) {
            Text("${t("used")}: ${fmtSize(usedBytes).ifBlank { "0 KB" }}", fontSize = 13.sp)
            Chips(t("auto_delete"), listOf("0" to t("off"), "7" to "7 ${t("days")}", "30" to "30 ${t("days")}", "90" to "90 ${t("days")}"), s.autoDeleteDays.toString()) { v ->
                Store.setSettings { it.copy(autoDeleteDays = v.toInt()) }
            }
            if (s.autoDeleteDays > 0) Text(t("auto_delete_w"), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            OutlinedButton(onClick = { val n = clearTemp(ctx); toast(ctx, Strings.get("cleaned", lang).replace("{n}", n.toString())) }, modifier = Modifier.padding(top = 4.dp)) { Text(t("clear_temp")) }
        }

        Group(t("g_security")) {
            SwitchRow(t("app_lock"), s.appLock, t("app_lock_d")) { c ->
                if (c && !AppLock.available(ctx)) toast(ctx, Strings.get("need_screen_lock", lang)) else Store.setSettings { it.copy(appLock = c) }
            }
            SwitchRow(t("private_folder"), s.privateFolder, t("private_folder_d")) { c -> Store.setSettings { it.copy(privateFolder = c) } }
        }

        if (Config.SHOW_HIDDEN_SETTINGS) {
            Group(t("cookies")) {
                Text(if (hasCookies) t("ck_ok") else t("ck_no"), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickCookies.launch(arrayOf("text/plain", "*/*")) }) { Text(t("ck_pick")) }
                    if (hasCookies) OutlinedButton(onClick = { Engine.cookiesFile(ctx).delete(); hasCookies = false }) { Text(t("ck_rm")) }
                }
            }
            Group(t("backup")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { exportBackup.launch("hdo-backup.json") }, label = { Text(t("backup")) })
                    AssistChip(onClick = { importBackup.launch(arrayOf("application/json", "*/*")) }, label = { Text(t("restore")) })
                }
            }
        }
        Box(Modifier.size(8.dp))
    }
}
