package com.wolfpc.remote.data.net

import com.wolfpc.remote.data.model.Envelope
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The home-network path (PRD §8, §29).
 *
 * Two very different endpoints live behind one class:
 *
 *  - the **ESP32**, over plain HTTP. It has no certificate and no way to get a
 *    trusted one for `wolfpc.local`, and terminating TLS on a 240 MHz chip for
 *    every poll would cost more than it buys. Confidentiality is not what
 *    protects this link — the request body is a signed envelope that is useless
 *    to replay, and the controller verifies the signature itself. An attacker
 *    on your LAN can read "someone asked to power on the PC" and can do nothing
 *    with it.
 *
 *  - the **Windows agent**, over HTTPS with a self-signed certificate pinned by
 *    SHA-256 at pairing time. Pinning is stronger than the usual CA path here,
 *    because exactly one certificate is ever legitimate.
 */
class LanClient {

    private companion object {
        const val TAG = "WolfLan"

        /**
         * Cleartext goes to private addresses and nothing else.
         *
         * The platform's network security config had to be opened up, because
         * a static allow-list cannot express "whatever address the controller
         * reports today" — and the app must dial addresses rather than names,
         * since a phone with strict Private DNS resolves nothing at all while
         * the internet is down. This is where that permission is narrowed back
         * to something meaningful, and it is checked on every request rather
         * than assumed once.
         */
        fun isPrivateHost(hostOrAuthority: String): Boolean {
            val host = hostOrAuthority.substringBefore(':').trim()
            if (host.endsWith(".local", ignoreCase = true)) return true
            if (host.equals("localhost", ignoreCase = true)) return true

            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false

            return when {
                octets[0] == 10 -> true
                octets[0] == 127 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json".toMediaType()

    /** Short timeouts: the LAN is either there or it is not, and the cloud is the fallback. */
    private val plain = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // ------------------------------------------------------- controller ----

    suspend fun controllerStatus(host: String): String? = withContext(Dispatchers.IO) {
        if (!isPrivateHost(host)) {
            android.util.Log.w(TAG, "refusing cleartext to a non-private host")
            return@withContext null
        }
        runCatching {
            plain.newCall(Request.Builder().url("http://$host/api/status").get().build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    /**
     * Is the agent answering on the LAN?
     *
     * Needed because cloud presence flags are useless in the exact situation
     * the LAN path exists for: with no internet the app cannot read pc_status
     * at all, so it cannot tell "the PC is off" from "I cannot see the PC".
     * Asking the PC directly is the only honest answer available.
     */
    suspend fun agentReachable(host: String, fingerprint: String): Boolean =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || fingerprint.isBlank()) return@withContext false
            runCatching {
                pinnedClient(fingerprint)
                    .newCall(Request.Builder().url("https://$host/api/status").get().build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun controllerReachable(host: String): Boolean =
        controllerStatus(host) != null

    /** @return null on success, or a short reason for the caller to fall back on. */
    suspend fun sendToController(host: String, envelope: Envelope): String? =
        withContext(Dispatchers.IO) {
            val route = when (envelope.command) {
                "POWER_ON" -> "power"
                "HARD_RESET" -> "reset"
                else -> return@withContext "command is not a controller command"
            }
            if (!isPrivateHost(host)) {
                return@withContext "the controller address is not on a private network"
            }
            post(plain, "http://$host/api/$route", envelope)
        }

    // ------------------------------------------------------------ agent ----

    suspend fun sendToAgent(host: String, fingerprint: String, envelope: Envelope): String? =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || fingerprint.isBlank()) return@withContext "agent LAN not configured"
            val route = when (envelope.command) {
                "SHUTDOWN" -> "shutdown"
                "RESTART" -> "restart"
                "LOCK" -> "lock"
                else -> return@withContext "command is not an agent command"
            }
            post(pinnedClient(fingerprint), "https://$host/api/$route", envelope)
        }

    // --------------------------------------------------------- plumbing ----

    private fun post(client: OkHttpClient, url: String, envelope: Envelope): String? = try {
        val body = json.encodeToString(Envelope.serializer(), envelope)
        val request = Request.Builder()
            .url(url)
            .header("content-type", "application/json")
            .post(body.toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use {
            if (it.isSuccessful) null else "local endpoint returned ${it.code}"
        }
    } catch (e: Exception) {
        e.message ?: "local endpoint unreachable"
    }

    /**
     * An OkHttp client that trusts exactly one certificate, identified by the
     * SHA-256 of its DER encoding.
     *
     * OkHttp's CertificatePinner is not usable here: it pins the public key
     * *within* an otherwise valid chain, and a self-signed certificate never
     * validates in the first place. So the trust manager is replaced outright
     * with one that accepts precisely the pinned fingerprint and nothing else —
     * narrower than the default trust store, not wider.
     */
    private fun pinnedClient(expectedFingerprint: String): OkHttpClient {
        val expected = expectedFingerprint.lowercase().replace(":", "").trim()

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw java.security.cert.CertificateException("client auth not used")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("no certificate presented")
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(leaf.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    throw java.security.cert.CertificateException(
                        "certificate fingerprint does not match the one pinned at pairing",
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }

        return OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The certificate carries the PC's machine name, which rarely
            // matches the IP the phone dials. The fingerprint check above is
            // the real authentication, so the hostname check is redundant here.
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
