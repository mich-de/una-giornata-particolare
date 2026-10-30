package it.michde.totoeduardo.data

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/**
 * Filmografia di riferimento (assets/filmografia.json) e regole per riconoscere
 * un titolo trovato su una piattaforma. Stessa logica di scripts/scrape_catalog.py.
 */
class Filmography(context: Context) {

    data class Work(
        val title: String,
        val year: Int?,
        val persons: List<String>,
        val kind: String,
        val n: String,
        val c: String,
        val words: List<String>
    )

    val works: List<Work>

    init {
        val text = context.assets.open("filmografia.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("works")
        works = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("persons")
            val title = o.getString("title")
            val n = norm(title)
            Work(
                title = title,
                year = if (o.isNull("year")) null else o.optInt("year"),
                persons = (0 until p.length()).map { p.getString(it) },
                kind = o.optString("kind", "film"),
                n = n,
                c = n.replace(" ", ""),
                words = n.split(" ").filter { it.isNotEmpty() }
            )
        }
    }

    fun titleMatches(title: String): List<Work> {
        val n = norm(title)
        val c = n.replace(" ", "")
        val words = n.split(" ").filter { it.isNotEmpty() }
        return works.filter { w ->
            c == w.c || (w.words.size >= 2 && w.c.length >= 8 && words.size >= w.words.size &&
                words.subList(0, w.words.size) == w.words)
        }
    }

    fun keywordHit(title: String): Boolean {
        val n = norm(title)
        return listOf("toto", "eduardo", "de filippo", "de crescenzo", "bellavista", "de curtis").any { hasWords(n, it) } ||
            titleMatches(title).isNotEmpty()
    }

    /** Ritorna le persone associate e l'opera della filmografia (se riconosciuta). */
    fun classify(title: String, year: Int?, credits: String, text: String): Pair<List<String>, Work?> {
        val creditsN = norm(credits)
        val textN = norm("$title $text")
        val persons = mutableListOf<String>()
        var best: Work? = null
        for (w in titleMatches(title)) {
            for (p in w.persons) {
                val strong = strongMention(p, creditsN, textN)
                val weak = weakMention(p, textN)
                val yearOk = year != null && w.year != null && abs(year - w.year) <= 1
                val distinctive = w.c.length >= 14 || hasWords(w.n, "toto") || hasWords(w.n, "bellavista")
                val oldEnough = year == null || year <= 1975
                val ok = strong || yearOk || (weak && (p != Persons.TOTO || oldEnough)) || (year == null && distinctive)
                if (ok && p !in persons) {
                    persons += p
                    if (best == null) best = w
                }
            }
        }
        if (persons.isEmpty()) {
            for (p in Persons.ALL) if (strongMention(p, creditsN, textN)) persons += p
        }
        return persons to best
    }

    companion object {
        fun norm(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val d = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
            return d.replace("&", " e ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        }

        fun hasWords(textNorm: String, phrase: String): Boolean {
            val p = norm(phrase)
            if (p.isEmpty()) return false
            return Regex("(?:^| )" + Regex.escape(p) + "(?: |$)").containsMatchIn(textNorm)
        }

        fun strongMention(person: String, creditsN: String, textN: String): Boolean = when (person) {
            Persons.TOTO -> hasWords(creditsN, "toto") || "de curtis" in textN || "de curtis" in creditsN
            Persons.EDUARDO -> "eduardo de filippo" in creditsN || "eduardo de filippo" in textN
            Persons.DECRESCENZO -> "de crescenzo" in creditsN || "luciano de crescenzo" in textN
            else -> false
        }

        fun weakMention(person: String, textN: String): Boolean = when (person) {
            Persons.TOTO -> hasWords(textN, "toto")
            Persons.EDUARDO -> hasWords(textN, "eduardo") || "de filippo" in textN
            Persons.DECRESCENZO -> "de crescenzo" in textN || hasWords(textN, "bellavista")
            else -> false
        }

        fun slugify(s: String): String =
            norm(s).replace(" ", "-").replace(Regex("-+"), "-").trim('-').ifBlank { "video" }
    }
}
