package com.animora.studio.ui.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.model.Project
import com.animora.studio.model.ProjectMeta
import com.animora.studio.model.ProjectStore
import com.animora.studio.ui.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(store: ProjectStore, onNew: () -> Unit, onOpen: (Project) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val projects by produceState(initialValue = emptyList<ProjectMeta>(), refresh) { value = withContext(Dispatchers.IO) { store.list() } }
    var loading by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { store.importJson(it.bufferedReader().readText()) } }.getOrNull()
            }
            if (p == null) error = "Не удалось импортировать файл проекта" else refresh++
        }
    }

    fun open(m: ProjectMeta) {
        if (loading) return
        loading = true
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { store.load(m.id) }.getOrNull() }
            loading = false
            if (p != null) onOpen(p) else error = "Не удалось открыть проект"
        }
    }

    Box(Modifier.fillMaxSize().background(Pal.bg).systemBarsPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(Pal.accent, Pal.pink))),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Animation, null, tint = Color.White) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Animora", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
                            Text("Студия 2D-анимации: покадровая, твины, кости", fontSize = 13.sp, color = Pal.dim)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = Pal.accent), modifier = Modifier.height(52.dp)) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Новый проект", fontSize = 16.sp)
                        }
                        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.height(52.dp)) {
                            Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Импорт проекта")
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(if (projects.isEmpty()) "Проектов пока нет — создайте первый!" else "Мои проекты", color = Pal.dim, fontSize = 14.sp)
                }
            }
            items(projects, key = { it.id }) { m ->
                ProjectCard(m, onOpen = { open(m) }, onRename = { renaming = m }, onDuplicate = {
                    scope.launch { withContext(Dispatchers.IO) { runCatching { store.duplicate(m.id) } }; refresh++ }
                }, onDelete = { deleting = m })
            }
        }
        if (loading) Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(16.dp), color = Pal.panel2) {
            Text("Открываю…", Modifier.padding(24.dp))
        }
    }

    renaming?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { runCatching { store.rename(m.id, name) } }; refresh++ }
                    renaming = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
            title = { Text("Переименовать") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) }
        )
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { store.delete(m.id) }; refresh++ }
                    deleting = null
                }) { Text("Удалить", color = Pal.pink) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
            title = { Text("Удалить «${m.name}»?") },
            text = { Text("Это действие нельзя отменить.") }
        )
    }
    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }, text = { Text(e) })
    }
    LaunchedEffect(Unit) { refresh++ }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(m: ProjectMeta, onOpen: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<android.graphics.Bitmap?>(null, m.thumb?.path, m.modified) {
        value = withContext(Dispatchers.IO) { m.thumb?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() } }
    }
    Surface(
        shape = RoundedCornerShape(14.dp), color = Pal.panel,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpen, onLongClick = { menu = true })
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Pal.panel2),
                contentAlignment = Alignment.Center
            ) {
                val t = thumb
                if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                else Icon(Icons.Filled.Animation, null, tint = Pal.dim, modifier = Modifier.size(40.dp))
            }
            Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${m.width}×${m.height} · ${m.fps} fps · ${m.frames} кадр.", fontSize = 11.sp, color = Pal.dim, maxLines = 1)
                    Text(DateUtils.getRelativeTimeSpanString(m.modified).toString(), fontSize = 11.sp, color = Pal.dim)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Открыть") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Дублировать") }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Удалить", color = Pal.pink) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
