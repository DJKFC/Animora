package com.animora.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.animora.studio.model.Project
import com.animora.studio.model.ProjectStore
import com.animora.studio.ui.AnimoraTheme
import com.animora.studio.ui.editor.EditorScreen
import com.animora.studio.ui.home.HomeScreen
import com.animora.studio.ui.home.NewProjectScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object NewProject : Screen
    class Editor(val project: Project) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProjectStore(applicationContext)
        setContent { AnimoraTheme { AppRoot(store) } }
    }
}

@Composable
fun AppRoot(store: ProjectStore) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    when (val s = screen) {
        Screen.Home -> HomeScreen(store, onNew = { screen = Screen.NewProject }, onOpen = { screen = Screen.Editor(it) })
        Screen.NewProject -> {
            BackHandler { screen = Screen.Home }
            NewProjectScreen(
                existingCount = remember { store.list().size },
                onBack = { screen = Screen.Home },
                onCreate = { p ->
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { store.save(p, null) } }
                        screen = Screen.Editor(p)
                    }
                }
            )
        }
        is Screen.Editor -> EditorScreen(s.project, store, onExit = { screen = Screen.Home })
    }
}
