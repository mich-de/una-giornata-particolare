package it.michde.totoeduardo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import it.michde.totoeduardo.data.Persons
import it.michde.totoeduardo.data.Platforms

/** Palette "sala d'essai": nero caldo, avorio, oro; un colore per ogni autore. */
object Palette {
    val Background = Color(0xFF110F0E)
    val Surface = Color(0xFF1C1918)
    val SurfaceHigh = Color(0xFF26211F)
    val Border = Color(0xFF332C29)
    val Text = Color(0xFFF3EADC)
    val TextMuted = Color(0xFFA89C8E)
    val Gold = Color(0xFFDDAA4A)

    val Toto = Color(0xFFE6B655)        // oro: il principe della risata
    val Eduardo = Color(0xFFD0583F)     // rosso sipario
    val DeCrescenzo = Color(0xFF55A3D4) // azzurro golfo

    fun person(p: String): Color = when (p) {
        Persons.TOTO -> Toto
        Persons.EDUARDO -> Eduardo
        Persons.DECRESCENZO -> DeCrescenzo
        else -> Gold
    }

    fun platform(p: String): Color = when (p) {
        Platforms.RAIPLAY -> Color(0xFF3D7BEB)
        Platforms.MEDIASET -> Color(0xFF8B5CF6)
        Platforms.LA7 -> Color(0xFFE23B3B)
        Platforms.YOUTUBE -> Color(0xFFE53935)
        else -> Color(0xFF7A7A7A)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Background,
            surface = Palette.Surface,
            primary = Palette.Gold,
            onPrimary = Color.Black,
            onBackground = Palette.Text,
            onSurface = Palette.Text
        ),
        content = content
    )
}

fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}min" else "${m} min"
}
