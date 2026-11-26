package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import secretspace.core.Disk.int
import secretspace.core.Disk.obj
import secretspace.core.Disk.text
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import secretspace.core.AccountCrypto as ac

/** Anything that stopped a sync, phrased for the user. */
open class SyncError(message: String) : Exception(message)
class Conflict409(message: String) : SyncError(message)
class ServerUnreachable(message: String) : SyncError(message)
class LoginFailed(message: String) : SyncError(message)
class KeyNotTrusted(message: String) : SyncError(message)
class KeyChanged(message: String) : SyncError(message)
class NeedsNewerApp(message: String) : SyncError(message)

/**
 * The server's API v2 (the PC's account_sync.AccountServer on
 * sync_client's transport). ``token`` is the session once [login] ran.
 */
class AccountServer(url: String, private val caFile: String = "", private val timeoutMs: Int = 20_000) {
    companion object {
        const val API_VERSION = 2
        const val FEATURE_VAULTS = "vaults"
        const val FEATURE_SHARING = "sharing"
        const val FEATURE_RECOVERY = "recovery"
        const val FEATURE_SIGNED_KEYS = "signed-keys"
        const val FEATURE_FORMAT_2 = "format-2"
        const val MIN_SERVER = "2.1.0"
        const val CLIENT_HEADER = "X-SecretSpace-Client"

        fun quote(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }

    val url: String = url.trimEnd('/')
    var token: String = ""
    var version: String = ""
        private set
    private var features: List<String>? = null

    init {
        if (this.url.isEmpty()) throw SyncError("No server address configured.")
    }

    private val tls: SSLContext? by lazy {
        if (!this.url.startsWith("https://") || caFile.isEmpty()) return@lazy null
        // A private server has a self-signed certificate: trust exactly that one.
        val certs = try {
            File(caFile).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        } catch (e: Exception) {
            throw SyncError("The server's certificate file cannot be read ($caFile).")
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, cert -> setCertificateEntry("server$i", cert) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    /** One call. Answers are JSON objects. */
    internal fun request(method: String, path: String, body: JsonElement? = null,
                         headers: Map<String, String> = emptyMap()): JsonObject {
        val connection = try {
            URI(url + path).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw SyncError("That is not a server address: $url")
        }
        try {
            if (connection is HttpsURLConnection) tls?.let { connection.sslSocketFactory = it.socketFactory }
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty(CLIENT_HEADER, "${AppVersion.PLATFORM}/${AppVersion.VERSION}")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                val raw = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(raw.size)
                connection.outputStream.use { it.write(raw) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status >= 400) throw httpError(status, text)
            return try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw SyncError("Unexpected reply from the server.")
        } catch (e: SSLException) {
            throw SyncError("The server's certificate was rejected (${e.message}). If it is self-signed, " +
                "give this device the server's certificate file.")
        } catch (e: IOException) {
            if (e is ConnectException || e is UnknownHostException || e is NoRouteToHostException ||
                e is SocketTimeoutException
            ) throw ServerUnreachable(
                "Could not reach $url (${e.message}). Check the address, and that this phone is on " +
                    "the server's network or VPN.",
            )
            throw SyncError("Unexpected reply from the server: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun httpError(status: Int, text: String): SyncError {
        val message = try {
            (Json.parseToJsonElement(text) as? JsonObject)?.text("error") ?: ""
        } catch (e: Exception) {
            ""
        }
        return when (status) {
            401 -> LoginFailed(message.ifEmpty { "The server did not accept the login." })
            409, 428 -> Conflict409(message.ifEmpty { "The vault changed on the server." })
            else -> SyncError(message.ifEmpty { "Server error $status." })
        }
    }

    // -- calls -----------------------------------------------------------------
    fun health(): JsonObject = request("GET", "/health")

    fun checkApi() {
        val health = health()
        features = (health["features"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        version = (health.text("version") ?: "").take(32)
        val needed = health.obj("min_client")?.text(AppVersion.PLATFORM)
        if (Disk.older(AppVersion.VERSION, needed)) throw SyncError(
            "This server needs SecretSpace $needed or newer on this device. Update SecretSpace, " +
                "then sync again. Nothing was changed.",
        )
        val apis = (health["api"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            ?: listOf(1)
        if (API_VERSION !in apis || FEATURE_FORMAT_2 !in features!!) throw SyncError(
            "This server is too old for this SecretSpace: it needs SecretSpace Server $MIN_SERVER or newer. " +
                "Update the server, then sync again. Nothing was changed.",
        )
    }

    private fun has(feature: String): Boolean {
        if (features == null) {
            features = (health()["features"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        }
        return feature in features!!
    }

    fun hasVaults() = has(FEATURE_VAULTS)
    fun hasSharing() = has(FEATURE_SHARING)
    fun hasRecovery() = has(FEATURE_RECOVERY)
    fun hasSignedKeys() = has(FEATURE_SIGNED_KEYS)

    private fun obj(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    fun prelogin(username: String) = request("GET", "/v2/prelogin/" + quote(username))

    fun login(username: String, loginKey: ByteArray) {
        val reply = request("POST", "/v2/login", obj("username" to username, "login_key" to ac.b64(loginKey)))
        token = reply.text("session") ?: throw SyncError("Unexpected reply from the server.")
    }

    fun logout() {
        if (token.isEmpty()) return
        try {
            request("POST", "/v2/logout", JsonObject(emptyMap()))
        } catch (e: SyncError) {
            // the session ends on the server by itself
        }
        token = ""
    }

    fun me() = request("GET", "/v2/me")

    fun putKeyset(loginKey: ByteArray, sealedKeyset: String) {
        request("PUT", "/v2/me/keyset", obj("login_key" to ac.b64(loginKey), "keyset" to sealedKeyset))
    }

    fun putSigningKey(signingKey: String) {
        request("PUT", "/v2/me/signing-key", obj("signing_key" to signingKey))
    }

    fun putRecoveryProof(proof: ByteArray) {
        request("PUT", "/v2/me/recovery-proof", obj("recovery_proof" to ac.b64(proof)))
    }

    fun publicKeys(username: String) = request("GET", "/v2/accounts/" + quote(username) + "/public-key")

    fun members(vaultId: String) = request("GET", "/v2/vaults/$vaultId/members")

    fun putKeySig(vaultId: String, keySig: String) {
        request("PUT", "/v2/vaults/$vaultId/key-sig", obj("key_sig" to keySig))
    }

    fun createVault(vaultId: String, sealedName: String, wrappedKey: String, keySig: String = "") =
        request("POST", "/v2/vaults", obj("id" to vaultId, "name" to sealedName, "wrapped_key" to wrappedKey,
            "key_sig" to keySig))

    fun renameVault(vaultId: String, sealedName: String) =
        request("PUT", "/v2/vaults/$vaultId/name", obj("name" to sealedName))

    class Stored(val blob: String?, val revision: Int)

    fun getVault(vaultId: String): Stored {
        val reply = request("GET", "/v2/vaults/$vaultId")
        return Stored(reply.text("blob")?.takeIf { it.isNotEmpty() }, reply.int("revision") ?: 0)
    }

    /** A new revision unless the server moved past ``revision`` (Conflict409). */
    fun putVault(vaultId: String, blob: String, revision: Int): Int {
        val reply = request("PUT", "/v2/vaults/$vaultId", obj("blob" to blob), mapOf("If-Match" to "$revision"))
        return reply.int("revision") ?: (revision + 1)
    }
}

/** What a sync base records: the server and revision it was agreed at. */
fun baseOrigin(url: String, revision: Int): Obj = linkedMapOf("url" to url.trimEnd('/'), "revision" to revision.toLong())
