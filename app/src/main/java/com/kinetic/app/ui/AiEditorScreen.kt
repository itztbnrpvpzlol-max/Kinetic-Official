package com.kinetic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.ai.AiEditorEngine
import com.kinetic.app.data.AppSettings
import com.kinetic.app.model.EditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AiInputFile(val uri: Uri, val label: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiEditorScreen(settings: AppSettings, onBack: () -> Unit, onOpenEditor: (EditorState) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generating by remember { mutableStateOf(false) }
    var generationStatus by remember { mutableStateOf("") }
    val footage = remember { mutableStateListOf<AiInputFile>() }
    val soundtrack = remember { mutableStateListOf<AiInputFile>() }
    val styleReferences = remember { mutableStateListOf<AiInputFile>() }
    var prompt by remember { mutableStateOf("") }
    var styleNotes by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("Cinematic") }
    var targetDuration by remember { mutableStateOf("30–45 seconds") }
    val focusOptions = listOf("Beat sync", "Impact shakes", "Time remapping", "Typography", "Transitions", "Color grading")
    val selectedFocus = remember { mutableStateListOf("Beat sync", "Transitions", "Time remapping") }

    fun keepPermissions(uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    val footagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        keepPermissions(uris)
        uris.forEach { uri ->
            if (footage.none { it.uri == uri }) footage.add(AiInputFile(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Video clip"))
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        keepPermissions(uris)
        uris.forEach { uri ->
            if (soundtrack.none { it.uri == uri }) soundtrack.add(AiInputFile(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Audio track"))
        }
    }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        keepPermissions(uris)
        uris.forEach { uri ->
            if (styleReferences.none { it.uri == uri }) styleReferences.add(AiInputFile(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Style reference"))
        }
    }

    Column(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Home", color = KColors.Muted) }
            Spacer(Modifier.weight(1f))
            Text("KINETIC AI", color = Color(0xFFB99AFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("AI Editor", color = KColors.Text, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Your footage. Your taste. A new way to build edits.", color = KColors.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AiSection("01", "Your footage", "Add clips or long-form source video")
                AiFilePickerCard(
                    title = "Add video clips",
                    subtitle = "Select one or multiple video files",
                    count = footage.size,
                    action = { footagePicker.launch(arrayOf("video/*")) }
                )
                if (footage.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    AiFileList(footage) { item -> footage.remove(item) }
                }
            }
            item {
                AiSection("02", "Soundtrack", "Choose the music the edit should follow")
                AiFilePickerCard(
                    title = "Add music / audio",
                    subtitle = "The edit planner can use this as its timing reference",
                    count = soundtrack.size,
                    action = { audioPicker.launch(arrayOf("audio/*")) }
                )
                if (soundtrack.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    AiFileList(soundtrack) { item -> soundtrack.remove(item) }
                }
            }
            item {
                AiSection("03", "Style references", "Show the kind of editing language you like")
                AiFilePickerCard(
                    title = "Add reference edits",
                    subtitle = "Videos or images that describe the look and pacing",
                    count = styleReferences.size,
                    action = { referencePicker.launch(arrayOf("video/*", "image/*")) }
                )
                if (styleReferences.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    AiFileList(styleReferences) { item -> styleReferences.remove(item) }
                }
                OutlinedTextField(
                    value = styleNotes,
                    onValueChange = { styleNotes = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("What should it learn from these references?") },
                    placeholder = { Text("e.g. fast velocity ramps, clean typography, impact on snare hits…") },
                    minLines = 2,
                    shape = RoundedCornerShape(14.dp)
                )
                Text("Kinetic measures reference pacing and image colour locally, then reuses that style profile on this device. This is analysis, not neural-network training.", color = KColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            item {
                AiSection("04", "Creative direction", "Tell the editor what you want")
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Describe your edit") },
                    placeholder = { Text("Make a high-energy anime AMV with a strong intro, beat-synced cuts, smooth speed ramps, impact moments and bold kinetic typography…") },
                    minLines = 4,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text("Starting style", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("Cinematic", "Velocity", "Mograph").forEach { style ->
                        FilterChip(
                            selected = selectedStyle == style,
                            onClick = { selectedStyle = style },
                            label = { Text(style) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Target duration", color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("15–30 seconds", "30–45 seconds", "60–90 seconds").forEach { duration ->
                        FilterChip(
                            selected = targetDuration == duration,
                            onClick = { targetDuration = duration },
                            label = { Text(duration, fontSize = 11.sp) }
                        )
                    }
                }
            }
            item {
                AiSection("05", "Editing priorities", "Choose what the AI should focus on")
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    focusOptions.forEach { option ->
                        FilterChip(
                            selected = option in selectedFocus,
                            onClick = {
                                if (option in selectedFocus) selectedFocus.remove(option)
                                else selectedFocus.add(option)
                            },
                            label = { Text(option, fontSize = 11.sp) }
                        )
                    }
                }
            }
            item {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .background(Color(0xFF211D35))
                        .border(1.dp, Color(0xFF51406F), RoundedCornerShape(15.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ON-DEVICE AI", color = Color(0xFFB99AFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Private by design", color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Footage frames, soundtrack energy and reference media are analysed locally. Kinetic then builds an editable timeline; your media is not sent to a cloud service.", color = KColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(KColors.Surface).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Button(
                onClick = {
                    if (generating || footage.isEmpty()) return@Button
                    generating = true
                    generationStatus = "Analysing footage, music and style references on this device…"
                    val seconds = when (targetDuration) {
                        "15–30 seconds" -> 22.5f
                        "60–90 seconds" -> 75f
                        else -> 37.5f
                    }
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                AiEditorEngine.generate(
                                    context,
                                    AiEditorEngine.Request(
                                        footage = footage.map { it.uri },
                                        audio = soundtrack.map { it.uri },
                                        references = styleReferences.map { it.uri },
                                        prompt = prompt,
                                        style = selectedStyle,
                                        durationSeconds = seconds,
                                        priorities = selectedFocus.toSet(),
                                        styleNotes = styleNotes
                                    ),
                                    settings.fps,
                                    settings.resolution
                                )
                            }
                        }
                        generating = false
                        result.onSuccess { generated ->
                            val project = EditorState(settings, projectName = generated.name)
                            project.restoreProject(generated.duration, settings.resolution, generated.layers, 0f)
                            android.widget.Toast.makeText(context, generated.report, android.widget.Toast.LENGTH_LONG).show()
                            onOpenEditor(project)
                        }.onFailure { error ->
                            generationStatus = error.message ?: "Couldn't generate this edit. Check the selected media and try again."
                        }
                    }
                },
                enabled = footage.isNotEmpty() && !generating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7652B8))
            ) {
                Text(if (generating) "Analysing & building…" else "Generate my edit", fontWeight = FontWeight.SemiBold)
            }
            Text(
                generationStatus.ifBlank { "Local scene analysis, music-beat detection, style profiling and editable timeline generation." },
                color = KColors.Muted, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun AiSection(number: String, title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$number  /  $title", color = KColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = KColors.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun AiFilePickerCard(title: String, subtitle: String, count: Int, action: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(KColors.Surface)
            .border(1.dp, KColors.Line, shape).clickable(onClick = action)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF302744)), contentAlignment = Alignment.Center) {
            Text("+", color = Color(0xFFD5C2FF), fontSize = 22.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = KColors.Muted, fontSize = 10.sp)
        }
        Text(if (count == 0) "Add" else "$count added", color = Color(0xFFB99AFF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AiFileList(files: List<AiInputFile>, onRemove: (AiInputFile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        files.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(KColors.Surface)
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.label, color = KColors.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(32.dp)) {
                    Text("×", color = KColors.Muted, fontSize = 18.sp)
                }
            }
        }
    }
}
