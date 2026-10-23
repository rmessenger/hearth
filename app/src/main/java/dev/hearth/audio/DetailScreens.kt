package dev.hearth.audio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(repo: LibraryRepository, theme: String, setTheme: (String) -> Unit, folder: () -> Unit, effects: () -> Unit, statistics: () -> Unit) {
    var rewind by remember { mutableIntStateOf(repo.preferences.getInt("rewind", 5)) }
    var shake by remember { mutableStateOf(repo.preferences.getBoolean("shake", true)) }
    var skipSilence by remember { mutableStateOf(repo.preferences.getBoolean("skipSilence", false)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionLabel("YOUR BOOKS")
        Text("Audiobook folder", fontWeight = FontWeight.SemiBold)
        Text(repo.root?.lastPathSegment?.substringAfter(':') ?: "No folder selected", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = folder) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose folder") }
        Text("Hearth checks for changes while your library is open and whenever you return. Keep each book in its own folder; numbered audio files play in order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionLabel("LISTENING")
        Text("Rewind after a pause", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 5, 10, 15).forEach { seconds -> FilterChip(rewind == seconds, { rewind = seconds; repo.preferences.edit().putInt("rewind", seconds).apply() }, { Text(if (seconds == 0) "Off" else "${seconds}s") }) } }
        ToggleRow("Shake to extend sleep timer", "A shake resets the timer, or resumes within 30 seconds after it ends.", shake) { shake = it; repo.preferences.edit().putBoolean("shake", it).apply() }
        ToggleRow("Skip silence", "Shorten quiet gaps during playback.", skipSilence) { skipSilence = it; repo.preferences.edit().putBoolean("skipSilence", it).apply(); effects() }
        OutlinedButton(onClick = statistics) { Text("Listening statistics") }
        HorizontalDivider()
        SectionLabel("APPEARANCE")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("System", "Light", "Dark").forEach { label -> FilterChip(theme == label, { setTheme(label) }, { Text(label) }) } }
        HorizontalDivider()
        Text("Hearth", fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineMedium)
        Text("A quiet place for your audiobooks.\nVersion 0.1.0 · Android 13+\nNo account, ads, or subscriptions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Audio files stay in your chosen folder. Listening progress, bookmarks, and notes are stored on this device and are removed if Hearth is uninstalled.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
@Composable private fun ToggleRow(title: String, description: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodySmall) }; Switch(value, change) }
}

@Composable
fun NotesScreen(screen: String, book: Book, progress: BookProgress, repo: LibraryRepository, seek: (Int, Long) -> Unit, dialog: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(book.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif) }
        when (screen) {
            "Chapters" -> book.tracks.forEachIndexed { index, track ->
                item { ListItem(headlineContent = { Text(track.name) }, supportingContent = { Text("File ${index + 1} · ${BookMath.time(track.durationMs)}") }, trailingContent = { IconButton(onClick = { seek(index, 0) }) { Icon(Icons.Rounded.PlayArrow, "Go to file ${index + 1}") } }) }
                items(track.chapters) { chapter ->
                    ListItem(headlineContent = { Text(chapter.title) }, supportingContent = { Text(BookMath.time(chapter.startMs)) }, trailingContent = { IconButton(onClick = { seek(index, chapter.startMs) }) { Icon(Icons.Rounded.PlayArrow, "Go to ${chapter.title}") } })
                }
            }
            "Characters" -> {
                item { Button(onClick = { dialog("Add character") }) { Icon(Icons.Rounded.Add, null); Text("Add character") } }
                if (progress.characters.isEmpty()) item { Text("Keep track of the people in your story. Add a name and a short note.") }
                items(progress.characters, key = { it.id }) { character ->
                    ListItem(headlineContent = { Text(character.name) }, supportingContent = { Text(character.note) }, trailingContent = { IconButton(onClick = { repo.updateProgress(book.id) { it.copy(characters = it.characters.filterNot { note -> note.id == character.id }) } }) { Icon(Icons.Rounded.DeleteOutline, "Remove ${character.name}") } })
                }
            }
            else -> {
                val marks = if (screen == "Bookmarks") progress.bookmarks else progress.history
                if (screen == "Bookmarks") item { Button(onClick = { dialog("Add bookmark") }) { Icon(Icons.Rounded.BookmarkAdd, null); Text("Bookmark current position") } }
                if (marks.isEmpty()) item { Text(if (screen == "Bookmarks") "Save moments you’d like to return to." else "Previous positions will appear here when you open a book or seek.") }
                items(marks, key = { it.id }) { mark ->
                    val index = book.tracks.indexOfFirst { it.uri == mark.trackUri }
                    ListItem(headlineContent = { Text(mark.note) }, supportingContent = {
                        Text("${if (index < 0) "File unavailable" else "File ${index + 1}"} · ${BookMath.time(mark.positionMs)}\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(mark.created))}")
                    }, leadingContent = { IconButton(onClick = { seek(index, mark.positionMs) }, enabled = index >= 0) { Icon(Icons.Rounded.PlayArrow, "Go to ${mark.note}") } }, trailingContent = {
                        if (screen == "Bookmarks") IconButton(onClick = { repo.updateProgress(book.id) { it.copy(bookmarks = it.bookmarks.filterNot { bookmark -> bookmark.id == mark.id }) } }) { Icon(Icons.Rounded.DeleteOutline, "Remove bookmark") }
                    })
                }
            }
        }
    }
}

@Composable
fun StatisticsScreen(books: List<Book>, progress: Map<String, BookProgress>) {
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Time well spent", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(BookMath.remaining(progress.values.sumOf { it.listenedMs }, 1f), style = MaterialTheme.typography.displayMedium)
        Text("of listening time", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("${progress.values.count { it.status == "Finished" }} books finished · ${books.size} in your library")
        books.sortedByDescending { progress[it.id]?.listenedMs ?: 0 }.forEach { book ->
            Row { Text(book.title, Modifier.weight(1f)); Text(BookMath.remaining(progress[book.id]?.listenedMs ?: 0, 1f)) }
        }
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, select: (String) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { options.forEach { option -> TextButton(onClick = { select(option) }, modifier = Modifier.fillMaxWidth()) { Text(option) } } } }, confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable
fun TextEntryDialog(title: String, label: String, save: (String) -> Unit, dismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it }, label = { Text(label) }) }, confirmButton = { TextButton(onClick = { save(text) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable
fun CharacterDialog(save: (String, String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add character") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true); OutlinedTextField(note, { note = it }, label = { Text("Who are they?") }) } }, confirmButton = { TextButton(onClick = { save(name.trim(), note) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable
fun SpeedDialog(speed: Float, change: (Float) -> Unit, dismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(speed) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Playback speed") }, text = { Column {
        Text("%.2f×".format(value), style = MaterialTheme.typography.headlineLarge)
        Slider(value, { value = (it * 20).toInt() / 20f }, valueRange = 0.5f..3f, steps = 49, onValueChangeFinished = { change(value) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1f, 1.25f, 1.5f, 2f).forEach { rate -> FilterChip(value == rate, { value = rate; change(rate) }, { Text("${rate}×") }) } }
        Text("Saved for this book. Pitch stays natural.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { change(value); dismiss() }) { Text("Done") } })
}
@Composable
fun SoundDialog(repo: LibraryRepository, change: () -> Unit, dismiss: () -> Unit) {
    var preset by remember { mutableIntStateOf(repo.preferences.getInt("equalizer", 0)) }
    var boost by remember { mutableIntStateOf(repo.preferences.getInt("boost", 0)) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Sound") }, text = { Column {
        Text("Equalizer", fontWeight = FontWeight.SemiBold)
        listOf("Flat", "Clear voice", "Warm bass").forEachIndexed { index, name -> FilterChip(preset == index, { preset = index; repo.preferences.edit().putInt("equalizer", index).apply(); change() }, { Text(name) }) }
        Text("Volume boost: +$boost dB", Modifier.padding(top = 16.dp))
        Slider(boost.toFloat(), { boost = it.toInt() }, valueRange = 0f..9f, steps = 8, onValueChangeFinished = { repo.preferences.edit().putInt("boost", boost).apply(); change() })
        Text("High boost can distort loud recordings.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
