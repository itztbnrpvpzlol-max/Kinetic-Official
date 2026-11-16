package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.data.AppSettings
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.plugins.EffectPluginManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettings, pluginManager: EffectPluginManager, onImportPlugin: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    var pluginRevision by remember { mutableIntStateOf(0) }
    val installedPacks = remember(pluginRevision) { pluginManager.installedPacks() }
    Column(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { KIcon(IconKind.BACK, KColors.Text) }
            Text("Preferences", color = KColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
        Column(Modifier.widthIn(max = 900.dp).fillMaxSize().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Section("New compositions")
            ChoiceRow("Resolution", AppSettings.RESOLUTIONS, settings.resolution) { settings.updateResolution(it) }
            ChoiceRow("Frame rate", AppSettings.FPS.map { "$it fps" }, settings.frameRate) { settings.updateFrameRate(it) }
            ChoiceRow("Length", AppSettings.COMP_LENGTHS.map { "${it}s" }, settings.compLength) { settings.updateCompLength(it) }

            Section("Timeline")
            ChoiceRow("Time display", AppSettings.TIME_FORMATS, settings.timeFormat) { settings.updateTimeFormat(it) }
            SwitchRow("Snap playhead to keyframes", settings.snap) { settings.updateSnap(it) }
            SwitchRow("Loop playback", settings.loop) { settings.updateLoop(it) }

            Section("Preview & performance")
            ChoiceRow("Preview quality", AppSettings.PREVIEW_QUALITIES, settings.previewQualityIndex) { settings.updatePreviewQuality(it) }
            SwitchRow("Show preview stats / debug overlay", settings.showPreviewDiagnostics) { settings.updateShowPreviewDiagnostics(it) }
            Row(
                Modifier.fillMaxWidth().clickable { showClearCacheDialog = true }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Clear saved preview frames", color = KColors.Text, fontSize = 15.sp)
                    Text("Removes frames stored on this device", color = KColors.Dim, fontSize = 12.sp)
                }
                Text("Clear", color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Section("Effect plugins")
            Text("Install .kplug packs to add shader-based effects. Packs are validated before installation.", color = KColors.Dim, fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onImportPlugin).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Install effect pack", color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("IMPORT", color = KColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (installedPacks.isEmpty()) {
                Text("No effect packs installed.", color = KColors.Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else installedPacks.forEach { pack ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("v${pack.version} • ${pack.effects.size} effects • ${pack.author}", color = KColors.Dim, fontSize = 11.sp)
                    }
                    Text("REMOVE", color = KColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            if (pluginManager.remove(pack.id)) pluginRevision++
                        }.padding(8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Kinetic 0.2", color = KColors.Dim, fontSize = 12.sp)
        }
    }
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear saved preview frames?") },
            text = { Text("Previously saved preview frames will be deleted. Kinetic will rebuild this cache as you preview media.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    scope.launch {
                        MediaLoader.clearPersistentPreviewCache(context)
                        cacheCleared = true
                    }
                }) { Text("Clear cache") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (cacheCleared) {
        AlertDialog(
            onDismissRequest = { cacheCleared = false },
            title = { Text("Preview cache cleared") },
            text = { Text("Saved preview frames have been removed.") },
            confirmButton = { TextButton(onClick = { cacheCleared = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = KColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = KColors.Text, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o -> Chip(o, i == selected) { onSelect(i) } }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = KColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = KColors.Clip, checkedThumbColor = KColors.ClipText, checkedBorderColor = KColors.Accent,
                uncheckedTrackColor = KColors.Surface2, uncheckedThumbColor = KColors.Muted, uncheckedBorderColor = KColors.Line
            )
        )
    }
}
