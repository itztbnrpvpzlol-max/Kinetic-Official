package com.kinetic.app.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.data.AppSettings
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.plugins.EffectPluginManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val SettingsCardShape = RoundedCornerShape(18.dp)
private const val KINETIC_REPOSITORY = "https://github.com/itztbnrpvpzlol-max/Kinetic-Official"
private const val KINETIC_ISSUES = "https://github.com/itztbnrpvpzlol-max/Kinetic-Official/issues/new"

@Composable
fun SettingsScreen(
    settings: AppSettings,
    pluginManager: EffectPluginManager,
    onImportPlugin: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pluginRevision by remember { mutableIntStateOf(0) }
    val installedPacks = remember(pluginRevision) { pluginManager.installedPacks() }

    val packageInfo = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    val versionCode = if (Build.VERSION.SDK_INT >= 28) {
        packageInfo?.longVersionCode ?: 0L
    } else {
        (packageInfo?.versionCode ?: 0).toLong()
    }
    val lastUpdated = packageInfo?.lastUpdateTime?.let {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
    } ?: "Unknown"
    val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }
        .distinct().joinToString(" ").ifBlank { "Unknown" }
    val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val glVersion = remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.deviceConfigurationInfo?.glEsVersion ?: "Unavailable"
    }
    val deviceInfo = """
        Device: $model
        Android: $androidVersion
        Graphics API: OpenGL ES $glVersion
        App: Kinetic $versionName (build $versionCode)
    """.trimIndent()

    Column(
        Modifier.fillMaxSize().background(KColors.Bg)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                KIcon(IconKind.BACK, KColors.Text)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Settings", color = KColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(44.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsSection("Language")
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().clip(SettingsCardShape)
                        .clickable { showLanguageDialog = true }
                        .padding(horizontal = 18.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("◎", color = KColors.Muted, fontSize = 25.sp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("English", color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Current app language", color = KColors.Muted, fontSize = 12.sp)
                    }
                    Text("›", color = KColors.Muted, fontSize = 28.sp)
                }
            }

            SettingsSection("Interface size")
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    AppSettings.INTERFACE_SIZES.forEach { (label, scale) ->
                        val selected = kotlin.math.abs(settings.interfaceScale - scale) < 0.01f
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { settings.updateInterfaceScale(scale) }
                                .padding(horizontal = 18.dp, vertical = if (scale == 1f) 13.dp else 15.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                label,
                                color = if (selected) KColors.Accent else KColors.Text,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                            if (scale == 1f) {
                                Text(
                                    "Uses this phone's normal interface size.",
                                    color = KColors.Muted, fontSize = 12.sp, lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection("General")
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SettingsChoiceRow("Time display", AppSettings.TIME_FORMATS, settings.timeFormat) {
                        settings.updateTimeFormat(it)
                    }
                    SettingsSwitchRow("Snap playhead to keyframes", settings.snap) { settings.updateSnap(it) }
                    SettingsSwitchRow("Loop playback", settings.loop) { settings.updateLoop(it) }
                }
            }

            SettingsSection("Previews")
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SettingsChoiceRow("Preview quality", AppSettings.PREVIEW_QUALITIES, settings.previewQualityIndex) {
                        settings.updatePreviewQuality(it)
                    }
                    SettingsSwitchRow("Show preview stats", settings.showPreviewDiagnostics) {
                        settings.updateShowPreviewDiagnostics(it)
                    }
                }
            }

            SettingsSection("Media & storage")
            SettingsCard {
                SettingsActionRow(
                    "Clear saved preview frames",
                    "Removes cached preview frames stored on this device",
                    "CLEAR"
                ) { showClearCacheDialog = true }
            }

            SettingsSection("Effect plugins")
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Install .kplug packs to add shader-based effects.",
                        color = KColors.Muted, fontSize = 12.sp)
                    SettingsActionRow("Install effect pack", "Import a validated .kplug file", "IMPORT", onImportPlugin)
                    if (installedPacks.isEmpty()) {
                        Text("No effect packs installed.", color = KColors.Dim, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp))
                    } else installedPacks.forEach { pack ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pack.name, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("v${pack.version} • ${pack.effects.size} effects • ${pack.author}",
                                    color = KColors.Muted, fontSize = 10.sp)
                            }
                            Text("REMOVE", color = KColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (pluginManager.remove(pack.id)) pluginRevision++
                                }.padding(8.dp))
                        }
                    }
                }
            }

            SettingsSection("App")
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
                    SettingsInfoRow("Kinetic", "$versionName (build $versionCode)")
                    SettingsInfoRow("Last updated", lastUpdated)
                }
            }

            SettingsSection("This device")
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
                    SettingsInfoRow("Model", model)
                    SettingsInfoRow("Android", androidVersion)
                    SettingsInfoRow("Graphics API", "OpenGL ES $glVersion")
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Kinetic device info", deviceInfo))
                                Toast.makeText(context, "Device information copied", Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("Copy device info", color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            SettingsSection("Help")
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
                    SettingsActionRow("Report a problem", "Open a GitHub issue for Kinetic", "OPEN") {
                        uriHandler.openUri(KINETIC_ISSUES)
                    }
                    SettingsActionRow("Kinetic on GitHub", "Source code and project updates", "VIEW") {
                        uriHandler.openUri(KINETIC_REPOSITORY)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Kinetic", color = KColors.Dim, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("Language") },
            text = { Text("Kinetic's interface is currently available in English. Additional languages will be added when localization is supported.") },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("OK") } }
        )
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
private fun SettingsSection(title: String) {
    Text(
        title, color = KColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 17.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(SettingsCardShape)
            .background(KColors.Surface)
            .border(1.dp, KColors.Line.copy(alpha = 0.55f), SettingsCardShape)
    ) { content() }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = KColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End)
    }
}

@Composable
private fun SettingsActionRow(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = KColors.Muted, fontSize = 11.sp)
        }
        Text(action, color = KColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsChoiceRow(
    title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEachIndexed { index, option ->
                val active = index == selected
                Box(
                    Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (active) KColors.Accent.copy(alpha = 0.14f) else KColors.Bg)
                        .border(1.dp, if (active) KColors.Accent.copy(alpha = 0.65f) else KColors.Line,
                            RoundedCornerShape(7.dp))
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(option, color = if (active) KColors.Accent else KColors.Muted,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = KColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = KColors.Clip, checkedThumbColor = KColors.ClipText,
                checkedBorderColor = KColors.Accent, uncheckedTrackColor = KColors.Surface2,
                uncheckedThumbColor = KColors.Muted, uncheckedBorderColor = KColors.Line
            )
        )
    }
}
