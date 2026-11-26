package com.hdo.app

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by Store.tasks.collectAsState()
    val syncing by Store.syncing.collectAsState()
    val lastSync by Store.lastSync.collectAsState()
    var filter by remember { mutableStateOf("all") }          // all | video | audio | failed
    var site by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Task?>(null) }

    fun pass(x: Task): Boolean =
        (filter != "video" || x.kind == "video") && (filter != "audio" || x.kind == "audio") &&
            (site.isEmpty() || x.site.equals(site, true)) &&
            (q.isBlank() || x.title.contains(q, true) || x.uploader.contains(q, true))

    val active = tasks.filter { (it.isActive || it.status == St.QUEUED) && pass(it) }.sortedBy { it.created }
    val paused = tasks.filter { it.status == St.PAUSED && pass(it) }
    val failed = tasks.filter { (it.status == St.ERROR || it.status == St.CANCELED) && pass(it) }
    val done = tasks.filter { it.status == St.DONE && pass(it) }.let {
        when (sort) {
            1 -> it.sortedBy { x -> x.finished }
            2 -> it.sortedByDescending { x -> x.size }
            3 -> it.sortedBy { x -> x.title.lowercase() }
            else -> it.sortedByDescending { x -> x.finished }
        }
    }
    val sites = tasks.filter { it.site.isNotBlank() }.map { it.site }.distinct().sorted()
    val onlyFailed = filter == "failed"
    val tActive = t("dl_active"); val tPaused = t("dl_paused"); val tFailed = t("dl_failed"); val tDone = t("dl_done")
    val nothing = (if (onlyFailed) failed else active + paused + failed + done).isEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text(t("search_dl")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(14.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("s_new", "s_old", "s_size", "s_name").forEachIndexed { i, k ->
                        DropdownMenuItem(text = { Text(t(k) + if (sort == i) "  ✓" else "") }, onClick = { sort = i; menu = false })
                    }
                    DropdownMenuItem(text = { Text(t("clear_failed")) }, onClick = {
                        menu = false; tasks.filter { it.status == St.ERROR || it.status == St.CANCELED }.forEach { Ops.remove(ctx, it.id, false) }
                    })
                    DropdownMenuItem(text = { Text(t("clear_done_list")) }, onClick = {
                        menu = false; tasks.filter { it.status == St.DONE }.forEach { Ops.remove(ctx, it.id, false) }
                    })
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            item { FilterChip(filter == "all", { filter = "all" }, { Text(t("all")) }) }
            item { FilterChip(filter == "video", { filter = "video" }, { Text(t("video")) }) }
            item { FilterChip(filter == "audio", { filter = "audio" }, { Text(t("audio")) }) }
            item { FilterChip(filter == "failed", { filter = "failed" }, { Text(t("dl_failed")) }) }
            items(sites, key = { "site_$it" }) { st -> FilterChip(site == st, { site = if (site == st) "" else st }, { Text(st) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (syncing) t("syncing") else if (lastSync > 0) t("last_sync") + ": " + fmtDate(lastSync) else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
            if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { LibrarySync.run(ctx) } }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) }
        }
        if (nothing) EmptyState("📭", t("dl_empty"), t("dl_empty2"))
        else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            fun section(key: String, title: String, list: List<Task>, doneStyle: Boolean) {
                if (list.isEmpty()) return
                item(key = "h_$key") { SectionTitle("$title (${list.size})") }
                items(list, key = { it.id }) { if (doneStyle) DoneRow(it) { open = it } else TaskRow(it) }
            }
            if (!onlyFailed) {
                section("active", tActive, active, false)
                section("paused", tPaused, paused, false)
            }
            section("failed", tFailed, failed, false)
            if (!onlyFailed) section("done", tDone, done, true)
        }
    }
    open?.let { tk -> PlayerDialog(tk) { open = null } }
}

@Composable
private fun TaskRow(task: Task) {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val failed = task.status == St.ERROR || task.status == St.CANCELED
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(task, Modifier.width(88.dp).height(52.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(task.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = when (task.status) {
                        St.DOWNLOADING -> if (task.info.isNotBlank()) task.info else t("waiting")
                        St.PROCESSING -> t("processing")
                        St.QUEUED -> (when {
                            task.retries > 0 && task.scheduledAt > System.currentTimeMillis() -> "\u21BB " + t("retrying") + " (" + task.retries + ")"
                            task.scheduledAt > System.currentTimeMillis() -> "\uD83D\uDD50 " + fmtDate(task.scheduledAt)
                            else -> t("waiting")
                        }) + (if (task.expectSize > 0) " \u2022 ~" + fmtSize(task.expectSize) else "")
                        St.PAUSED -> t("dl_paused") + (if (task.percent > 0f) " \u2022 ${task.percent.toInt()}%" else "")
                        else -> hintText(task.hint) ?: task.error.takeLast(90)
                    }
                    Text(sub, color = if (failed) MaterialTheme.colorScheme.error else muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (task.status == St.DOWNLOADING && task.percent > 0f)
                    Text("${task.percent.toInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp))
            }
            if (task.isActive) {
                val bar = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                if (task.status == St.PROCESSING || task.percent <= 0f) {
                    LinearProgressIndicator(modifier = bar, strokeCap = StrokeCap.Round)
                } else {
                    val anim by animateFloatAsState(targetValue = (task.percent / 100f).coerceIn(0f, 1f), label = "prog")
                    LinearProgressIndicator(progress = { anim }, modifier = bar, strokeCap = StrokeCap.Round)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val rem = (task.sizeTotal - task.sizeDone).coerceAtLeast(0)
                val stat = when {
                    task.isActive && task.sizeTotal > 0 ->
                        fmtSize(task.sizeDone) + " / " + fmtSize(task.sizeTotal) + (if (rem > 0) " \u2022 " + t("remaining") + " " + fmtSize(rem) else "")
                    task.status == St.PAUSED && task.sizeTotal > 0 -> fmtSize(task.sizeDone) + " / " + fmtSize(task.sizeTotal)
                    else -> ""
                }
                Text(stat, color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Row {
                    when (task.status) {
                        St.DOWNLOADING, St.PROCESSING -> {
                            IconButton(onClick = { Ops.pause(task.id) }) { Icon(Icons.Filled.Pause, null) }
                            IconButton(onClick = { Ops.cancel(ctx, task.id) }) { Icon(Icons.Filled.Close, null) }
                        }
                        St.QUEUED -> {
                            if (task.scheduledAt > System.currentTimeMillis()) IconButton(onClick = { Ops.resume(ctx, task.id) }) { Icon(Icons.Filled.PlayArrow, null) }
                            IconButton(onClick = { Ops.cancel(ctx, task.id) }) { Icon(Icons.Filled.Close, null) }
                        }
                        St.PAUSED -> {
                            IconButton(onClick = { Ops.resume(ctx, task.id) }) { Icon(Icons.Filled.PlayArrow, null) }
                            IconButton(onClick = { Ops.cancel(ctx, task.id) }) { Icon(Icons.Filled.Close, null) }
                        }
                        else -> {
                            IconButton(onClick = { Ops.retry(ctx, task.id) }) { Icon(Icons.Filled.Refresh, null) }
                            IconButton(onClick = { Ops.remove(ctx, task.id, false) }) { Icon(Icons.Filled.Delete, null) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneRow(task: Task, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onOpen), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Thumb(task, Modifier.width(104.dp).height(60.dp))
                if (task.duration > 0) Text(
                    fmtDur(task.duration), color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0xBB000000), RoundedCornerShape(5.dp)).padding(horizontal = 4.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(task.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOf(fmtSize(task.size), task.site, if (task.finished > 0) fmtDate(task.finished) else "", if (task.isPrivate) "🔒" else "")
                    .filter { it.isNotBlank() }.joinToString(" • ")
                Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PlayerDialog(task: Task, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(task.title) }
    HdoDialog(onDismiss) {
        Text(task.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Media3/ExoPlayer: plays every format the app produces (mkv/webm/opus/av1), with real controls
        val player = remember(task.id) {
            androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(task.uri)))
                prepare()
                playWhenReady = true
            }
        }
        androidx.compose.runtime.DisposableEffect(task.id) {
            onDispose { player.release() }
        }
        PlayerBox(player, Modifier.fillMaxWidth().height(240.dp).padding(vertical = 8.dp), onClose = onDismiss)
        Text(listOf(fmtSize(task.size), task.uploader).filter { it.isNotBlank() }.joinToString(" • "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Storage.shareUri(ctx, task), task.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
            }, label = { Text(t("open_with")) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
            AssistChip(onClick = {
                val i = Intent(Intent.ACTION_SEND).setType(task.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, Storage.shareUri(ctx, task)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
            }, label = { Text(t("share")) }, leadingIcon = { Icon(Icons.Filled.Share, null) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { rename = true }, label = { Text(t("rename")) }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
            AssistChip(onClick = { confirmDel = true }, label = { Text(t("del")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
        }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false }, title = { Text(t("new_name")) },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                val nu = if (newName.isNotBlank()) Storage.rename(ctx, task, newName) else null
                if (nu != null) Store.updateTask(task.id, true) { it.copy(title = newName.trim(), uri = nu) }
                rename = false; onDismiss()
            }) { Text(t("ok")) }
        },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(t("cancel")) } },
    )
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false }, text = { Text(t("del_f")) },
        confirmButton = { TextButton(onClick = { Ops.remove(ctx, task.id, true); confirmDel = false; onDismiss() }) { Text(t("yes")) } },
        dismissButton = { TextButton(onClick = { Ops.remove(ctx, task.id, false); confirmDel = false; onDismiss() }) { Text(t("no")) } },
    )
}
