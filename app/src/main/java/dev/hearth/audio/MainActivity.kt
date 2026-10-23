@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hearth.audio

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val repo get() = (application as HearthApp).library
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Hearth(repo) }
    }
    override fun onStart() { super.onStart(); repo.startWatching() }
    override fun onStop() { repo.stopWatching(); super.onStop() }
}

private val Pine = Color(0xFF23372F)
private val Cream = Color(0xFFFBF5ED)
private val Ember = Color(0xFF98452D)
private val Gold = Color(0xFFE5AF74)

@Composable
fun Hearth(repo: LibraryRepository) {
    var theme by remember { mutableStateOf(repo.preferences.getString("theme", "System")!!) }
    val dark = theme == "Dark" || (theme == "System" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Gold, background = Color(0xFF17251F), surface = Color(0xFF17251F), surfaceContainer = Pine, secondary = Color(0xFFC2CDB9), secondaryContainer = Color(0xFF354C40), onSecondaryContainer = Cream, primaryContainer = Color(0xFF57351F), onPrimaryContainer = Cream)
        else lightColorScheme(primary = Ember, background = Cream, surface = Cream, surfaceContainer = Color(0xFFF0E7DA), secondary = Pine, secondaryContainer = Color(0xFFDCE6D9), onSecondaryContainer = Pine, primaryContainer = Color(0xFFF6DDCA), onPrimaryContainer = Ember),
        typography = Typography(headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif), headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif))) {
        val library by repo.state.collectAsStateWithLifecycle()
        val progress by repo.progress.collectAsStateWithLifecycle()
        val playback by PlaybackService.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var controller by remember { mutableStateOf<MediaController?>(null) }
        var screen by rememberSaveable { mutableStateOf("Library") }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        var selectedId by rememberSaveable { mutableStateOf("") }
        var bookmarkTarget by remember { mutableStateOf<BookmarkTarget?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        DisposableEffect(context) {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            future.addListener({
                try { controller = future.get() }
                catch (error: java.util.concurrent.ExecutionException) { scope.launch { snackbar.showSnackbar("Could not connect to the player. Reopen Hearth to retry.") } }
                catch (_: java.util.concurrent.CancellationException) { }
            }, ContextCompat.getMainExecutor(context))
            onDispose { MediaController.releaseFuture(future) }
        }
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    repo.setRoot(uri)
                } catch (_: SecurityException) { scope.launch { snackbar.showSnackbar("Folder permission was not granted. Please choose it again.") } }
            }
        }
        fun command(action: String, args: Bundle = Bundle.EMPTY) { controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
        fun openBook(book: Book, play: Boolean) {
            selectedId = book.id
            command(PlaybackService.OPEN, Bundle().apply { putString("book", book.id); putBoolean("play", play) })
            screen = "Player"
        }
        val currentBook = library.books.firstOrNull { it.id == playback.bookId }
        val selectedBook = library.books.firstOrNull { it.id == selectedId } ?: currentBook
        fun showDialog(name: String) {
            if (name == "Add bookmark" && currentBook != null) {
                currentBook.tracks.getOrNull(playback.trackIndex)?.let { bookmarkTarget = BookmarkTarget(currentBook.id, it.uri, playback.positionMs) }
            }
            dialog = name
        }
        BackHandler(screen != "Library") { screen = if (screen in listOf("Bookmarks", "Characters", "History", "Chapters")) "Player" else "Library" }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (screen == "Library") Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_hearth), null, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Pine))
                    Text("hearth", Modifier.padding(start = 10.dp).weight(1f), fontFamily = FontFamily.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { screen = "Settings" }) { Icon(Icons.Rounded.Settings, "Settings") }
                } else TopAppBar(title = { Text(if (screen == "Player") "Now listening" else screen, fontFamily = FontFamily.Serif) }, navigationIcon = {
                    IconButton(onClick = { screen = if (screen in listOf("Bookmarks", "Characters", "History", "Chapters")) "Player" else "Library" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }, actions = { if (screen == "Player" && currentBook != null) IconButton(onClick = { dialog = "Book options" }) { Icon(Icons.Rounded.MoreVert, "Book options") } })
            },
            bottomBar = {
                if (screen != "Player" && currentBook != null) Surface(color = Pine, contentColor = Cream, tonalElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().clickable { selectedId = currentBook.id; screen = "Player" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(currentBook, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(currentBook.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${BookMath.time(playback.positionMs)} · ${playback.speed}×", fontSize = 12.sp, color = Gold)
                        }
                        IconButton(onClick = { if (playback.playing) controller?.pause() else controller?.play() }) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Pause" else "Play") }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    "Library" -> LibraryScreen(library, progress, repo.root != null, { pickFolder.launch(repo.root) }, { book -> openBook(book, true) }, { book -> selectedId = book.id; dialog = "Book options" })
                    "Player" -> if (currentBook != null) PlayerScreen(currentBook, playback,
                        toggle = { if (playback.playing) controller?.pause() else controller?.play() },
                        skip = { delta -> command(PlaybackService.SKIP, Bundle().apply { putLong("delta", delta) }) },
                        seek = { value -> val (index, position) = BookMath.locate(currentBook, value); controller?.seekTo(index, position) },
                        previous = { controller?.seekToPreviousMediaItem() }, next = { controller?.seekToNextMediaItem() },
                        action = { name -> when (name) { "Speed", "Sleep timer", "Add bookmark", "Sound" -> showDialog(name); else -> screen = name } })
                        else EmptyState("Choose your next story", "Open an audiobook from your library to start listening.", "Open library", { screen = "Library" })
                    "Settings" -> SettingsScreen(repo, theme, { value -> theme = value; repo.preferences.edit().putString("theme", value).apply() }, { pickFolder.launch(repo.root) }, { command(PlaybackService.EFFECTS) }, { screen = "Listening statistics" })
                    "Listening statistics" -> StatisticsScreen(library.books, progress)
                    "Bookmarks", "History", "Characters", "Chapters" -> if (currentBook != null) NotesScreen(screen, currentBook, progress[currentBook.id] ?: BookProgress(), repo,
                        { index, position -> controller?.seekTo(index, position); screen = "Player" }, { showDialog(it) })
                }
            }
        }
        if (dialog != null) {
            val book = if (dialog == "Book options") selectedBook else currentBook
            when (dialog) {
                "Speed" -> SpeedDialog(playback.speed, { controller?.setPlaybackSpeed(it) }, { dialog = null })
                "Sleep timer" -> ChoiceDialog("Sleep timer", listOf("Off", "5 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of current file"), { value ->
                    command(PlaybackService.SLEEP, Bundle().apply { putLong("minutes", value.substringBefore(' ').toLongOrNull() ?: 0); putBoolean("endOfTrack", value == "End of current file") }); dialog = null
                }, { dialog = null })
                "Sound" -> SoundDialog(repo, { command(PlaybackService.EFFECTS) }, { dialog = null })
                "Add bookmark" -> TextEntryDialog("Bookmark this moment", "Note", { note ->
                    bookmarkTarget?.let { target -> repo.bookmark(target.bookId, target.trackUri, target.positionMs, note.ifBlank { "Bookmark" }) }
                    dialog = null
                }, { dialog = null })
                "Add character" -> CharacterDialog({ name, note ->
                    if (book != null) repo.updateProgress(book.id) { it.copy(characters = it.characters + CharacterNote(UUID.randomUUID().toString(), name, note)) }
                    dialog = null
                }, { dialog = null })
                "Book options" -> if (book != null) ChoiceDialog(book.title, listOf("Mark as New", "Mark as Started", "Mark as Finished"), { value -> repo.updateProgress(book.id) { it.copy(status = value.removePrefix("Mark as ")) }; dialog = null }, { dialog = null })
            }
        }
    }
}

@Composable
private fun LibraryScreen(library: LibraryState, progress: Map<String, BookProgress>, hasFolder: Boolean, chooseFolder: () -> Unit, open: (Book) -> Unit, options: (Book) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Title") }
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Your library", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
            Text(if (library.scanning) "Looking for new stories…" else "${library.books.size} audiobooks · Automatically kept up to date", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (library.error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(library.error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (!hasFolder || (library.books.isEmpty() && !library.scanning)) {
            EmptyState(if (hasFolder) "Your next chapter awaits" else "Make yourself at home", if (hasFolder) "Add audiobook folders here and they’ll appear automatically. MP3, M4B, FLAC, and more." else "Choose the folder where your audiobooks live. Your stories and listening progress stay on this device.", if (hasFolder) "Change folder" else "Choose audiobook folder", chooseFolder)
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp), placeholder = { Text("Find a book or author") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "New", "Started", "Finished").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(sort) }
                    DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Title", "Recently listened", "Author").forEach { label -> DropdownMenuItem({ Text(label) }, { sort = label; sortMenu = false }) } }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "List view" else "Grid view") }
            }
            val books = library.books.filter { book -> (filter == "All" || (progress[book.id]?.status ?: "New") == filter) && (book.title.contains(query, true) || book.author.contains(query, true)) }.let { books ->
                when (sort) { "Recently listened" -> books.sortedByDescending { progress[it.id]?.lastPlayed ?: 0 }; "Author" -> books.sortedBy { it.author.lowercase() }; else -> books }
            }
            if (books.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No books match this view.") }
            else if (grid) LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(books, key = { it.id }) { book ->
                    Column(Modifier.clickable { open(book) }) {
                        Cover(book, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(progress[book.id]?.status ?: "New", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); IconButton(onClick = { options(book) }) { Icon(Icons.Rounded.MoreHoriz, "Options for ${book.title}") } }
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(books, key = { it.id }) { book ->
                    val state = progress[book.id] ?: BookProgress()
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, onClick = { open(book) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(book, Modifier.size(84.dp))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(state.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
                                if (book.author.isNotBlank()) Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text("${BookMath.remaining(book.durationMs - BookMath.absolute(book, state.trackUri, state.positionMs), state.speed)} left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.status == "Started") LinearProgressIndicator(progress = { (BookMath.absolute(book, state.trackUri, state.positionMs).toFloat() / book.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp))
                            }
                            IconButton(onClick = { options(book) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "Options for ${book.title}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(book: Book, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Pine, Color(0xFF496557)))), contentAlignment = Alignment.Center) {
        if (book.cover != null) AsyncImage(book.cover, "Cover of ${book.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = Gold, modifier = Modifier.size(32.dp))
            Text(book.title.take(50), Modifier.padding(top = 8.dp), color = Cream, fontFamily = FontFamily.Serif, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String, button: String, action: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.ic_hearth), null, Modifier.size(132.dp).clip(RoundedCornerShape(36.dp)).background(Pine))
        Text(title, Modifier.padding(top = 28.dp), style = MaterialTheme.typography.headlineMedium)
        Text(description, Modifier.padding(top = 12.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = action, contentPadding = PaddingValues(24.dp, 16.dp)) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(button) }
    }
}

@Composable
private fun PlayerScreen(book: Book, state: PlaybackState, toggle: () -> Unit, skip: (Long) -> Unit, seek: (Long) -> Unit, previous: () -> Unit, next: () -> Unit, action: (String) -> Unit) {
    val track = book.tracks.getOrNull(state.trackIndex) ?: return
    val absolute = BookMath.absolute(book, track.uri, state.positionMs)
    var dragPosition by remember(book.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(book, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
        Text(book.title, Modifier.padding(top = 22.dp), style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) Text(book.author, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("FILE ${state.trackIndex + 1} OF ${book.tracks.size} · ${track.name.substringBeforeLast('.')}", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = 1.sp)
        Slider(value = dragPosition ?: absolute.toFloat(), onValueChange = { dragPosition = it }, valueRange = 0f..book.durationMs.coerceAtLeast(1).toFloat(), onValueChangeFinished = { dragPosition?.let { seek(it.toLong()) }; dragPosition = null }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(BookMath.time((dragPosition ?: absolute.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium)
            Text("${BookMath.remaining(book.durationMs - absolute, state.speed)} left at ${state.speed}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = previous, enabled = state.trackIndex > 0) { Icon(Icons.Rounded.SkipPrevious, "Previous file") }
            IconButton(onClick = { skip(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", Modifier.size(32.dp)) }
            FilledIconButton(onClick = toggle, modifier = Modifier.size(76.dp), shape = CircleShape) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(42.dp)) }
            IconButton(onClick = { skip(30_000) }) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds", Modifier.size(32.dp)) }
            IconButton(onClick = next, enabled = state.trackIndex < book.tracks.lastIndex) { Icon(Icons.Rounded.SkipNext, "Next file") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { action("Speed") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("${state.speed}× speed") }
            OutlinedButton(onClick = { action("Sleep timer") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.endOfTrack) "End of file" else if (state.sleepRemainingMs > 0) BookMath.time(state.sleepRemainingMs) else "Sleep timer") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionIcon("Add bookmark", Icons.Rounded.BookmarkAdd, action)
            ActionIcon("Chapters", Icons.AutoMirrored.Rounded.QueueMusic, action)
            ActionIcon("Sound", Icons.Rounded.Tune, action)
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("Bookmarks", "History", "Characters").forEach { TextButton(onClick = { action(it) }) { Text(it, fontSize = 12.sp) } } }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable private fun ActionIcon(label: String, icon: ImageVector, action: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { IconButton(onClick = { action(label) }) { Icon(icon, label) }; Text(label, style = MaterialTheme.typography.labelSmall) }
}
