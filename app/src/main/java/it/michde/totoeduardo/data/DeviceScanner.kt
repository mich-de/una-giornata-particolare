package it.michde.totoeduardo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scansione eseguita sul dispositivo (in Italia, quindi senza geoblocchi) di RaiPlay e
 * Mediaset Infinity. Versione leggera di scripts/scrape_catalog.py: la CI fa la scansione
 * completa, qui si aggiunge ciò che è comparso nel frattempo.
 */
class DeviceScanner(private val fm: Filmography) {

    data class Result(val films: List<Film>, val report: List<String>, val okPlatforms: Set<String>)

    private val gate = Semaphore(8)

    suspend fun scan(onProgress: (String, Float) -> Unit): Result = withContext(Dispatchers.IO) {
        val report = mutableListOf<String>()
        val ok = mutableSetOf<String>()
        val films = mutableListOf<Film>()

        onProgress("RaiPlay: indice dei programmi…", 0.02f)
        val rai = scanRaiPlay { done, total -> onProgress("RaiPlay: $done/$total", 0.05f + 0.55f * done / total.coerceAtLeast(1)) }
        report += "RaiPlay: ${rai.size} titoli"
        if (rai.isNotEmpty()) ok += Platforms.RAIPLAY
        films += rai

        val ms = scanMediaset { done, total -> onProgress("Mediaset Infinity: $done/$total", 0.6f + 0.38f * done / total.coerceAtLeast(1)) }
        report += "Mediaset Infinity: ${ms.size} titoli"
        if (ms.isNotEmpty()) ok += Platforms.MEDIASET
        films += ms

        onProgress("Completato", 1f)
        Result(films.distinctBy { it.id }, report, ok)
    }

    // ------------------------------------------------------------------ RaiPlay

    private suspend fun scanRaiPlay(progress: (Int, Int) -> Unit): List<Film> = coroutineScope {
        val candidates = LinkedHashMap<String, String>()
        Http.getJson("$RAI/dl/RaiTV/RaiPlayMobile/Prod/Config/programmiAZ-elenco.json")?.let { az ->
            for (letter in az.keys()) {
                val items = az.optJSONArray(letter) ?: continue
                for (i in 0 until items.length()) {
                    val entry = items.optJSONObject(i) ?: continue
                    val name = entry.optString("name").ifBlank { entry.optString("title") }
                    val url = programJsonUrl(entry.optString("PathID").ifBlank { entry.optString("path_id") })
                    if (url.isNotBlank() && fm.keywordHit(name)) candidates[url] = name
                }
            }
        }
        for (w in fm.works) candidates.putIfAbsent("$RAI/programmi/${w.c}.json", w.title)

        val total = candidates.size
        val done = AtomicInteger(0)
        candidates.keys.map { url ->
            async {
                gate.withPermit {
                    val r = try { checkRaiProgram(url, candidates[url].orEmpty()) } catch (e: Exception) { emptyList() }
                    progress(done.incrementAndGet(), total)
                    r
                }
            }
        }.awaitAll().flatten()
    }

    private fun checkRaiProgram(url: String, fallbackName: String): List<Film> {
        val p = Http.getJson(url) ?: return emptyList()
        val info = p.optJSONObject("program_info") ?: p
        val name = info.optString("name").ifBlank { p.optString("name") }.ifBlank { fallbackName }
        val year = parseYear(info.optString("year"))
        val director = info.optString("direction")
        val actors = info.optString("actors")
        val credits = listOf("actors", "direction", "conductor", "authors", "credits").joinToString(" ") { info.optString(it) }
        val desc = info.optString("description").ifBlank { info.optString("vanity") }
        val typos = buildList {
            info.optJSONArray("typologies")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("nome")?.let { n -> add(n) } }
            info.optJSONArray("genres")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("nome")?.let { n -> add(n) } }
        }.map { Filmography.norm(it) }
        val platformKind = when {
            "film" in typos -> "film"
            typos.any { "teatr" in it || "prosa" in it } -> "teatro"
            typos.any { "document" in it } -> "documentario"
            else -> "tv"
        }
        val thumb = raiImage(info.optJSONObject("images") ?: p.optJSONObject("images"))
        val first = p.optString("first_item_path")
        val out = mutableListOf<Film>()

        if (first.isNotBlank()) {
            val (persons, work) = fm.classify(name, year, credits, desc)
            if (persons.isNotEmpty()) {
                val ref = videoRef(first)
                out += Film(
                    id = "raiplay:$ref", title = name, year = year ?: work?.year, persons = persons,
                    kind = work?.kind ?: platformKind, platform = Platforms.RAIPLAY, ref = ref,
                    pageUrl = "$RAI$ref.html", thumb = thumb, description = desc, duration = 0, drm = null,
                    director = director, cast = actors, source = name, matched = work?.title.orEmpty()
                )
            }
            return out
        }

        val (progPersons, _) = fm.classify(name, year, credits, desc)
        val base = url.removeSuffix(".json")
        val blocks = p.optJSONArray("blocks") ?: return out
        for (b in 0 until blocks.length()) {
            val sets = blocks.optJSONObject(b)?.optJSONArray("sets") ?: continue
            for (s in 0 until sets.length()) {
                val sid = sets.optJSONObject(s)?.optString("id").orEmpty()
                if (sid.isBlank()) continue
                val items = Http.getJson("$base/$sid.json")?.optJSONArray("items") ?: continue
                for (i in 0 until minOf(items.length(), 300)) {
                    val item = items.optJSONObject(i) ?: continue
                    val path = item.optString("path_id")
                    if ("/video/" !in path) continue
                    val t = item.optString("name").ifBlank { item.optString("episode_title") }.ifBlank { name }
                    val itemDesc = item.optString("description")
                    var (persons, work) = fm.classify(t, null, credits, "$name $desc $itemDesc")
                    if (persons.isEmpty() && progPersons.isNotEmpty()) {
                        val m = fm.titleMatches(t)
                        if (m.isNotEmpty()) {
                            persons = progPersons; work = m.first()
                        } else if (progPersons.size == 1 && fm.keywordHit(name)) {
                            persons = progPersons
                        }
                    }
                    if (persons.isEmpty()) continue
                    val ref = videoRef(path)
                    out += Film(
                        id = "raiplay:$ref", title = t, year = work?.year, persons = persons,
                        kind = work?.kind ?: platformKind, platform = Platforms.RAIPLAY, ref = ref,
                        pageUrl = "$RAI$ref.html", thumb = raiImage(item.optJSONObject("images")).ifBlank { thumb },
                        description = itemDesc.ifBlank { desc }, duration = parseDuration(item.optString("duration")),
                        drm = null, director = director, cast = actors, source = name, matched = work?.title.orEmpty()
                    )
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Mediaset

    private suspend fun scanMediaset(progress: (Int, Int) -> Unit): List<Film> = coroutineScope {
        val queries = (listOf("Totò", "Antonio de Curtis", "Eduardo De Filippo", "Luciano De Crescenzo", "Bellavista") +
            fm.works.map { it.title }).distinct()
        val now = System.currentTimeMillis()
        val done = AtomicInteger(0)
        val entries = queries.map { q ->
            async {
                gate.withPermit {
                    val url = "$MS_FEED?q=${URLEncoder.encode(q, "UTF-8")}&range=1-60"
                    val arr = Http.getJson(url)?.optJSONArray("entries")
                    progress(done.incrementAndGet(), queries.size)
                    if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
            }
        }.awaitAll().flatten()

        entries.distinctBy { it.optString("guid") }.mapNotNull { e -> mediasetEntry(e, now) }
    }

    private fun mediasetEntry(e: JSONObject, now: Long): Film? {
        val guid = e.optString("guid")
        if (guid.isBlank()) return null
        val type = e.optString("programType")
        if (type.isNotBlank() && type != "movie" && type != "episode") return null
        e.optJSONArray("media")?.let { media ->
            if (media.length() > 0) {
                val available = (0 until media.length()).any { i ->
                    val m = media.optJSONObject(i) ?: return@any false
                    val a = m.optLong("availableDate", 0L)
                    val x = m.optLong("expirationDate", 0L)
                    (a == 0L || a <= now) && (x == 0L || x > now)
                }
                if (!available) return null
            }
        }
        val title = e.optString("title")
        val year = parseYear(e.opt("year")?.toString())
        val credits = buildString {
            append(e.opt("credits")?.toString().orEmpty())
            for (k in e.keys()) {
                val lk = k.lowercase()
                if (listOf("cast", "director", "regia", "actor", "people").any { it in lk }) append(' ').append(e.opt(k).toString())
            }
        }
        val desc = e.optString("description").ifBlank { e.optString("longDescription") }
        val brand = e.optString("mediasetprogram\$brandTitle")
        val (persons, work) = fm.classify(title, year, credits, "$desc $brand")
        if (persons.isEmpty()) return null

        var page = e.optString("mediasetprogram\$videoPageUrl").ifBlank { e.optString("mediasetprogram\$pageUrl") }
        if (page.startsWith("//")) page = "https:$page"
        if (page.isBlank()) {
            val s = Filmography.slugify(title)
            page = if (type == "movie") "https://mediasetinfinity.mediaset.it/movie/$s/${s}_$guid"
            else "https://mediasetinfinity.mediaset.it/video/${Filmography.slugify(brand.ifBlank { title }).replace("-", "")}/${s}_$guid"
        }
        return Film(
            id = "mediaset:$guid", title = title, year = year ?: work?.year, persons = persons,
            kind = work?.kind ?: if (type == "movie") "film" else "tv",
            platform = Platforms.MEDIASET, ref = guid, pageUrl = page, thumb = mediasetThumb(e),
            description = desc, duration = 0, drm = null, director = "", cast = "", source = brand,
            matched = work?.title.orEmpty()
        )
    }

    private fun mediasetThumb(e: JSONObject): String {
        val th = e.optJSONObject("thumbnails") ?: return ""
        var best = ""
        var score = -1
        for (k in th.keys()) {
            val v = th.optJSONObject(k) ?: continue
            val url = v.optString("url")
            val w = v.optInt("width")
            val h = v.optInt("height")
            if (url.isBlank() || w < 400 || w > 1920) continue
            val s = w + (if ("keyframe" in k) 5000 else 0) + (if ("poster" !in k && w >= h) 3000 else 0)
            if (s > score) {
                best = url; score = s
            }
        }
        return best
    }

    companion object {
        const val RAI = "https://www.raiplay.it"
        const val MS_FEED = "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"

        fun programJsonUrl(path: String): String {
            var p = path.trim().replace("?json", "")
            if (p.startsWith("http")) p = java.net.URI(p).path ?: ""
            if (p.startsWith("/raiplay/")) p = p.removePrefix("/raiplay")
            p = p.trimEnd('/').removeSuffix("/index.json").removeSuffix(".html").removeSuffix(".json")
            return if (p.isBlank()) "" else "$RAI$p.json"
        }

        fun videoRef(path: String): String {
            var p = path.trim()
            if (p.startsWith("http")) p = java.net.URI(p).path ?: p
            return p.removeSuffix(".json").removeSuffix(".html")
        }

        fun raiImage(images: JSONObject?): String {
            if (images == null) return ""
            for (k in listOf("landscape", "landscape_logo", "landscape43", "portrait_logo", "portrait", "portrait43", "square")) {
                val v = images.optString(k)
                if (v.isNotBlank()) {
                    val u = v.replace("[RESOLUTION]", "640x-")
                    return when {
                        u.startsWith("//") -> "https:$u"
                        u.startsWith("http") -> u
                        else -> "$RAI$u"
                    }
                }
            }
            return ""
        }

        fun parseYear(v: String?): Int? =
            v?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

        fun parseDuration(v: String?): Int {
            if (v.isNullOrBlank()) return 0
            val parts = Regex("\\d+").findAll(v).map { it.value.toInt() }.toList()
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> 0
            }
        }
    }
}
