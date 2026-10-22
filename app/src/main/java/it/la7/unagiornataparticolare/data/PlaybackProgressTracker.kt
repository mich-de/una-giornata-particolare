package it.la7.unagiornataparticolare.data

import android.content.Context
import android.content.SharedPreferences

class PlaybackProgressTracker(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ugp_playback_prefs", Context.MODE_PRIVATE)

    fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (positionMs > 10_000L && (durationMs <= 0 || positionMs < durationMs - 15_000L)) {
            prefs.edit().putLong("pos_$videoId", positionMs).apply()
        } else if (durationMs > 0 && positionMs >= durationMs - 15_000L) {
            clearProgress(videoId)
        }
    }

    fun getProgress(videoId: String): Long {
        return prefs.getLong("pos_$videoId", 0L)
    }

    fun clearProgress(videoId: String) {
        prefs.edit().remove("pos_$videoId").apply()
    }
}
