package it.michde.totoeduardo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import it.michde.totoeduardo.R
import it.michde.totoeduardo.data.Catalog
import it.michde.totoeduardo.data.CatalogRepository
import it.michde.totoeduardo.data.Film
import it.michde.totoeduardo.data.Filmography
import it.michde.totoeduardo.data.Kinds
import it.michde.totoeduardo.data.Persons
import it.michde.totoeduardo.data.Platforms
import it.michde.totoeduardo.data.PlaybackProgressTracker

data class ScanState(val message: String, val progress: Float)

@Composable
fun HomeScreen(
    catalog: Catalog,
    isLoading: Boolean,
    scan: ScanState?,
    progressVersion: Int,
    onSelect: (Film, Long) -> Unit,
    onScan: () -> Unit,
    onInfo: () -> Unit
) {
    val context = LocalContext.current
    val tracker = remember { PlaybackProgressTracker(context) }
    val focusManager = LocalFocusManager.current

    var query by rememberSaveable { mutableStateOf("") }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var platform by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeFor by remember { mutableStateOf<Pair<Film, Long>?>(null) }

    val films = catalog.films
    val platformsPresent = remember(films) { Platforms.ALL.filter { p -> films.any { it.platform == p } } }
    val kindsPresent = remember(films) { Kinds.ALL.filter { k -> films.any { it.kind == k } } }

    val filtered = remember(films, query, person, platform, kind) {
        val q = Filmography.norm(query)
        films.filter { f ->
            (person == null || person in f.persons) &&
                (platform == null || f.platform == platform) &&
                (kind == null || f.kind == kind) &&
                (q.isBlank() || Filmography.norm("${f.title} ${f.year ?: ""} ${f.director} ${f.cast} ${f.description} ${f.matched}").contains(q))
        }
    }

    resumeFor?.let { (film, pos) ->
        AlertDialog(
            onDismissRequest = { resumeFor = null },
            containerColor = Palette.Surface,
            shape = RoundedCornerShape(12.dp),
            title = { Text("Riprendi visione", color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text(film.title, color = Palette.Gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text("Ti eri fermato a ${formatTime(pos)}. Vuoi riprendere da lì?", color = Palette.TextMuted, fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { resumeFor = null; onSelect(film, pos) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Gold)
                ) { Text("Riprendi", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { tracker.clearProgress(film.id); resumeFor = null; onSelect(film, 0L) }) {
                    Text("Dall'inizio", color = Palette.Text)
                }
            }
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        val compact = maxWidth < 720.dp
        val hPad = if (compact) 14.dp else 32.dp

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (compact) 158.dp else 236.dp),
            contentPadding = PaddingValues(start = hPad, end = hPad, top = if (compact) 12.dp else 22.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(
                    compact = compact,
                    catalog = catalog,
                    query = query,
                    onQuery = { query = it },
                    onSearchDone = { focusManager.clearFocus() },
                    onScan = onScan,
                    onInfo = onInfo,
                    scanning = scan != null
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow {
                        item { FilterPill("Tutti", person == null, Palette.Gold) { person = null } }
                        items(Persons.ALL) { p ->
                            val n = films.count { p in it.persons }
                            FilterPill("${Persons.label(p)}  $n", person == p, Palette.person(p)) { person = if (person == p) null else p }
                        }
                    }
                    if (platformsPresent.size > 1 || kindsPresent.size > 1) {
                        ChipRow {
                            if (platformsPresent.size > 1) {
                                item { FilterPill("Tutte le piattaforme", platform == null, Palette.TextMuted) { platform = null } }
                                items(platformsPresent) { p ->
                                    FilterPill(Platforms.label(p), platform == p, Palette.platform(p)) { platform = if (platform == p) null else p }
                                }
                            }
                            if (kindsPresent.size > 1) {
                                item { Spacer(Modifier.width(10.dp)) }
                                items(kindsPresent) { k ->
                                    FilterPill(Kinds.label(k), kind == k, Palette.Gold) { kind = if (kind == k) null else k }
                                }
                            }
                        }
                    }
                    scan?.let {
                        Column(Modifier.padding(top = 4.dp)) {
                            LinearProgressIndicator(
                                progress = { it.progress },
                                color = Palette.Gold,
                                trackColor = Palette.Border,
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            )
                            Text(it.message, color = Palette.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            when {
                isLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Palette.Gold)
                    }
                }
                filtered.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(catalogEmpty = films.isEmpty(), scanning = scan != null)
                }
                else -> items(filtered, key = { it.id }) { film ->
                    // progressVersion forza la rilettura dei progressi dopo la visione
                    val saved = remember(film.id, progressVersion) { tracker.getProgress(film.id) }
                    val ratio = remember(film.id, progressVersion) { tracker.getRatio(film.id) }
                    FilmCard(
                        film = film,
                        compact = compact,
                        savedPosition = saved,
                        savedRatio = ratio,
                        onClick = {
                            if (saved > 15_000L && !film.opensExternally) resumeFor = film to saved else onSelect(film, 0L)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    compact: Boolean,
    catalog: Catalog,
    query: String,
    onQuery: (String) -> Unit,
    onSearchDone: () -> Unit,
    onScan: () -> Unit,
    onInfo: () -> Unit,
    scanning: Boolean
) {
    val title = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Persons.ALL.forEach { p ->
                    Box(Modifier.size(width = 6.dp, height = if (compact) 9.dp else 11.dp).background(Palette.person(p), RoundedCornerShape(2.dp)))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "TOTÒ · EDUARDO · DE CRESCENZO",
                    color = Palette.Text,
                    fontSize = if (compact) 17.sp else 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val date = CatalogRepository.prettyDate(catalog.generated)
                Text(
                    buildString {
                        append("${catalog.films.size} titoli in streaming")
                        if (date.isNotBlank()) append("  •  aggiornato al $date")
                    },
                    color = Palette.Gold,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
    val actions = @Composable { modifier: Modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(query, onQuery, onSearchDone, if (compact) Modifier.weight(1f) else Modifier.width(340.dp))
            RoundIcon(R.drawable.ic_refresh, if (scanning) "Scansione in corso" else "Cerca nuovi titoli", enabled = !scanning, onClick = onScan)
            RoundIcon(R.drawable.ic_info, "Informazioni", onClick = onInfo)
        }
    }
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            title()
            actions(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(Modifier.weight(1f)) { title() }
            actions(Modifier)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Cerca titolo, anno, regista, attore…", color = Color(0xFF7D7268), fontSize = 13.sp) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_search), "Cerca", tint = if (focused) Palette.Gold else Palette.TextMuted, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                Icon(painterResource(R.drawable.ic_clear), "Cancella", tint = Palette.Text, modifier = Modifier.size(16.dp))
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
            focusedBorderColor = Palette.Gold,
            unfocusedBorderColor = Palette.Border,
            focusedTextColor = Palette.Text,
            unfocusedTextColor = Palette.Text,
            cursorColor = Palette.Gold
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
    )
}

@Composable
private fun RoundIcon(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.Gold else Palette.Border, CircleShape)
            .background(Palette.Surface, CircleShape)
    ) {
        Icon(
            painterResource(icon), description,
            tint = when {
                !enabled -> Palette.TextMuted.copy(alpha = 0.5f)
                focused -> Palette.Gold
                else -> Palette.Text
            },
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun FilterPill(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else Palette.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Palette.Text
                    selected -> accent
                    else -> Palette.Border
                },
                shape = shape
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) accent else Palette.Text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyState(catalogEmpty: Boolean, scanning: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when {
                scanning -> "Sto cercando i titoli su RaiPlay e Mediaset Infinity…"
                catalogEmpty -> "Il catalogo è vuoto."
                else -> "Nessun titolo con questi filtri."
            },
            color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
        )
        if (catalogEmpty && !scanning) {
            Text(
                "Premi ⟳ per cercare i film disponibili adesso.",
                color = Palette.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun FilmCard(film: Film, compact: Boolean, savedPosition: Long, savedRatio: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(150), label = "scale")
    val accent = Palette.person(film.persons.first())
    val shape = RoundedCornerShape(10.dp)
    val infoHeight: Dp = if (compact) 84.dp else 96.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Palette.Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Palette.Border, shape)
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), Palette.SurfaceHigh)))
        ) {
            Text(
                film.title,
                color = Palette.Text.copy(alpha = 0.55f),
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                maxLines = 3,
                modifier = Modifier.align(Alignment.Center).padding(12.dp)
            )
            if (film.thumb.isNotBlank()) {
                AsyncImage(model = film.thumb, contentDescription = film.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            film.year?.let { TagBadge(it.toString(), Palette.Text, Modifier.align(Alignment.TopStart)) }
            TagBadge(Platforms.label(film.platform), Color.White, Modifier.align(Alignment.TopEnd), Palette.platform(film.platform).copy(alpha = 0.9f))
            if (film.opensExternally) {
                TagBadge("↗ app ufficiale", Palette.Text, Modifier.align(Alignment.BottomEnd))
            }
            if (focused) {
                Box(Modifier.fillMaxSize().background(Color(0x55000000)), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = accent, modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_player_play), "Riproduci", tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (savedPosition > 15_000L) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxWidth(if (savedRatio > 0f) savedRatio else 0.3f).fillMaxHeight().background(accent))
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(infoHeight)) {
            Column(Modifier.fillMaxHeight().width(4.dp)) {
                film.persons.forEach { p -> Box(Modifier.fillMaxWidth().weight(1f).background(Palette.person(p))) }
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    film.title,
                    color = if (focused) accent else Palette.Text,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = if (compact) 16.sp else 18.sp
                )
                Text(
                    secondaryLine(film),
                    color = Palette.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        film.persons.joinToString(" · ") { Persons.label(it) },
                        color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    val right = if (savedPosition > 15_000L) "da ${formatTime(savedPosition)}" else formatDuration(film.duration)
                    if (right.isNotBlank()) Text(right, color = Palette.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun secondaryLine(film: Film): String {
    val parts = mutableListOf(Kinds.label(film.kind))
    when {
        film.director.isNotBlank() -> parts += "regia ${film.director.split(",").first().trim()}"
        film.source.isNotBlank() && Filmography.norm(film.source) != Filmography.norm(film.title) -> parts += film.source
        film.description.isNotBlank() -> parts += film.description
    }
    return parts.joinToString(" • ")
}

@Composable
private fun TagBadge(text: String, color: Color, modifier: Modifier, bg: Color = Color(0xCC000000)) {
    Box(
        modifier
            .padding(6.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
