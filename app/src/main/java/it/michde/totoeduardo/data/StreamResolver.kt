package it.michde.totoeduardo.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SubtitleTrack(val url: String, val language: String, val label: String)

sealed class Playable {
    data class Stream(
        val url: String,
        val kind: StreamKind,
        val widevineLicenseUrl: String? = null,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val userAgent: String = Http.BROWSER_UA,
        val headers: Map<String, String> = emptyMap()
    ) : Playable()

    /** Da aprire nell'app o nel sito ufficiale (login, abbonamento, YouTube...). */
    data class External(val url: String, val reason: String, val needsSubscription: Boolean = false) : Playable()
}

enum class StreamKind { HLS, DASH, MP4 }

/**
 * Trasforma un titolo del catalogo in un flusso riproducibile, al momento della visione
 * (i link delle CDN scadono dopo pochi minuti). Non aggira protezioni: se un contenuto
 * richiede login/abbonamento si apre l'app ufficiale.
 */
object StreamResolver {

    private const val RAI = "https://www.raiplay.it"
    /** Profili di release ThePlatform: HbbTV (smart TV) e browser, senza Widevine. */
    private val TP_ASSET_PROFILES = listOf(
        "HD,HBBTV,geoIT%7CgeoNo:HD,HBBTV,geoIT%7CgeoNo:HD,geoIT%7CgeoNo:SD,HBBTV,geoIT%7CgeoNo:SD,geoIT%7CgeoNo",
        "geoNo:HD,browser,geoIT%7CgeoNo:HD,geoIT%7CgeoNo:SD,browser,geoIT%7CgeoNo:SD,geoIT%7CgeoNo%7CHD%7CSD"
    )
    suspend fun resolve(film: Film): Playable = withContext(Dispatchers.IO) {
        try {
            when (film.platform) {
                Platforms.RAIPLAY -> resolveRai(film)
                Platforms.MEDIASET -> {
                    val clear = resolveMediaset(film)
                    if (clear is Playable.External && clear.needsSubscription && MediasetAuth.isConfigured) {
                        resolveMediasetWithAccount(film) ?: Playable.External(
                            film.pageUrl, "Mediaset non concede questo titolo al tuo account (forse è Infinity+).", needsSubscription = true
                        )
                    } else clear
                }
                Platforms.LA7 -> Playable.Stream(
                    "https://kdam.iltrovatore.it/p/103/sp/10300/playManifest/entryId/${film.ref}/format/applehttp/protocol/https/a.m3u8",
                    StreamKind.HLS
                )
                Platforms.URL -> Playable.Stream(film.ref, kindOf(film.ref, ""))
                else -> Playable.External(film.pageUrl, "Si apre in ${Platforms.appName(film.platform)}.")
            }
        } catch (e: Exception) {
            Playable.External(film.pageUrl, "Non è stato possibile avviare il video (${e.message ?: "errore di rete"}).")
        }
    }

    /** true = si vede nell'app, false = serve l'abbonamento, null = non verificabile ora. */
    suspend fun mediasetPlayable(film: Film): Boolean? = withContext(Dispatchers.IO) {
        try {
            when (val p = resolveMediaset(film)) {
                is Playable.Stream -> true
                is Playable.External -> if (p.needsSubscription) false else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ RaiPlay

    private fun resolveRai(film: Film): Playable {
        val meta = Http.getJson("$RAI${film.ref}.json")
            ?: return Playable.External(film.pageUrl, "Il titolo non risulta più su RaiPlay.")
        val video = meta.optJSONObject("video") ?: JSONObject()
        val contentUrl = video.optString("content_url").ifBlank { meta.optString("content_url") }
        if (contentUrl.isBlank()) return Playable.External(film.pageUrl, "RaiPlay non fornisce il video per questo titolo.")

        val subs = mutableListOf<SubtitleTrack>()
        listOfNotNull(meta.optJSONArray("subtitlesArray"), video.optJSONArray("subtitlesArray")).forEach { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val u = s.optString("url")
                if (u.isBlank()) continue
                val abs = if (u.startsWith("http")) u else "$RAI$u"
                subs += SubtitleTrack(abs, s.optString("language", "it"), s.optString("label", "Italiano"))
            }
        }

        if (!contentUrl.contains("relinkerServlet")) {
            return Playable.Stream(contentUrl, kindOf(contentUrl, ""), subtitles = subs)
        }

        val relinker = Uri.parse(contentUrl.replace("http://", "https://")).buildUpon()
            .clearQuery().apply {
                val orig = Uri.parse(contentUrl)
                for (k in orig.queryParameterNames) if (k != "output") appendQueryParameter(k, orig.getQueryParameter(k) ?: "")
                appendQueryParameter("output", "56")
            }.build().toString()

        val xml = Http.get(relinker) ?: return Playable.External(film.pageUrl, "RaiPlay non ha risposto.")
        val mediaUrl = cdata(Regex("<url type=\"content\">(.*?)</url>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1))
        if (mediaUrl.isBlank()) return Playable.External(film.pageUrl, "RaiPlay non ha restituito il video.")
        if (mediaUrl.contains("video_no_available")) {
            return Playable.External(film.pageUrl, "RaiPlay rende disponibile questo titolo solo dall'Italia o con accesso al tuo account.")
        }
        val ct = Regex("<ct>(.*?)</ct>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let { cdata(it) }.orEmpty()

        var license: String? = null
        val licenseRaw = cdata(Regex("<license_url>(.*?)</license_url>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1))
        if (licenseRaw.isNotBlank() && licenseRaw != "{}") {
            try {
                val arr = JSONObject(licenseRaw).optJSONArray("drmLicenseUrlValues")
                if (arr != null) for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    if (l.optString("drm").contains("WIDEVINE", ignoreCase = true)) license = l.optString("licenceUrl")
                }
            } catch (e: Exception) {
                // formato inatteso: sotto si decide se aprire RaiPlay
            }
            if (license.isNullOrBlank()) {
                return Playable.External(film.pageUrl, "Contenuto protetto: si guarda nell'app RaiPlay.")
            }
        }
        return Playable.Stream(mediaUrl, kindOf(mediaUrl, ct), widevineLicenseUrl = license, subtitles = subs)
    }

    private fun cdata(s: String?): String {
        if (s == null) return ""
        val m = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL).find(s)
        return (m?.groupValues?.get(1) ?: s).trim()
    }

    // ------------------------------------------------------------------ Mediaset

    /**
     * Come l'app Le Iene: SMIL ThePlatform, si preferisce il manifest "_no_" (in chiaro),
     * altrimenti il primo link restituito che non sia esplicitamente cifrato.
     * "Serve abbonamento" solo se ThePlatform rifiuta per protezione e non dà alcun link.
     */
    private fun resolveMediaset(film: Film): Playable {
        var protectionError = false
        var geoBlocked = false
        val encrypted = listOf("_sampleaes", "_cenc", "_widevine", "_playready", "_fp_", "_drm")
        val candidates = mutableListOf<Pair<String, String>>() // url, formato
        val attempts = TP_ASSET_PROFILES.flatMap { a -> listOf("MPEG-DASH", "M3U", "MPEG4").map { f -> f to a } }
        for ((format, assets) in attempts) {
            val url = "https://link.theplatform.eu/s/PR1GhC/media/guid/2702976343/${film.ref}" +
                "?mbr=true&formats=$format&assetTypes=$assets&format=SMIL"
            val smil = Http.get(url) ?: continue
            if (smil.contains("ProtectionScheme") || smil.contains("None of the available releases")) protectionError = true
            if (smil.contains("GeoLocationBlocked", ignoreCase = true)) geoBlocked = true
            Regex("<video[^>]+src=\"([^\"]+)\"").findAll(smil)
                .map { it.groupValues[1].replace("msf.ticdn.it", "msf.cdn.mediaset.net").replace("netfarmunica", "net/farmunica") }
                .forEach { candidates += it to format }
            candidates.firstOrNull { "_no_" in it.first }?.let { (u, f) ->
                return Playable.Stream(u, kindOf(u, if (f == "MPEG-DASH") "dash" else ""))
            }
        }
        candidates.firstOrNull { (u, _) -> encrypted.none { u.contains(it, ignoreCase = true) } }?.let { (u, f) ->
            return Playable.Stream(u, kindOf(u, if (f == "MPEG-DASH") "dash" else ""))
        }
        return when {
            geoBlocked -> Playable.External(film.pageUrl, "Mediaset rende disponibile questo titolo solo dall'Italia.")
            protectionError || candidates.isNotEmpty() -> Playable.External(
                film.pageUrl,
                "Su Mediaset Infinity questo titolo richiede l'abbonamento: lo tolgo dal catalogo.",
                needsSubscription = true
            )
            else -> Playable.External(film.pageUrl, "Mediaset non ha fornito il video in questo momento.")
        }
    }

    private const val WV_ASSETS =
        "HD,browser,widevine,geoIT%7CgeoNo:HD,browser,geoIT%7CgeoNo:HD,geoIT%7CgeoNo:SD,browser,widevine,geoIT%7CgeoNo:SD,browser,geoIT%7CgeoNo:SD,geoIT%7CgeoNo"

    /**
     * Titolo protetto con l'account Mediaset dell'utente (come l'add-on Kodi ufficiale):
     * manifest DASH "commonEncryption" + licenza Widevine dal server ThePlatform con il token di sessione.
     */
    private fun resolveMediasetWithAccount(film: Film): Playable? {
        val token = MediasetAuth.currentToken() ?: return null
        val url = "https://link.theplatform.eu/s/PR1GhC/media/guid/2702976343/${film.ref}" +
            "?auto=true&balance=true&format=SMIL&formats=MPEG-DASH,MPEG4,M3U&tracking=true&assetTypes=$WV_ASSETS"
        val smil = Http.get(url) ?: return null
        val refTag = Regex("<ref\\s([^>]*)>").find(smil)?.groupValues?.get(1) ?: return null
        val attrs = Regex("(\\w+)=\"([^\"]*)\"").findAll(refTag).associate { it.groupValues[1] to it.groupValues[2] }
        val src = attrs["src"]?.takeIf { it.isNotBlank() } ?: return null
        val type = attrs["type"].orEmpty()
        val headers = mapOf("t-apigw" to token.apigw, "t-cts" to token.cts)
        if (attrs["security"] != "commonEncryption") {
            return Playable.Stream(src, kindOf(src, if ("dash" in type) "dash" else type), headers = headers)
        }
        val tracking = Regex("name=\"trackingData\"\\s+value=\"([^\"]+)\"").find(smil)?.groupValues?.get(1)
            ?: Regex("value=\"([^\"]*pid=[^\"]+)\"\\s+name=\"trackingData\"").find(smil)?.groupValues?.get(1)
            ?: return null
        val pid = tracking.split("|").firstOrNull { it.startsWith("pid=") }?.removePrefix("pid=") ?: return null
        val license = "https://widevine.entitlement.theplatform.eu/wv/web/ModularDrm/getRawWidevineLicense" +
            "?releasePid=$pid&account=http://access.auth.theplatform.com/data/Account/2702976343&schema=1.0&token=${token.cts}"
        return Playable.Stream(src, StreamKind.DASH, widevineLicenseUrl = license, headers = headers)
    }

    private fun kindOf(url: String, contentType: String): StreamKind {
        val u = url.lowercase()
        val ct = contentType.lowercase()
        return when {
            "dash" in ct || ".mpd" in u || "format=mpd" in u -> StreamKind.DASH
            "m3u8" in ct || "hls" in ct || ".m3u8" in u || "format=m3u8" in u -> StreamKind.HLS
            else -> StreamKind.MP4
        }
    }
}
