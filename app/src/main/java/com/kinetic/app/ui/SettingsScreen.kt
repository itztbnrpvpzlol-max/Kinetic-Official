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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.data.AppSettings

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KColors.Text) }
            Text("Preferences", color = KColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Section("New compositions")
            ChoiceRow("Resolution", AppSettings.RESOLUTIONS, settings.resolution) { settings.updateResolution(it) }
            ChoiceRow("Frame rate", AppSettings.FPS.map { "$it fps" }, settings.frameRate) { settings.updateFrameRate(it) }
            ChoiceRow("Length", AppSettings.COMP_LENGTHS.map { "${it}s" }, settings.compLength) { settings.updateCompLength(it) }

            Section("Timeline")
            ChoiceRow("Time display", AppSettings.TIME_FORMATS, settings.timeFormat) { settings.updateTimeFormat(it) }
            SwitchRow("Snap playhead to keyframes", settings.snap) { settings.updateSnap(it) }
            SwitchRow("Loop playback", settings.loop) { settings.updateLoop(it) }

            Section("Properties")
            ChoiceRow("Scrub sensitivity", AppSettings.SENSITIVITY, settings.sensitivity) { settings.updateSensitivity(it) }

            Spacer(Modifier.height(24.dp))
            Text("Kinetic 0.2", color = KColors.Dim, fontSize = 12.sp)
        }
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
