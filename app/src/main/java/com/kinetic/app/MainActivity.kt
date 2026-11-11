package com.kinetic.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kinetic.app.data.AppSettings
import com.kinetic.app.data.ProjectStore
import com.kinetic.app.model.EditorState
import com.kinetic.app.ui.EditorScreen
import com.kinetic.app.ui.HomeScreen
import com.kinetic.app.ui.KineticTheme
import com.kinetic.app.ui.SettingsScreen

private enum class Screen { HOME, EDITOR, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val settings = AppSettings(applicationContext)
        val store = ProjectStore(applicationContext)
        setContent {
            KineticTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                var settingsFrom by remember { mutableStateOf(Screen.HOME) }
                var editor by remember { mutableStateOf<EditorState?>(null) }
                var projects by remember { mutableStateOf(store.list()) }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onNewComposition = { editor = EditorState(settings); screen = Screen.EDITOR },
                        onImportProject = { }, onImportPlugin = { },
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
                    Screen.SETTINGS -> {
                        BackHandler { screen = settingsFrom }
                        SettingsScreen(settings) { screen = settingsFrom }
                    }
                }
            }
        }
    }
}
