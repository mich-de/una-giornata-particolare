package it.michde.totoeduardo.data

import android.content.Context
import android.content.SharedPreferences

class PlaybackProgressTracker(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ted_playback_prefs", Context.MODE_PRIVATE)

    fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (positionMs > 10_000L && (durationMs <= 0 || positionMs < durationMs - 60_000L)) {
            prefs.edit().putLong("pos_$videoId", positionMs).putLong("dur_$videoId", durationMs.coerceAtLeast(0)).apply()
        } else if (durationMs > 0 && positionMs >= durationMs - 60_000L) {
            clearProgress(videoId) // visto fino ai titoli di coda
        }
    }

    fun getProgress(videoId: String): Long = prefs.getLong("pos_$videoId", 0L)

    /** Frazione vista (0..1), 0 se non nota. */
    fun getRatio(videoId: String): Float {
        val d = prefs.getLong("dur_$videoId", 0L)
        if (d <= 0) return 0f
        return (getProgress(videoId).toFloat() / d).coerceIn(0f, 1f)
    }

    fun clearProgress(videoId: String) {
        prefs.edit().remove("pos_$videoId").remove("dur_$videoId").apply()
    }
}
