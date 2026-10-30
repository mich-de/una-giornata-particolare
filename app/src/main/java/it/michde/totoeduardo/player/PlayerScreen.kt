package it.michde.totoeduardo.player

import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import it.michde.totoeduardo.R
import it.michde.totoeduardo.data.Film
import it.michde.totoeduardo.data.Persons
import it.michde.totoeduardo.data.Platforms
import it.michde.totoeduardo.data.Playable
import it.michde.totoeduardo.data.PlaybackProgressTracker
import it.michde.totoeduardo.data.StreamKind
import it.michde.totoeduardo.ui.Palette
import it.michde.totoeduardo.ui.formatTime
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    film: Film,
    stream: Playable.Stream,
    initialPositionMs: Long = 0L,
    onBack: () -> Unit,
    onOpenExternal: (String) -> Unit
) {
    val context = LocalContext.current
    val tracker = remember { PlaybackProgressTracker(context) }
    val accent = Palette.person(film.persons.first())

    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(initialPositionMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf(false) }
    var seekRatio by remember { mutableFloatStateOf(0f) }
    var lastInteraction by remember { mutableIntStateOf(0) }
    val supportedSubs = remember(stream) { stream.subtitles.filter { subtitleMime(it.url) != null } }
    var subsOn by remember { mutableStateOf(false) }

    val playFocus = remember { FocusRequester() }

    val exoPlayer = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .build().apply {
                val item = MediaItem.Builder()
                    .setUri(stream.url)
                    .setMimeType(
                        when (stream.kind) {
                            StreamKind.HLS -> MimeTypes.APPLICATION_M3U8
                            StreamKind.DASH -> MimeTypes.APPLICATION_MPD
                            StreamKind.MP4 -> MimeTypes.VIDEO_MP4
                        }
                    )
                stream.widevineLicenseUrl?.let { lic ->
                    item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(lic).build())
                }
                if (supportedSubs.isNotEmpty()) {
                    item.setSubtitleConfigurations(supportedSubs.map { s ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                            .setMimeType(subtitleMime(s.url))
                            .setLanguage(s.language)
                            .setLabel(s.label)
                            .build()
                    })
                }
                setMediaItem(item.build())
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("it")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                prepare()
                if (initialPositionMs > 0) seekTo(initialPositionMs)
                playWhenReady = true
            }
    }

    fun seekBy(ms: Long) {
        val d = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val p = (exoPlayer.currentPosition + ms).coerceIn(0L, d)
        exoPlayer.seekTo(p)
        position = p
        showControls = true
        lastInteraction++
    }

    fun setSubtitles(on: Boolean) {
        subsOn = on
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .build()
    }

    fun leave() {
        tracker.saveProgress(film.id, exoPlayer.currentPosition, exoPlayer.duration)
        onBack()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) duration = exoPlayer.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) tracker.clearProgress(film.id)
            }

            override fun onPlayerError(e: PlaybackException) {
                buffering = false
                showControls = true
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
                    PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
                    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                    PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
                    PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                    PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
                    PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ->
                        "Il titolo è protetto e la licenza non è stata concessa a questo dispositivo."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                        "Il server di ${Platforms.appName(film.platform)} ha rifiutato la richiesta (titolo scaduto o disponibile solo in Italia)."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Connessione assente o troppo lenta."
                    else -> "Errore di riproduzione: ${e.errorCodeName}"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            tracker.saveProgress(film.id, exoPlayer.currentPosition, exoPlayer.duration)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            if (exoPlayer.isPlaying) tracker.saveProgress(film.id, position, duration)
            delay(1000)
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            try {
                playFocus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(showControls, isPlaying, seeking, lastInteraction) {
        if (showControls && !seeking) {
            delay(6000)
            if (isPlaying && error == null) showControls = false
        }
    }

    BackHandler { leave() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!showControls) {
                        showControls = true; true
                    } else false
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!showControls) {
                            showControls = true; true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (!showControls) {
                        seekBy(-10_000); true
                    } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!showControls) {
                        seekBy(10_000); true
                    } else false
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exoPlayer.play(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exoPlayer.pause(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        seekBy(30_000); true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        seekBy(-30_000); true
                    }
                    KeyEvent.KEYCODE_CAPTIONS -> {
                        if (supportedSubs.isNotEmpty()) setSubtitles(!subsOn); true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                showControls = !showControls
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (buffering && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
            }
        }

        error?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(film.title, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(msg, color = Palette.Text, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (film.platform != Platforms.MEDIASET) {
                            Button(
                                onClick = { tracker.saveProgress(film.id, exoPlayer.currentPosition, exoPlayer.duration); onOpenExternal(msg) },
                                colors = ButtonDefaults.buttonColors(containerColor = accent)
                            ) { Text("Apri in ${Platforms.appName(film.platform)}", color = Color.Black, fontWeight = FontWeight.Bold) }
                        }
                        OutlinedButton(onClick = { leave() }) { Text("Indietro", color = Palette.Text) }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showControls && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color(0xEE000000))))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(R.drawable.ic_arrow_back, "Indietro", 48, accent) { leave() }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(film.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                film.persons.joinToString(" · ") { Persons.fullName(it) },
                                film.year?.toString(),
                                film.director.takeIf { it.isNotBlank() }?.let { "regia ${it.split(",").first().trim()}" },
                                Platforms.appName(film.platform)
                            ).joinToString("  •  "),
                            color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (supportedSubs.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        SubtitleButton(subsOn, accent) { setSubtitles(!subsOn) }
                    }
                }

                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlButton(R.drawable.ic_player_replay_10, "Indietro 10 secondi", 56, accent) { seekBy(-10_000) }
                    ControlButton(
                        if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                        if (isPlaying) "Pausa" else "Riproduci", 76, accent, primary = true,
                        modifier = Modifier.focusRequester(playFocus)
                    ) { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }
                    ControlButton(R.drawable.ic_player_forward_10, "Avanti 10 secondi", 56, accent) { seekBy(10_000) }
                }

                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                    val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    var sliderFocused by remember { mutableStateOf(false) }
                    Slider(
                        value = if (seeking) seekRatio else ratio,
                        onValueChange = { v ->
                            seeking = true
                            seekRatio = v
                            showControls = true
                        },
                        onValueChangeFinished = {
                            if (duration > 0) {
                                val target = (seekRatio * duration).toLong()
                                exoPlayer.seekTo(target)
                                position = target
                            }
                            seeking = false
                        },
                        enabled = duration > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = if (sliderFocused) Color.White else accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = Color(0x55FFFFFF)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { f ->
                                sliderFocused = f.isFocused
                                if (!f.isFocused && seeking) {
                                    seeking = false
                                }
                            }
                            .onPreviewKeyEvent { ev ->
                                // sul telecomando: ←/→ spostano di 30 s quando la barra è selezionata
                                if (ev.type != KeyEventType.KeyDown || duration <= 0) return@onKeyEvent false
                                when (ev.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-30_000); true }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(30_000); true }
                                    else -> false
                                }
                            }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(if (seeking) (seekRatio * duration).toLong() else position), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(formatTime(duration), color = Color(0xFFAAAAAA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: Int,
    description: String,
    sizeDp: Int,
    accent: Color,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(sizeDp.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else if (primary) 1.5.dp else 1.dp,
                color = if (focused) Color.White else if (primary) accent else Color(0x44FFFFFF),
                shape = CircleShape
            )
            .background(
                when {
                    primary && focused -> accent
                    primary -> accent.copy(alpha = 0.6f)
                    focused -> accent.copy(alpha = 0.35f)
                    else -> Color(0x33000000)
                },
                CircleShape
            )
    ) {
        Icon(
            painterResource(icon), description,
            tint = if (primary && focused) Color.Black else Color.White,
            modifier = Modifier.size((sizeDp / 2).dp)
        )
    }
}

@Composable
private fun SubtitleButton(on: Boolean, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) accent else Color(0x33000000))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x66FFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("CC", color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

private fun subtitleMime(url: String): String? {
    val u = url.lowercase().substringBefore('?')
    return when {
        u.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        u.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        u.endsWith(".ttml") || u.endsWith(".dfxp") || u.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}
