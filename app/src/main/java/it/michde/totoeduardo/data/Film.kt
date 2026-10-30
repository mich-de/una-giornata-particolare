package it.michde.totoeduardo.data

import org.json.JSONArray
import org.json.JSONObject

data class Film(
    val id: String,
    val title: String,
    val year: Int?,
    val persons: List<String>,
    val kind: String,
    val platform: String,
    val ref: String,
    val pageUrl: String,
    val thumb: String,
    val description: String,
    val duration: Int,
    val drm: Boolean?,
    val director: String,
    val cast: String,
    val source: String,
    val matched: String
) {
    /** I titoli che si aprono solo nell'app/sito ufficiale. */
    val opensExternally: Boolean
        get() = platform == Platforms.YOUTUBE || (platform == Platforms.MEDIASET && drm == true)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("year", year ?: JSONObject.NULL)
        put("persons", JSONArray(persons))
        put("kind", kind)
        put("platform", platform)
        put("ref", ref)
        put("page_url", pageUrl)
        put("thumb", thumb)
        put("description", description)
        put("duration", duration)
        put("drm", drm ?: JSONObject.NULL)
        put("director", director)
        put("cast", cast)
        put("source", source)
        put("matched", matched)
    }

    companion object {
        fun fromJson(o: JSONObject): Film? {
            val id = o.optString("id")
            val ref = o.optString("ref")
            if (id.isBlank() || ref.isBlank()) return null
            val personsArr = o.optJSONArray("persons") ?: JSONArray()
            val persons = (0 until personsArr.length()).map { personsArr.optString(it) }.filter { it.isNotBlank() }
            if (persons.isEmpty()) return null
            return Film(
                id = id,
                title = o.optString("title"),
                year = if (o.isNull("year")) null else o.optInt("year").takeIf { it > 0 },
                persons = persons,
                kind = o.optString("kind", "film"),
                platform = o.optString("platform"),
                ref = ref,
                pageUrl = o.optString("page_url"),
                thumb = o.optString("thumb"),
                description = o.optString("description"),
                duration = o.optInt("duration", 0),
                drm = if (o.isNull("drm") || !o.has("drm")) null else o.optBoolean("drm"),
                director = o.optString("director"),
                cast = o.optString("cast"),
                source = o.optString("source"),
                matched = o.optString("matched")
            )
        }
    }
}

object Persons {
    const val TOTO = "toto"
    const val EDUARDO = "eduardo"
    const val DECRESCENZO = "decrescenzo"
    val ALL = listOf(TOTO, EDUARDO, DECRESCENZO)

    fun label(p: String): String = when (p) {
        TOTO -> "Totò"
        EDUARDO -> "Eduardo"
        DECRESCENZO -> "De Crescenzo"
        else -> p
    }

    fun fullName(p: String): String = when (p) {
        TOTO -> "Totò"
        EDUARDO -> "Eduardo De Filippo"
        DECRESCENZO -> "Luciano De Crescenzo"
        else -> p
    }
}

object Platforms {
    const val RAIPLAY = "raiplay"
    const val MEDIASET = "mediaset"
    const val LA7 = "la7"
    const val YOUTUBE = "youtube"
    const val URL = "url"
    val ALL = listOf(RAIPLAY, MEDIASET, LA7, YOUTUBE, URL)

    fun label(p: String): String = when (p) {
        RAIPLAY -> "RaiPlay"
        MEDIASET -> "Infinity"
        LA7 -> "LA7"
        YOUTUBE -> "YouTube"
        URL -> "Link"
        else -> p
    }

    fun appName(p: String): String = when (p) {
        RAIPLAY -> "RaiPlay"
        MEDIASET -> "Mediaset Infinity"
        LA7 -> "LA7"
        YOUTUBE -> "YouTube"
        else -> "il browser"
    }
}

object Kinds {
    val ALL = listOf("film", "teatro", "documentario", "tv")

    fun label(k: String): String = when (k) {
        "film" -> "Film"
        "teatro" -> "Teatro"
        "documentario" -> "Documentari"
        "tv" -> "TV"
        else -> k.replaceFirstChar { it.uppercase() }
    }
}

data class Catalog(
    val films: List<Film>,
    val generated: String,
    val origin: String
)
