package it.michde.totoeduardo.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** GET testuale; null se la risposta non è 2xx o in caso di errore di rete. */
    fun get(url: String, userAgent: String = BROWSER_UA): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "it-IT,it;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    fun getJson(url: String): JSONObject? = get(url)?.let {
        try {
            JSONObject(it)
        } catch (e: Exception) {
            null
        }
    }
}
