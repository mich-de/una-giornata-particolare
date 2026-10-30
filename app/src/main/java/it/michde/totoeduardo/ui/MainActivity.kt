package it.michde.totoeduardo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.michde.totoeduardo.data.Catalog
import it.michde.totoeduardo.data.CatalogRepository
import it.michde.totoeduardo.data.Film
import it.michde.totoeduardo.data.Persons
import it.michde.totoeduardo.data.Platforms
import it.michde.totoeduardo.data.Playable
import it.michde.totoeduardo.data.StreamResolver
import it.michde.totoeduardo.player.PlayerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = CatalogRepository(applicationContext)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Background) {
                    AppRoot(repository)
                }
            }
        }
    }
}

private data class NowPlaying(val film: Film, val stream: Playable.Stream, val startMs: Long)

@Composable
fun AppRoot(repo: CatalogRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf(Catalog(emptyList(), "", "")) }
    var loading by remember { mutableStateOf(true) }
    var scan by remember { mutableStateOf<ScanState?>(null) }
    var scanReport by remember { mutableStateOf<List<String>>(emptyList()) }
    var showInfo by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var resolving by remember { mutableStateOf<Film?>(null) }
    var external by remember { mutableStateOf<Pair<Film, String>?>(null) }
    var progressVersion by remember { mutableIntStateOf(0) }

    fun startScan() {
        if (scan != null) return
        scan = ScanState("Avvio scansione…", 0f)
        scope.launch {
            try {
                val (c, report) = repo.scanDevice(catalog) { msg, p -> scan = ScanState(msg, p) }
                catalog = c
                scanReport = report
                Toast.makeText(context, report.joinToString("  •  "), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Scansione non riuscita: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                scan = null
            }
        }
    }

    fun play(film: Film, startMs: Long) {
        if (resolving != null) return
        resolving = film
        scope.launch {
            when (val p = StreamResolver.resolve(film)) {
                is Playable.Stream -> nowPlaying = NowPlaying(film, p, startMs)
                is Playable.External -> external = film to p.reason
            }
            resolving = null
        }
    }

    LaunchedEffect(Unit) {
        catalog = repo.loadLocal()
        loading = false
        catalog = repo.refreshRemote(catalog)
        if (catalog.films.isEmpty()) startScan()
    }

    val np = nowPlaying
    if (np != null) {
        PlayerScreen(
            film = np.film,
            stream = np.stream,
            initialPositionMs = np.startMs,
            onBack = { nowPlaying = null; progressVersion++ },
            onOpenExternal = { reason -> nowPlaying = null; progressVersion++; external = np.film to reason }
        )
        return
    }

    HomeScreen(
        catalog = catalog,
        isLoading = loading,
        scan = scan,
        progressVersion = progressVersion,
        onSelect = { film, pos -> play(film, pos) },
        onScan = { startScan() },
        onInfo = { showInfo = true }
    )

    resolving?.let { film ->
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.person(film.persons.first()))
                Spacer(Modifier.height(14.dp))
                Text("Preparo “${film.title}”…", color = Palette.Text, fontSize = 15.sp)
            }
        }
    }

    external?.let { (film, reason) ->
        AlertDialog(
            onDismissRequest = { external = null },
            containerColor = Palette.Surface,
            shape = RoundedCornerShape(12.dp),
            title = { Text(film.title, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text(reason, color = Palette.TextMuted, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(film.pageUrl, color = Palette.Gold, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { external = null; openExternally(context, film) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.person(film.persons.first()))
                ) { Text("Apri in ${Platforms.appName(film.platform)}", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { external = null }) { Text("Chiudi", color = Palette.Text) } }
        )
    }

    if (showInfo) {
        InfoDialog(catalog, scanReport) { showInfo = false }
    }
}

@Composable
private fun InfoDialog(catalog: Catalog, report: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Informazioni", color = Palette.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val date = CatalogRepository.prettyDate(catalog.generated)
                Text(
                    "Catalogo: ${catalog.films.size} titoli" + (if (date.isNotBlank()) ", aggiornato al $date" else "") +
                        " (fonte: ${catalog.origin}).",
                    color = Palette.Text, fontSize = 14.sp
                )
                Persons.ALL.forEach { p ->
                    val n = catalog.films.count { p in it.persons }
                    Text("${Persons.fullName(p)}: $n", color = Palette.person(p), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Platforms.ALL.forEach { p ->
                    val n = catalog.films.count { it.platform == p }
                    if (n > 0) Text("${Platforms.appName(p)}: $n", color = Palette.TextMuted, fontSize = 13.sp)
                }
                if (report.isNotEmpty()) Text("Ultima scansione: " + report.joinToString(", "), color = Palette.TextMuted, fontSize = 12.sp)
                Text(
                    "Il catalogo si aggiorna ogni settimana da GitHub; ⟳ cerca subito i titoli nuovi su RaiPlay e Mediaset Infinity. " +
                        "I video si guardano dai server ufficiali; quando serve l'accesso o l'abbonamento, il titolo si apre nell'app della piattaforma.",
                    color = Palette.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Chiudi", color = Palette.Gold) } }
    )
}

/** Prova l'app della piattaforma (deep link), poi il browser; su TV senza browser mostra il link. */
fun openExternally(context: Context, film: Film) {
    val intents = mutableListOf<Intent>()
    if (film.platform == Platforms.YOUTUBE) intents += Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${film.ref}"))
    intents += Intent(Intent.ACTION_VIEW, Uri.parse(film.pageUrl))
    // pacchetti noti delle app ufficiali (telefono e TV): se il deep link non è gestito, apre almeno l'app
    val packages = when (film.platform) {
        Platforms.RAIPLAY -> listOf("it.rainet.raiplay", "it.rainet.androidtv", "it.rainet.raiplaytv")
        Platforms.MEDIASET -> listOf("it.mediaset.infinity", "it.mediaset.mediasetplay", "it.mediaset.infinitytv")
        Platforms.LA7 -> listOf("it.la7.la7", "it.la7.tv")
        Platforms.YOUTUBE -> listOf("com.google.android.youtube.tv", "com.google.android.youtube")
        else -> emptyList()
    }
    packages.forEach { pkg -> context.packageManager.getLaunchIntentForPackage(pkg)?.let { intents += it } }

    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, "Nessuna app per aprire il link. Cerca \"${film.title}\" in ${Platforms.appName(film.platform)}.", Toast.LENGTH_LONG).show()
}
