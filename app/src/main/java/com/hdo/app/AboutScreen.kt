package com.hdo.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    val engine by Engine.state.collectAsState()
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<Engine.Release?>(null) }

    fun open(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 12.dp).size(96.dp).clip(RoundedCornerShape(26.dp))) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text("H-DO", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(t("tagline"), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 13.sp)

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow(t("app_name"), "H-DO")
                InfoRow(t("version"), version)
                InfoRow(t("founder"), Config.FOUNDER)
                InfoRow(t("yt_ver"), "NewPipe + yt-dlp (self-updating)")
                InfoRow(t("license"), "GPL-3.0")
            }
        }

        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { Engine.latestRelease() }
                        if (r == null) toast(ctx, Strings.get("no_release", lang))
                        else if (Engine.isNewer(r.tag, version)) release = r
                        else toast(ctx, Strings.get("up_to_date", lang))
                    } catch (e: Exception) {
                        toast(ctx, Strings.get("h_network", lang))
                    }
                    checking = false
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(t("check_update")) }

        OutlinedButton(onClick = { open("https://github.com/${Config.UPDATE_REPO}") }, modifier = Modifier.fillMaxWidth()) { Text("GitHub") }
        Text(t("credits"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
    }

    release?.let { r ->
        AlertDialog(
            onDismissRequest = { release = null },
            title = { Text(t("new_version") + " " + r.tag) },
            text = { Text(t("new_version_d")) },
            confirmButton = { TextButton(onClick = { open(r.apkUrl.ifBlank { r.pageUrl }); release = null }) { Text(t("download")) } },
            dismissButton = { TextButton(onClick = { release = null }) { Text(t("later")) } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
