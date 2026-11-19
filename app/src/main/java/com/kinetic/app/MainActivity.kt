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
import com.kinetic.app.data.AppSettings
import com.kinetic.app.data.MediaLoader
import com.kinetic.app.data.ProjectStore
import com.kinetic.app.model.EditorState
import com.kinetic.app.plugins.EffectPluginManager
import com.kinetic.app.ui.EditorScreen
import com.kinetic.app.ui.HomeScreen
import com.kinetic.app.ui.KineticTheme
import com.kinetic.app.ui.SettingsScreen

private enum class Screen { HOME, EDITOR, AI_EDITOR, SETTINGS }

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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val settings = AppSettings(applicationContext)
        val store = ProjectStore(applicationContext)
        setContent {
            KineticTheme {
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
                val projectImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        val result = runCatching {
                            val input = contentResolver.openInputStream(uri)
                                ?: throw IllegalArgumentException("Kinetic couldn't open the selected project file.")
                            store.importProject(input)
                        }
                        result.onSuccess { imported ->
                            projects = store.list()
                            Toast.makeText(this@MainActivity, "Imported ${imported.name}", Toast.LENGTH_LONG).show()
                        }.onFailure { error ->
                            Toast.makeText(this@MainActivity, error.message ?: "Project import failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onNewComposition = { editor = EditorState(settings); screen = Screen.EDITOR },
                        onOpenAiEditor = { screen = Screen.AI_EDITOR },
                        onImportProject = { projectImporter.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, onImportPlugin = { pluginPicker.launch(arrayOf("*/*")) },
                        onOpenSettings = { settingsFrom = Screen.HOME; screen = Screen.SETTINGS },
                        projects = projects,
                        onOpenProject = { info ->
                            val saved = store.load(info.id)
                            if (saved != null) {
                                val e = EditorState(settings, saved.id, saved.name)
                                e.restoreProject(saved.duration, saved.resolution, saved.layers, saved.time)
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
                    Screen.AI_EDITOR -> {
                        BackHandler { screen = Screen.HOME }
                        com.kinetic.app.ui.AiEditorScreen(
                            settings = settings,
                            onBack = { screen = Screen.HOME },
                            onOpenEditor = { generated ->
                                editor = generated
                                runCatching { store.save(generated) }
                                projects = store.list()
                                screen = Screen.EDITOR
                            }
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
