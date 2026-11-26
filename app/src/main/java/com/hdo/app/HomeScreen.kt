package com.hdo.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private fun viewsText(v: Long): String = when {
    v >= 1_000_000_000 -> String.format("%.1fB", v / 1e9)
    v >= 1_000_000 -> String.format("%.1fM", v / 1e6)
    v >= 1_000 -> String.format("%.1fK", v / 1e3)
    else -> v.toString()
}

@Composable
fun HomeScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val s by Store.settings.collectAsState()
    val tasks by Store.tasks.collectAsState()
    val history by Store.history.collectAsState()
    val isUrl = Engine.extractUrls(vm.query).isNotEmpty()
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val hasContent = vm.results.isNotEmpty() || vm.searching || vm.error.isNotBlank()
    val sources = listOf("youtube" to "YouTube", "ytmusic" to "YT Music", "soundcloud" to "SoundCloud")

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        vm.update?.let { r ->
            if (!vm.updateDismissed) item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(t("new_version") + " " + r.tag, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    when (vm.updState) {
                                        "downloading" -> t("upd_dl") + " " + (vm.updProgress * 100).toInt() + "%"
                                        "error" -> t("upd_fail")
                                        else -> t("upd_cur") + " " + BuildConfig.VERSION_NAME
                                    },
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                            }
                            if (vm.updState != "downloading") {
                                Button(onClick = { vm.startUpdate(ctx) }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Text(if (vm.updState == "ready") t("upd_install") else t("upd_get"), fontWeight = FontWeight.Bold)
                                }
                            }
                            IconButton(onClick = { vm.updateDismissed = true }, modifier = Modifier.size(36.dp)) {
                                Icon(androidx.compose.material.icons.Icons.Filled.Close, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        if (vm.updState == "downloading") {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { vm.updProgress },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
            }
        }
        vm.clip?.let { c ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📋 " + t("clip_found"), fontSize = 12.sp)
                        Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp)
                        TextButton(onClick = { vm.useClip() }) { Text(t("clip_use")) }
                        IconButton(onClick = { vm.clip = null }, modifier = Modifier.size(32.dp)) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.onQuery(it) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { vm.focused = it.isFocused },
                placeholder = { Text(t("search_hint")) }, shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.clear() }) { Icon(Icons.Filled.Close, null) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
            )
        }
        item {
            Button2(t("search"), enabled = vm.query.isNotBlank() && !vm.searching) { focus.clearFocus(); vm.search() }
        }
        if (!isUrl) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources, key = { it.first }) { (k, label) ->
                    FilterChip(selected = vm.source == k, onClick = { vm.source = k }, label = { Text(label) })
                }
            }
        }
        // live suggestions (YouTube) + matching history while typing
        if (vm.focused && vm.query.isNotBlank() && !isUrl) {
            val local = history.filter { it.contains(vm.query.trim(), ignoreCase = true) && !it.equals(vm.query.trim(), true) }.take(3)
            val list = (local + vm.suggestions).distinct().take(8)
            items(list, key = { "s_$it" }) { sug ->
                Row(Modifier.fillMaxWidth().clickable { vm.query = sug; focus.clearFocus(); vm.search() }.padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sug, Modifier.padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (vm.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (vm.error.isNotBlank()) item {
            Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                Text(
                    if (vm.error == "none") t("no_results") else (hintText(vm.error) ?: vm.errorMsg),
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
        vm.playlist?.let { p ->
            item {
                Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("▶ " + p.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(tn("playlist_n", p.entries.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(t("download_all"), Modifier.weight(1f), onClick = { vm.optionsFolder = p.title; vm.optionsFor = p.entries })
                            ActionButton("⚡ " + t("fast_download"), Modifier.weight(1f), onClick = { vm.quick(ctx, p.entries, p.title) })
                        }
                    }
                }
            }
        }
        itemsIndexed(vm.results, key = { i, e -> e.url + "#" + i }) { _, e ->
            val before = tasks.any { it.status == St.DONE && it.url.isNotBlank() && (it.url == e.url || (e.id.length >= 6 && it.url.contains(e.id))) }
            ResultCard(e, before,
                onOptions = {
                    focus.clearFocus()
                    vm.optionsFolder = vm.playlist?.title ?: ""; vm.optionsFor = listOf(e)
                },
                onFast = {
                    focus.clearFocus()
                    vm.quick(ctx, listOf(e), vm.playlist?.title ?: "")
                },
                onPreview = { vm.previewFor = e })
        }
        if (vm.canMore) item {
            // keep the button on screen while the next page loads, with a spinner, so the tap has visible feedback
            OutlinedButton(onClick = { vm.search(more = true) }, enabled = !vm.searching, modifier = Modifier.fillMaxWidth()) {
                if (vm.searching) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (vm.searching) t("loading_more") else t("more"))
            }
        }
        if (!hasContent) {
            item {
                if (history.isNotEmpty() && s.saveHistory) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(t("recent"), Modifier.weight(1f))
                        TextButton(onClick = { Store.clearHistory() }) { Text(t("clear_all"), fontSize = 12.sp) }
                    }
                } else {
                    EmptyState("🔎", t("home_empty"), t("home_empty2"))
                }
            }
            if (s.saveHistory) items(history.take(10), key = { "h_$it" }) { h ->
                Row(Modifier.fillMaxWidth().clickable { vm.query = h; vm.search() }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(h, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { Store.removeHistory(h) }) { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) }
                }
            }
        }
    }
    vm.optionsFor?.let { list -> DownloadOptionsDialog(list, vm.optionsFolder) { vm.optionsFor = null } }
    vm.previewFor?.let { e -> PreviewDialog(e) { vm.previewFor = null } }
}

@Composable
private fun Button2(text: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PreviewDialog(e: Entry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember(e.url) { mutableStateOf("loading") }        // loading | play | error:<msg-or-code>
    var player by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var pQuality by remember { mutableIntStateOf(Store.settings.value.previewQuality.toIntOrNull() ?: if (Store.settings.value.dataSaver && !Engine.unmetered(ctx)) 360 else 480) }
    var sbSkip by remember { mutableStateOf(false) }
    var curSet by remember { mutableStateOf<Engine.StreamSet?>(null) }
    var forceSlow by remember(e.url) { mutableStateOf(false) }   // set when the direct stream fails: re-resolve fresh URLs
    val ds = remember {
        androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
    }
    LaunchedEffect(e.url, pQuality, forceSlow) {
        state = try {
            // cached fast path first; on failure re-resolve fresh URLs from the extractor
            var set: Engine.StreamSet? = if (!forceSlow) withContext(Dispatchers.IO) { Engine.fastStream(e.id.ifBlank { e.url }, e.url, pQuality) } else null
            if (set == null) {
                Engine.awaitReady(ctx)
                set = withContext(Dispatchers.IO) { Engine.resolveStreamSlow(ctx, e.id.ifBlank { e.url }, e.url, pQuality) }
            }
            if (set.video.isBlank()) {
                "error:preview_fail"
            } else {
                curSet = set
                // small buffers: playback starts after ~0.5s of download instead of the 2.5s stock default
                val p = player ?: androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                    .setLoadControl(
                        androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            .setBufferDurationsMs(2000, 15000, 500, 1500).build(),
                    ).build().also {
                        player = it
                        // direct URLs unusable from this network (403/throttle) -> re-resolve fresh URLs, once
                        it.addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                if (!forceSlow) forceSlow = true else state = "error:preview_fail"
                            }
                        })
                    }
                val pos = if (p.mediaItemCount > 0) p.currentPosition else 0L
                val f = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds)
                val vs = f.createMediaSource(androidx.media3.common.MediaItem.fromUri(set.video))
                p.setMediaSource(
                    if (set.audio.isBlank()) vs
                    else androidx.media3.exoplayer.source.MergingMediaSource(
                        vs, f.createMediaSource(androidx.media3.common.MediaItem.fromUri(set.audio)),
                    ),
                )
                p.prepare()
                if (pos > 0) p.seekTo(pos)
                p.playWhenReady = true
                // stuck-buffering watchdog: if the direct stream never starts, re-resolve fresh URLs
                if (!forceSlow) launch {
                    kotlinx.coroutines.delay(12000)
                    if (!forceSlow && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING) forceSlow = true
                }
                // SponsorBlock: skip community-marked segments while previewing (YouTube only)
                if (e.url.contains("youtu")) {
                    launch {
                        val segs = withContext(Dispatchers.IO) { Engine.sponsorSegments(e.id) }
                        while (segs.isNotEmpty()) {
                            kotlinx.coroutines.delay(500)
                            if (!p.isPlaying) continue
                            val pos = p.currentPosition / 1000f
                            val seg = segs.firstOrNull { pos >= it[0] && pos < it[1] - 0.5f } ?: continue
                            p.seekTo((seg[1] * 1000).toLong())
                            sbSkip = true
                            kotlinx.coroutines.delay(2000)
                            sbSkip = false
                        }
                    }
                }
                "play"
            }
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val code = Engine.friendlyError(ex.message ?: "")
            "error:" + code.ifBlank { (ex.message ?: "?").takeLast(120) }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { player?.release() }
    }
    HdoDialog(onDismiss) {
        Text(e.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(240.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            when {
                state == "loading" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(t("loading_preview"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                state == "play" -> player?.let { PlayerBox(it, Modifier.fillMaxSize(), onClose = onDismiss) }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 28.sp)
                    val msg = state.removePrefix("error:")
                    Text(
                        hintText(msg) ?: msg,
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (sbSkip) Text(
                "⏭ " + t("skipped_sponsor"), color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(androidx.compose.ui.graphics.Color(0xBB000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(240, 360, 480, 720, 1080).forEach { q ->
                FilterChip(selected = pQuality == q, onClick = { pQuality = q }, label = { Text("${q}p") })
            }
            FilterChip(
                selected = false,
                onClick = {
                    val set = curSet ?: return@FilterChip
                    val i = android.content.Intent(ctx, PlaybackService::class.java).setAction("play")
                        .putExtra("video", set.video).putExtra("audio", set.audio)
                        .putExtra("pos", player?.currentPosition ?: 0L)
                        .putExtra("title", e.title)
                    androidx.core.content.ContextCompat.startForegroundService(ctx, i)
                    onDismiss()
                },
                label = { Text("🎧 " + t("bg_play")) },
            )
        }
    }
}

@Composable
private fun ResultCard(e: Entry, before: Boolean, onOptions: () -> Unit, onFast: () -> Unit, onPreview: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Box(Modifier.clickable(onClick = onPreview)) {
                    RemoteThumb(e.thumb, Modifier.width(128.dp).height(72.dp))
                    if (e.duration > 0) Text(
                        fmtDur(e.duration), color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(androidx.compose.ui.graphics.Color(0xBB000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                    )
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOf(e.uploader, if (e.views > 0) viewsText(e.views) + " 👁" else "").filter { it.isNotBlank() }.joinToString(" • ")
                    if (meta.isNotBlank()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (before) Text("✓ " + t("downloaded_before"), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(t("download"), Modifier.weight(1f), onClick = onOptions)
                ActionButton("⚡ " + t("fast_download"), Modifier.weight(1f), onClick = onFast)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// download options: video qualities / audio formats, trim, schedule, extras
// ------------------------------------------------------------------------------------------------
@Composable
fun DownloadOptionsDialog(entries: List<Entry>, folder: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val s = Store.settings.value
    val single = entries.size == 1
    val first = entries.first()
    var tab by remember { mutableIntStateOf(0) }                  // 0 video, 1 audio
    var quality by remember { mutableStateOf(effDefQuality(s.defQuality, !Engine.unmetered(ctx), s.dataSaver)) }
    var container by remember { mutableStateOf(s.defContainer) }
    var audioFmt by remember { mutableStateOf(s.defAudioFormat) }
    var audioQ by remember { mutableStateOf(s.defAudioQuality) }
    var formatExpr by remember { mutableStateOf("") }
    var subs by remember { mutableStateOf(s.subtitles) }
    var embed by remember { mutableStateOf(s.embed) }
    var sponsor by remember { mutableStateOf(s.sponsor) }
    var trim by remember { mutableStateOf(false) }
    var trimA by remember { mutableStateOf("") }
    var trimB by remember { mutableStateOf("") }
    var sched by remember { mutableStateOf(false) }
    var schedAt by remember { mutableStateOf(0L) }
    var info by remember { mutableStateOf<Probe?>(null) }
    var loading by remember { mutableStateOf(single) }
    val probeStage by Engine.probeStage.collectAsState()
    val isYt = first.url.contains("youtu")

    // load the real formats of this video (only for a single item)
    LaunchedEffect(first.url) {
        if (single) {
            try {
                Engine.awaitReady(ctx)
                info = withContext(Dispatchers.IO) { Engine.probe(ctx, first.url, playlist = false, limit = 1, flat = false) }
                // probe() already cached the resolved streams: tapping the thumbnail after this is instant
            } catch (e: Exception) { Engine.logEvent(null, "HomeScreen:415", e.toString()) }
            loading = false
        }
    }
    val heights: List<VOpt> = info?.videoOptions?.takeIf { it.isNotEmpty() }
        ?: listOf(1080, 720, 480, 360, 240).map { VOpt(it, 0L) }
    val dur = info?.duration ?: first.duration

    HdoDialog(onDismiss) {
        Text(if (single) first.title else tn("n_items", entries.size), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (loading) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (probeStage.isNotBlank()) t(probeStage) else t("loading_formats"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        SectionTitle(t("ready_made"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(false, { tab = 0; quality = "best"; container = "mp4"; formatExpr = "" }, { Text("✨ " + t("p_best")) }) }
            item { FilterChip(false, { tab = 0; quality = "480"; container = "mp4"; formatExpr = "" }, { Text("📱 " + t("p_phone")) }) }
            item {
                FilterChip(false, {
                    tab = 0; quality = "360"; container = "mp4"
                    formatExpr = "bv*[height<=360][vcodec^=avc1]+ba[acodec^=mp4a]/b[height<=360][ext=mp4]/b[height<=360]/b"
                }, { Text("💬 " + t("p_whatsapp")) })
            }
            item { FilterChip(false, { tab = 1; audioFmt = "mp3"; audioQ = "192" }, { Text("🎵 MP3") }) }
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text("🎬 " + t("video")) })
            FilterChip(tab == 1, { tab = 1 }, { Text("🎵 " + t("audio")) })
        }

        if (tab == 0) {
            SectionTitle(t("quality"))
            QualityRow(t("best_quality"), "", quality == "best" && formatExpr.isEmpty()) { quality = "best"; formatExpr = "" }
            heights.forEach { v ->
                QualityRow("${v.height}p", if (v.bytes > 0) "~" + fmtSize(v.bytes) else "", quality == v.height.toString() && formatExpr.isEmpty()) {
                    quality = v.height.toString(); formatExpr = ""
                }
            }
            SectionTitle(t("container"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("mp4", "mkv", "webm").forEach { c -> FilterChip(container == c, { container = c }, { Text(c) }) } }
        } else {
            SectionTitle(t("afmt"))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("mp3", "m4a", "opus", "flac", "wav")) { a -> FilterChip(audioFmt == a, { audioFmt = a }, { Text(a) }) }
            }
            if (audioFmt == "mp3" || audioFmt == "m4a" || audioFmt == "opus") {
                SectionTitle(t("audio_quality"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("best", "320", "256", "192", "128")) { q ->
                        val est = if (dur > 0 && q != "best") " ~" + fmtSize(dur * q.toLong() * 125L) else ""
                        FilterChip(audioQ == q, { audioQ = q }, { Text(if (q == "best") t("best_quality") else "$q kbps$est") })
                    }
                }
            }
        }

        SectionTitle(t("extras"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Toggle(subs, Icons.Filled.Subtitles, t("t_subs")) { subs = !subs } }
            item { Toggle(embed, Icons.Filled.Image, t("t_embed")) { embed = !embed } }
            if (isYt) item { Toggle(sponsor, Icons.Filled.Block, t("t_sb")) { sponsor = !sponsor } }
            if (single) item { Toggle(trim, Icons.Filled.ContentCut, t("t_trim")) { trim = !trim } }
            item { Toggle(sched, Icons.Filled.Schedule, t("t_sched")) { sched = !sched } }
        }
        if (trim && single) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(trimA, { trimA = it }, label = { Text(t("from")) }, placeholder = { Text("0:00") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(trimB, { trimB = it }, label = { Text(t("to")) }, placeholder = { Text("1:30") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (sched) OutlinedButton(
            onClick = {
                val c = Calendar.getInstance()
                DatePickerDialog(ctx, { _, y, m, d ->
                    TimePickerDialog(ctx, { _, hh, mm ->
                        schedAt = Calendar.getInstance().apply { set(y, m, d, hh, mm, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text(t("start_at") + (if (schedAt > 0) ": " + fmtDate(schedAt) else "…")) }

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = {
                val a = Engine.parseTime(trimA); val b = Engine.parseTime(trimB)
                val trimOk = trim && single && a != null && b != null && b > a && a >= 0
                val spec = Spec(
                    kind = if (tab == 1) "audio" else "video", quality = quality, container = container, audioFormat = audioFmt, audioQuality = audioQ,
                    format = formatExpr, subtitles = subs, embed = embed, sponsor = sponsor,
                    trimStart = if (trimOk) a!! else -1.0, trimEnd = if (trimOk) b!! else -1.0,
                    scheduledAt = if (sched && schedAt > System.currentTimeMillis()) schedAt else 0L,
                )
                val expect = if (!single || formatExpr.isNotBlank()) 0L else if (tab == 1) {
                    if (audioQ == "best") info?.audioBytes ?: 0L else if (dur > 0) dur * (audioQ.toLongOrNull() ?: 192L) * 125L else 0L
                } else when (quality) {
                    "best" -> heights.firstOrNull()?.bytes ?: 0L
                    else -> heights.firstOrNull { it.height.toString() == quality }?.bytes ?: 0L
                }
                val n = Enqueue.start(ctx, entries, spec, folder, expect, if (single) info?.raw ?: "" else "")
                toast(ctx, Strings.get("added", lang).replace("{n}", n.toString()))
                onDismiss()
            }) { Text(t("start_download")) }
            OutlinedButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    }
}

@Composable
private fun Toggle(on: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
}

@Composable
private fun QualityRow(label: String, size: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Text(size, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}
