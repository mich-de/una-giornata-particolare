package it.michde.totoeduardo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catalogo: copia in cache (filesDir) > versione su GitHub (aggiornata ogni settimana dalla CI)
 * > copia inclusa nell'APK. La scansione sul dispositivo aggiunge i titoli nuovi.
 */
class CatalogRepository(private val context: Context) {

    val filmography by lazy { Filmography(context) }
    private val cacheFile = File(context.filesDir, "catalogo.json")

    suspend fun loadLocal(): Catalog = withContext(Dispatchers.IO) {
        val cached = if (cacheFile.exists()) parse(cacheFile.readText(), "cache") else null
        val bundled = try {
            parse(context.assets.open("catalogo.json").bufferedReader().use { it.readText() }, "app")
        } catch (e: Exception) {
            null
        }
        listOfNotNull(cached, bundled).maxByOrNull { it.generated } ?: Catalog(emptyList(), "", "vuoto")
    }

    /** Scarica il catalogo pubblicato dalla CI; lo unisce a quello locale se è più recente. */
    suspend fun refreshRemote(current: Catalog): Catalog = withContext(Dispatchers.IO) {
        val remote = Http.get(REMOTE_URL)?.let { parse(it, "github") } ?: return@withContext current
        if (remote.films.isEmpty() || (remote.generated <= current.generated && current.films.isNotEmpty())) {
            return@withContext current
        }
        save(remote)
        remote
    }

    suspend fun scanDevice(current: Catalog, onProgress: (String, Float) -> Unit): Pair<Catalog, List<String>> =
        withContext(Dispatchers.IO) {
            val res = DeviceScanner(filmography).scan(onProgress)
            val byId = LinkedHashMap<String, Film>()
            current.films.forEach { byId[it.id] = it }
            for (f in res.films) {
                val old = byId[f.id]
                // conserva i metadati più ricchi (DRM, durata) raccolti dalla CI
                byId[f.id] = if (old == null) f else old.copy(
                    thumb = old.thumb.ifBlank { f.thumb },
                    description = old.description.ifBlank { f.description },
                    persons = (old.persons + f.persons).distinct()
                )
            }
            val merged = Catalog(sortFilms(byId.values.toList()), nowIso(), "dispositivo")
            save(merged)
            merged to res.report
        }

    private fun save(c: Catalog) {
        val o = JSONObject()
        o.put("generated", c.generated)
        o.put("origin", c.origin)
        o.put("films", JSONArray().apply { c.films.forEach { put(it.toJson()) } })
        cacheFile.writeText(o.toString())
    }

    private fun parse(text: String, origin: String): Catalog? = try {
        val o = JSONObject(text)
        val arr = o.optJSONArray("films") ?: JSONArray()
        val films = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Film.fromJson(o) } }
        Catalog(sortFilms(films), o.optString("generated"), o.optString("origin").ifBlank { origin })
    } catch (e: Exception) {
        null
    }

    companion object {
        const val REMOTE_URL =
            "https://raw.githubusercontent.com/mich-de/toto-eduardo-decrescenzo/main/app/src/main/assets/catalogo.json"

        private val personOrder = Persons.ALL.withIndex().associate { it.value to it.index }

        fun sortFilms(films: List<Film>): List<Film> = films.sortedWith(
            compareBy<Film>({ f -> f.persons.minOf { personOrder[it] ?: 9 } }, { it.year ?: 9999 }, { Filmography.norm(it.title) })
        )

        fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        fun prettyDate(iso: String): String = try {
            val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(p.parse(iso)!!)
        } catch (e: Exception) {
            ""
        }
    }
}
