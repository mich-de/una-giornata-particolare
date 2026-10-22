package it.la7.unagiornataparticolare.data

data class UgpEpisode(
    val title: String,
    val date: String,
    val description: String,
    val thumb: String,
    val pageUrl: String,
    val entryId: String,
    val resolution: String = "720p",
    val hlsUrl: String,
    val directMp4: String,
    val hasSubtitles: Boolean = false
)
