package ca.rocklogik.connect.cloud

import ca.rocklogik.connect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** rocklogik-cloud over HTTPS (plan section 7). Plain HttpURLConnection: three routes need no more. */
class CloudApi(private val baseUrl: String = BuildConfig.CLOUD_BASE_URL) {

    /** A refusal the user can act on, by the cloud's error name. */
    class Refused(val reason: String, val status: Int) : Exception(reason)

    data class SignedIn(val token: String, val name: String, val email: String)

    /** Exchanges a Google ID token for a rocklogik-cloud token (POST /api/auth/google). */
    suspend fun signIn(idToken: String, deviceName: String): SignedIn {
        val (status, body) = post("/api/auth/google", JSONObject().put("id_token", idToken).put("device_name", deviceName), null)
        if (status != 200) throw Refused(body?.optString("error")?.ifEmpty { null } ?: "http_$status", status)
        val user = body!!.getJSONObject("user")
        return SignedIn(body.getString("token"), user.optString("name"), user.optString("email"))
    }

    /** Revokes the token. Failure is ignored: the phone forgets it either way. */
    suspend fun signOut(token: String) {
        runCatching { post("/api/auth/logout", JSONObject(), token) }
    }

    private suspend fun post(path: String, json: JSONObject, token: String?): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        val c = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to (if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull())
        } catch (e: IOException) {
            throw Refused("unreachable", 0)
        } finally {
            c.disconnect()
        }
    }
}
