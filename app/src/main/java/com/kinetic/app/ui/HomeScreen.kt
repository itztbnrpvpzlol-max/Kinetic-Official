package com.kinetic.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.R
import com.kinetic.app.data.ProjectStore
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    onNewComposition: () -> Unit,
    onImportProject: () -> Unit,
    onImportPlugin: () -> Unit,
    onOpenSettings: () -> Unit,
    projects: List<ProjectStore.ProjectInfo>,
    onOpenProject: (ProjectStore.ProjectInfo) -> Unit,
    onDeleteProject: (ProjectStore.ProjectInfo) -> Unit
) {
    Column(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Header(onOpenSettings)
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(16.dp))
            NewCompositionCard(onNewComposition)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Import Project", onImportProject, Modifier.weight(1f))
                ActionButton("Import Plugin", onImportPlugin, Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your projects", color = KColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${projects.size}", color = KColors.Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (projects.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 34.dp)) {
                        Text("No projects yet", color = KColors.Muted, fontSize = 15.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Your autosaved compositions will appear here.", color = KColors.Muted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(project, onClick = { onOpenProject(project) }, onDelete = { onDeleteProject(project) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.kinetic_icon), "Kinetic", Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
            Text("Kinetic", color = KColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Preferences", tint = KColors.Muted) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
    }
}

@Composable
private fun NewCompositionCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().height(178.dp).clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).clickable(onClick = onClick).padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TrackBar(0.46f, 0f, KColors.Clip)
            TrackBar(0.78f, 0.12f, Color(0xFF26264A))
            TrackBar(0.92f, 0f, Color(0xFF1B1B2E))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(KColors.Clip), contentAlignment = Alignment.Center) {
                Text("+", color = KColors.ClipText, fontSize = 20.sp)
            }
            Text("New Composition", color = KColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectStore.ProjectInfo, onClick: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).clickable(onClick = onClick).padding(start = 14.dp, top = 13.dp, bottom = 13.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF25233A)), contentAlignment = Alignment.Center) {
            Text("K", color = KColors.ClipText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("${project.layerCount} layers  ·  ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.updatedAt))}", color = KColors.Muted, fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Delete project", tint = KColors.Muted) }
    }
}

@Composable
private fun TrackBar(width: Float, offset: Float, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        if (offset > 0f) Spacer(Modifier.weight(offset))
        Box(Modifier.weight(width).height(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
        val rest = 1f - width - offset
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).clickable(onClick = onClick).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
