package it.michde.totoeduardo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Accesso con l'account Mediaset dell'utente (gratuito), come l'add-on Kodi ufficiale
 * "Mediaset Infinity": login Gigya -> token di sessione (t-apigw / t-cts) usato per
 * ottenere la licenza Widevine dei titoli protetti dal server di licenze ThePlatform.
 */
object MediasetAuth {

    data class Token(val apigw: String, val cts: String, val time: Long)

    private const val GIGYA_API_KEY = "3_l-A-KKZVONJdGd272x41mezO6AUV4mUoxOdZCMfccvEXAJa6COVXyT_tUdQI03dh"
    private const val TOKEN_TTL_MS = 4 * 60 * 60 * 1000L

    private var prefs: SharedPreferences? = null

    @Volatile
    private var token: Token? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("mediaset_account", Context.MODE_PRIVATE)
    }

    val email: String get() = prefs?.getString("email", "").orEmpty()
    private val password: String get() = prefs?.getString("password", "").orEmpty()
    val isConfigured: Boolean get() = email.isNotBlank() && password.isNotBlank()

    fun logout() {
        token = null
        prefs?.edit()?.clear()?.apply()
    }

    /** Ritorna null se l'accesso riesce, altrimenti il messaggio d'errore. */
    suspend fun login(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val t = doLogin(email.trim(), password)
            token = t
            prefs?.edit()?.putString("email", email.trim())?.putString("password", password)?.apply()
            null
        } catch (e: Exception) {
            e.message ?: "Accesso non riuscito"
        }
    }

    /** Token valido (rinnovato se scaduto); da chiamare fuori dal main thread. */
    fun currentToken(): Token? {
        token?.let { if (System.currentTimeMillis() - it.time < TOKEN_TTL_MS) return it }
        if (!isConfigured) return null
        return try {
            doLogin(email, password).also { token = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun doLogin(email: String, password: String): Token {
        val form = FormBody.Builder()
            .add("loginID", email)
            .add("password", password)
            .add("sessionExpiration", "31536000")
            .add("targetEnv", "jssdk")
            .add("include", "profile,data,emails,subscriptions,preferences,id_token,")
            .add("includeUserInfo", "true")
            .add("loginMode", "standard")
            .add("lang", "it")
            .add("APIKey", GIGYA_API_KEY)
            .add("cid", "mediaset-web-mediaset.it programmi-mediaset Default")
            .add("source", "showScreenSet")
            .add("sdk", "js_latest")
            .add("authMode", "cookie")
            .add("pageURL", "https://mediasetinfinity.mediaset.it/")
            .add("format", "json")
            .build()
        val gigyaText = Http.client.newCall(
            Request.Builder().url("https://login.mediaset.it/accounts.login")
                .header("User-Agent", Http.BROWSER_UA).post(form).build()
        ).execute().use { it.body?.string().orEmpty() }
        val cleaned = gigyaText.trim().removePrefix("gigya.callback(").removeSuffix(";").removeSuffix(")")
        val g = JSONObject(cleaned)
        if (g.optInt("errorCode", -1) != 0) {
            throw IllegalStateException(
                g.optString("errorDetails").ifBlank { g.optString("errorMessage") }.ifBlank { "Email o password errate" }
            )
        }

        val body = JSONObject()
            .put("platform", "pc")
            .put("UID", g.getString("UID"))
            .put("UIDSignature", g.getString("UIDSignature"))
            .put("signatureTimestamp", g.get("signatureTimestamp").toString())
            .put("appName", "web/mediasetplay-web/bd16667")
        Http.client.newCall(
            Request.Builder().url("https://api-ott-prod-fe.mediaset.net/PROD/play/idm/account/login/v1.0")
                .header("User-Agent", Http.BROWSER_UA)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            val apigw = resp.header("t-apigw").orEmpty()
            val cts = resp.header("t-cts").orEmpty()
            val ok = try {
                JSONObject(resp.body?.string().orEmpty()).optBoolean("isOk")
            } catch (e: Exception) {
                false
            }
            if (!ok || cts.isBlank()) throw IllegalStateException("Mediaset non ha confermato l'accesso")
            return Token(apigw, cts, System.currentTimeMillis())
        }
    }
}
