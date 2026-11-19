package app.datame.movil

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A volume of the house this phone can send files to. */
data class RemoteVolume(val id: String, val name: String, val used: Long, val quota: Long)

/** Who the token belongs to, and what it reaches. */
data class Me(val name: String, val volumes: List<RemoteVolume>, val active: Boolean)

sealed class UploadResult {
    data object Sent : UploadResult()
    /** Something is already there with that name, of this size. */
    data class Exists(val size: Long) : UploadResult()
    data object Unauthorized : UploadResult()
    data object Standby : UploadResult()
    data object Full : UploadResult()
    data class Failed(val message: String) : UploadResult()
    data object Cancelled : UploadResult()
}

class NoNasException(message: String) : IOException(message)

/**
 * Talks to the NAS. The NAS has a certificate of its own, which no authority
 * signs: it is recognized by the fingerprint of its key, which came in the code,
 * the same way the machines of a house recognize each other. Any other key is
 * refused, whatever its name or address.
 */
class NasClient(private val pin: String, private val token: String) {

    private val trust = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Sin certificado")
            val sum = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            val hex = sum.joinToString("") { "%02x".format(it) }
            if (hex != pin) throw CertificateException("Este no es tu NAS: su clave no coincide")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("No se esperan clientes")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory, trust)
        .hostnameVerifier { _, _ -> true } // the key says who it is; the address may change at home
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private var base: HttpUrl? = null

    /**
     * Finds the NAS among its addresses, the one that answered last first.
     * Returns the address that answered.
     */
    fun connect(addresses: List<String>): Pair<String, Me> {
        var problem: String? = null
        for (address in addresses) {
            val url = runCatching { address.toHttpUrl() }.getOrNull() ?: continue
            try {
                val me = me(url)
                base = url
                return address to me
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: IOException) {
                if (e.cause is CertificateException || e is javax.net.ssl.SSLPeerUnverifiedException) problem = "Hay un equipo en ${url.host} que no es tu NAS."
            }
        }
        throw NoNasException(problem ?: "No encuentro el NAS. ¿Estás en la red de casa?")
    }

    class UnauthorizedException : IOException("Este móvil ya no está autorizado. Vuelve a emparejarlo desde la web del NAS.")

    private fun me(url: HttpUrl): Me {
        val request = Request.Builder().url(url.newBuilder().encodedPath("/v0/me").build()).header("Authorization", "Bearer $token").build()
        http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (!response.isSuccessful) throw IOException("El NAS respondió ${response.code}")
            val json = JSONObject(response.body.string())
            val volumes = json.optJSONArray("volumes")?.let { array ->
                (0 until array.length()).map { i ->
                    array.getJSONObject(i).let { RemoteVolume(it.getString("id"), it.optString("name", it.getString("id")), it.optLong("used"), it.optLong("quota")) }
                }
            }.orEmpty()
            return Me(json.optString("name"), volumes, json.optBoolean("active", true))
        }
    }

    /**
     * Sends a file. Only if nothing is at that path when replace is false: the
     * NAS refuses instead of overwriting, and says what is there.
     */
    fun upload(
        volume: String,
        path: String,
        size: Long,
        modifiedSeconds: Long,
        mime: String?,
        replace: Boolean,
        open: () -> InputStream,
        progress: (Long) -> Unit,
        cancelled: () -> Boolean = { false },
    ): UploadResult {
        val root = base ?: return UploadResult.Failed("Sin conexión con el NAS")
        val url = root.newBuilder()
            .encodedPath("/v0/file")
            .addQueryParameter("volume", volume)
            .addQueryParameter("path", path)
            .addQueryParameter("mtime", modifiedSeconds.toString())
            .apply { if (!replace) addQueryParameter("base", "none") }
            .build()
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = (mime ?: "application/octet-stream").toMediaTypeOrNull()
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        // Paused: the NAS keeps nothing of an upload cut half way.
                        if (cancelled()) throw CancelledUpload()
                        sink.write(buffer, 0, read)
                        sent += read
                        if (sent - lastReport > 512 * 1024) {
                            progress(sent)
                            lastReport = sent
                        }
                    }
                    if (sent != size) throw IOException("El fichero cambió mientras se enviaba")
                    progress(sent)
                }
            }
        }
        val request = Request.Builder().url(url).put(body).header("Authorization", "Bearer $token").build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    201, 200 -> UploadResult.Sent
                    412 -> UploadResult.Exists(JSONObject(response.body.string()).optLong("size", -1))
                    401 -> UploadResult.Unauthorized
                    409 -> UploadResult.Standby
                    507 -> UploadResult.Full
                    else -> UploadResult.Failed("El NAS respondió ${response.code}")
                }
            }
        } catch (e: CancelledUpload) {
            UploadResult.Cancelled
        } catch (e: IOException) {
            if (cancelled()) UploadResult.Cancelled else UploadResult.Failed(e.message ?: "Se cortó la conexión")
        }
    }

    private class CancelledUpload : IOException("Cancelado")
}
