package it.la7.unagiornataparticolare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import it.la7.unagiornataparticolare.R
import it.la7.unagiornataparticolare.data.PlaybackProgressTracker
import it.la7.unagiornataparticolare.data.UgpEpisode

@Composable
fun HomeScreen(
    episodes: List<UgpEpisode>,
    isLoading: Boolean,
    onSelectEpisode: (UgpEpisode, Long) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val progressTracker = remember { PlaybackProgressTracker(context) }
    val focusManager = LocalFocusManager.current
    var searchQuery by remember { mutableStateOf("") }

    var episodeForResumeDialog by remember { mutableStateOf<Pair<UgpEpisode, Long>?>(null) }

    val filteredEpisodes = remember(searchQuery, episodes) {
        if (searchQuery.isBlank()) episodes
        else {
            val q = searchQuery.trim().lowercase()
            episodes.filter {
                it.title.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.date.lowercase().contains(q)
            }
        }
    }

    // Resume Dialog
    episodeForResumeDialog?.let { (ep, savedPos) ->
        AlertDialog(
            onDismissRequest = { episodeForResumeDialog = null },
            title = {
                Text(
                    text = "Riprendi visione",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = ep.title,
                        color = Color(0xFFD4AF37),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Hai interrotto questo episodio a ${formatTime(savedPos)}. Vuoi riprendere da dove ti eri fermato?",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toPlay = ep
                        val pos = savedPos
                        episodeForResumeDialog = null
                        onSelectEpisode(toPlay, pos)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                ) {
                    Text("Riprendi", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val toPlay = ep
                        progressTracker.clearProgress(ep.entryId)
                        episodeForResumeDialog = null
                        onSelectEpisode(toPlay, 0L)
                    }
                ) {
                    Text("Ricomincia da capo", color = Color.White)
                }
            },
            containerColor = Color(0xFF161B22),
            shape = RoundedCornerShape(12.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFD4AF37),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("LA7", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "UNA GIORNATA PARTICOLARE",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Aldo Cazzullo  •  Tutte le Puntate Intere",
                        color = Color(0xFFD4AF37),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Search Bar & Refresh
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                var isSearchFocused by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Cerca puntata, personaggio o anno...",
                            color = Color(0xFF777777),
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_search),
                            contentDescription = "Cerca",
                            tint = if (isSearchFocused) Color(0xFFD4AF37) else Color(0xFF888888),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_clear),
                                    contentDescription = "Cancella",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .width(320.dp)
                        .height(44.dp)
                        .onFocusChanged { isSearchFocused = it.isFocused },
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF161B22),
                        unfocusedContainerColor = Color(0xFF161B22),
                        focusedBorderColor = Color(0xFFD4AF37),
                        unfocusedBorderColor = Color(0xFF30363D),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFD4AF37)
                    )
                )

                var isRefreshFocused by remember { mutableStateOf(false) }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(40.dp)
                        .onFocusChanged { isRefreshFocused = it.isFocused }
                        .border(
                            width = if (isRefreshFocused) 2.dp else 1.dp,
                            color = if (isRefreshFocused) Color(0xFFD4AF37) else Color(0xFF30363D),
                            shape = CircleShape
                        )
                        .background(Color(0xFF161B22), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "Aggiorna",
                        tint = if (isRefreshFocused) Color(0xFFD4AF37) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFD4AF37))
            }
        } else {
            // Uniform Card Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 250.dp),
                contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredEpisodes, key = { it.entryId }) { ep ->
                    EpisodeCard(
                        episode = ep,
                        savedPosition = progressTracker.getProgress(ep.entryId),
                        onClick = {
                            val saved = progressTracker.getProgress(ep.entryId)
                            if (saved > 15_000L) {
                                episodeForResumeDialog = ep to saved
                            } else {
                                onSelectEpisode(ep, 0L)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(
    episode: UgpEpisode,
    savedPosition: Long,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp) // Exact uniform dimension
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color(0xFFD4AF37) else Color(0xFF21262D),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .focusable(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thumbnail container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(Color(0xFF010409))
            ) {
                AsyncImage(
                    model = episode.thumb,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Date badge
                if (episode.date.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = episode.date,
                            color = Color(0xFFE6EDF3),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Resolution Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "HD 720p",
                        color = Color(0xFFD4AF37),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Play icon overlay when focused
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x44000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFD4AF37),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_player_play),
                                    contentDescription = "Riproduci",
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Saved progress bar
                if (savedPosition > 15_000L) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .align(Alignment.BottomStart)
                            .background(Color(0xFF30363D))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.45f)
                                .fillMaxHeight()
                                .background(Color(0xFFD4AF37))
                        )
                    }
                }
            }

            // Info section
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = episode.title,
                    color = if (isFocused) Color(0xFFD4AF37) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )

                Text(
                    text = episode.description.ifBlank { "Una Giornata Particolare con Aldo Cazzullo" },
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LA7 RivediLa7",
                        color = Color(0xFFD4AF37),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (savedPosition > 15_000L) {
                        Text(
                            text = "Riprendi da ${formatTime(savedPosition)}",
                            color = Color(0xFFE5C07B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
