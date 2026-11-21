package com.kinetic.app

import android.content.ComponentCallbacks2
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.kinetic.app.data.AppSettings
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.data.ProjectStore
import com.kinetic.app.model.EditorState
import com.kinetic.app.plugins.EffectPluginManager
import com.kinetic.app.ui.EditorScreen
import com.kinetic.app.ui.HomeScreen
import com.kinetic.app.ui.KineticTheme
import com.kinetic.app.ui.SettingsScreen

private enum class Screen { HOME, EDITOR, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MediaLoader.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MediaLoader.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Parse the EffectCraft catalog off the UI thread so the effects browser opens instantly.
        Thread { runCatching { com.kinetic.app.engine.EffectCatalog.effects } }.apply { name = "EffectCatalogLoad"; start() }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val settings = AppSettings(applicationContext)
        val store = ProjectStore(applicationContext)
        setContent {
            KineticTheme {
                val deviceDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = deviceDensity.density * settings.interfaceScale,
                        fontScale = deviceDensity.fontScale * settings.interfaceScale
                    )
                ) {
                val pluginManager = remember { EffectPluginManager(applicationContext) }
                val pluginPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) when (val result = pluginManager.install(uri)) {
                        is com.kinetic.app.plugins.PluginInstallResult.Success -> {
                            val verb = if (result.updated) "Updated" else "Installed"
                            Toast.makeText(this@MainActivity, "$verb ${result.pack.name}", Toast.LENGTH_LONG).show()
                        }
                        is com.kinetic.app.plugins.PluginInstallResult.Failure ->
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
                var screen by remember { mutableStateOf(Screen.HOME) }
                var settingsFrom by remember { mutableStateOf(Screen.HOME) }
                var editor by remember { mutableStateOf<EditorState?>(null) }
                var projects by remember { mutableStateOf(store.list()) }

                val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    if (uri != null) Thread {
                        val result = runCatching {
                            contentResolver.openOutputStream(uri)?.use { store.exportBackup(it) }
                                ?: error("Unable to create backup file")
                        }
                        runOnUiThread {
                            val message = result.fold(
                                { "Backed up ${it} projects and their imported media." },
                                { "Backup failed: ${it.message ?: "unknown error"}" }
                            )
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }.apply { name = "KineticBackupExport"; start() }
                }
                val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) Thread {
                        val result = runCatching {
                            contentResolver.openInputStream(uri)?.use { store.importBackup(it) }
                                ?: error("Unable to open backup file")
                        }
                        runOnUiThread {
                            projects = store.list()
                            val message = result.fold(
                                { "Restored ${it} projects and their imported media." },
                                { "Restore failed: ${it.message ?: "unknown error"}" }
                            )
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }.apply { name = "KineticBackupImport"; start() }
                }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        newCompDefaults = settings.lastComp,
                        onNewComposition = { cs ->
                            settings.rememberComp(cs)
                            editor = EditorState(settings, projectName = cs.name).also { it.applyComp(cs) }
                            screen = Screen.EDITOR
                        },
                        onImportProject = { }, onImportPlugin = { pluginPicker.launch(arrayOf("*/*")) },
                        onBackupProjects = { backupExport.launch("Kinetic-Projects-Backup.zip") },
                        onRestoreBackup = { backupImport.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        onOpenSettings = { settingsFrom = Screen.HOME; screen = Screen.SETTINGS },
                        projects = projects,
                        onOpenProject = { info ->
                            val saved = store.load(info.id)
                            if (saved != null) {
                                val e = EditorState(settings, saved.id, saved.name)
                                e.restoreProject(
                                    saved.duration, saved.resolution, saved.layers, saved.time,
                                    saved.comp ?: com.kinetic.app.model.CompSettings(name = saved.name, duration = saved.duration, fps = settings.lastComp.fps),
                                    saved.beatMarkers
                                )
                                editor = e
                                screen = Screen.EDITOR
                            } else projects = store.list()
                        },
                        onDeleteProject = { info -> store.delete(info.id); projects = store.list() }
                    )
                    Screen.EDITOR -> {
                        val e = editor
                        if (e != null) EditorScreen(
                            editor = e,
                            onExit = {
                                e.playing = false
                                runCatching { store.save(e) }
                                projects = store.list()
                                editor = null
                                screen = Screen.HOME
                            },
                            onOpenSettings = { settingsFrom = Screen.EDITOR; screen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS -> {
                        BackHandler { screen = settingsFrom }
                        SettingsScreen(settings, pluginManager, onImportPlugin = { pluginPicker.launch(arrayOf("*/*")) }, onBack = { screen = settingsFrom })
                    }
                }
                }
            }
        }
    }
}