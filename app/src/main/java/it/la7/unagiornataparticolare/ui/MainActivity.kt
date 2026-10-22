package it.la7.unagiornataparticolare.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import it.la7.unagiornataparticolare.data.UgpEpisode
import it.la7.unagiornataparticolare.data.UgpRepository
import it.la7.unagiornataparticolare.player.PlayerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: UgpRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = UgpRepository(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D1117),
                    surface = Color(0xFF161B22),
                    primary = Color(0xFFD4AF37),
                    onPrimary = Color.Black
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0D1117)
                ) {
                    AppNavigation(repository)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(repository: UgpRepository) {
    val coroutineScope = rememberCoroutineScope()
    var episodes by remember { mutableStateOf<List<UgpEpisode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedEpisode by remember { mutableStateOf<Pair<UgpEpisode, Long>?>(null) }

    fun refreshData() {
        isLoading = true
        coroutineScope.launch {
            episodes = repository.getEpisodes()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    selectedEpisode?.let { (episode, initialPos) ->
        PlayerScreen(
            episode = episode,
            initialPositionMs = initialPos,
            onBack = { selectedEpisode = null }
        )
    } ?: run {
        HomeScreen(
            episodes = episodes,
            isLoading = isLoading,
            onSelectEpisode = { ep, pos ->
                selectedEpisode = ep to pos
            },
            onRefresh = { refreshData() }
        )
    }
}
