package com.animora.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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
        installCrashReporter(applicationContext)
        com.animora.studio.ui.UiPrefs.init(applicationContext)
        val store = ProjectStore(applicationContext)
        val crash = File(filesDir, CRASH_FILE).takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull().also { f.delete() } }
        setContent {
            AnimoraTheme {
                AppRoot(store)
                var report by remember { mutableStateOf(crash) }
                report?.let { text -> CrashDialog(text) { report = null } }
            }
        }
    }
}

private const val CRASH_FILE = "last_crash.txt"

/** Сохраняет отчёт о падении, чтобы показать его при следующем запуске. */
private fun installCrashReporter(ctx: Context) {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    if (prev is AnimoraCrashHandler) return
    Thread.setDefaultUncaughtExceptionHandler(AnimoraCrashHandler(ctx, prev))
}

private class AnimoraCrashHandler(private val ctx: Context, private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val info = "Animora ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} · " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Поток: ${t.name}\n\n"
            File(ctx.filesDir, CRASH_FILE).writeText(info + sw.toString().take(20000))
        }
        prev?.uncaughtException(t, e)
    }
}

@Composable
private fun CrashDialog(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Приложение аварийно завершилось") },
        text = {
            Column {
                Text("Скопируйте отчёт и отправьте разработчику — это поможет исправить ошибку.", fontSize = 13.sp)
                Text(
                    text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Animora crash", text))
                Toast.makeText(ctx, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
            }) { Text("Копировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
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
