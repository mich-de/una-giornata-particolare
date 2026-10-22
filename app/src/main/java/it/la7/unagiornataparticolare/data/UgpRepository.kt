package it.la7.unagiornataparticolare.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class UgpRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getEpisodes(): List<UgpEpisode> = withContext(Dispatchers.IO) {
        val cached = loadFromAssets()
        try {
            val live = fetchLiveLatest()
            if (live.isNotEmpty()) {
                val combined = mutableListOf<UgpEpisode>()
                val seenIds = mutableSetOf<String>()
                live.forEach {
                    if (seenIds.add(it.entryId)) combined.add(it)
                }
                cached.forEach {
                    if (seenIds.add(it.entryId)) combined.add(it)
                }
                return@withContext combined
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext cached
    }

    fun loadFromAssets(): List<UgpEpisode> {
        val list = mutableListOf<UgpEpisode>()
        try {
            val jsonString = context.assets.open("puntate_ugp.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val entryId = obj.optString("entry_id", "")
                if (entryId.isBlank()) continue
                list.add(
                    UgpEpisode(
                        title = obj.optString("title"),
                        date = obj.optString("date"),
                        description = obj.optString("description"),
                        thumb = obj.optString("thumb"),
                        pageUrl = obj.optString("page_url"),
                        entryId = entryId,
                        resolution = obj.optString("resolution", "720p"),
                        hlsUrl = obj.optString("hls_url"),
                        directMp4 = obj.optString("direct_mp4"),
                        hasSubtitles = obj.optBoolean("has_subtitles", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun fetchLiveLatest(): List<UgpEpisode> {
        val results = mutableListOf<UgpEpisode>()
        val url = "https://www.la7.it/una-giornata-particolare/rivedila7?page=0"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return emptyList()
        val html = resp.body?.string() ?: return emptyList()

        val linkPattern = Pattern.compile("href=[\"'](/una-giornata-particolare/rivedila7/[a-zA-Z0-9\\-_]+)[\"']")
        val matcher = linkPattern.matcher(html)
        val links = mutableSetOf<String>()
        while (matcher.find()) {
            val link = matcher.group(1)
            if (!link.endsWith("/rivedila7")) {
                links.add("https://www.la7.it$link")
            }
        }

        for (epUrl in links.take(5)) {
            try {
                val pReq = Request.Builder().url(epUrl).header("User-Agent", "Mozilla/5.0").build()
                val pResp = client.newCall(pReq).execute()
                if (!pResp.isSuccessful) continue
                val pHtml = pResp.body?.string() ?: continue

                val idMatcher = Pattern.compile("0_[a-z0-9]{8}").matcher(pHtml)
                if (!idMatcher.find()) continue
                val entryId = idMatcher.group(0)

                val titleMatcher = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(pHtml)
                val title = if (titleMatcher.find()) titleMatcher.group(1).replace(" | LA7", "").trim() else ""

                val descMatcher = Pattern.compile("<meta property=\"og:description\" content=\"([^\"]+)\"").matcher(pHtml)
                val desc = if (descMatcher.find()) descMatcher.group(1).trim() else ""

                val imgMatcher = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(pHtml)
                val thumb = if (imgMatcher.find()) imgMatcher.group(1).trim() else ""

                val dateMatcher = Pattern.compile("([0-9]{2}/[0-9]{2}/[0-9]{4})").matcher(pHtml)
                val dateStr = if (dateMatcher.find()) dateMatcher.group(1).trim() else ""

                val hls = "https://kdam.iltrovatore.it/p/103/sp/10300/playManifest/entryId/$entryId/format/applehttp/protocol/https/a.m3u8"
                val mp4 = "https://awsvodpkg.iltrovatore.it/content/entry/data/0/737/${entryId}_0_cxxbx07r_1.mp4"

                results.add(
                    UgpEpisode(
                        title = title,
                        date = dateStr,
                        description = desc,
                        thumb = thumb,
                        pageUrl = epUrl,
                        entryId = entryId,
                        resolution = "720p",
                        hlsUrl = hls,
                        directMp4 = mp4,
                        hasSubtitles = false
                    )
                )
            } catch (e: Exception) {
                // skip
            }
        }
        return results
    }
}
