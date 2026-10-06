package com.wolfpc.remote.data.model

import android.net.Uri

/**
 * What the agent's pairing QR carries. The other half of `PairingQr.cs`.
 *
 * Everything is optional except the scheme check, because an older agent may
 * emit fewer fields than a newer app expects. A payload that supplies only the
 * code is still useful: the user just fills the rest in by hand.
 */
data class PairingPayload(
    val version: Int,
    val supabaseUrl: String?,
    val anonKey: String?,
    val pcId: String?,
    val code: String?,
    val controllerHost: String?,
    val agentHost: String?,
    val fingerprint: String?,
) {
    val hasProject: Boolean get() = !supabaseUrl.isNullOrBlank() && !anonKey.isNullOrBlank()
    val hasLan: Boolean get() = !agentHost.isNullOrBlank() && !fingerprint.isNullOrBlank()

    companion object {
        const val SCHEME = "wolfpc"
        private const val HOST = "pair"

        /** The versions this build understands. */
        private val SUPPORTED = setOf(1)

        /**
         * Parse a scanned string.
         *
         * Returns null for anything that is not one of our pairing URIs, which
         * is the common case: a camera pointed at the world finds plenty of
         * QR codes, and the scanner keeps looking rather than reporting an
         * error for each one.
         */
        fun parse(raw: String): PairingPayload? {
            val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            if (!HOST.equals(uri.host, ignoreCase = true)) return null

            val version = uri.getQueryParameter("v")?.toIntOrNull() ?: 1
            if (version !in SUPPORTED) return null

            fun param(name: String): String? =
                uri.getQueryParameter(name)?.trim()?.takeIf { it.isNotEmpty() }

            val url = param("url")
            // A pairing payload that would point the app at a plaintext
            // endpoint is refused outright rather than half-applied: the
            // project URL decides where every token this app holds gets sent.
            if (url != null && !url.startsWith("https://")) return null

            val fingerprint = param("fp")?.lowercase()
            if (fingerprint != null && !Regex("^[0-9a-f]{64}$").matches(fingerprint)) return null

            val code = param("code")?.uppercase()
            if (code != null && !Regex("^WOLF-\\d{6}$").matches(code)) return null

            return PairingPayload(
                version = version,
                supabaseUrl = url?.trimEnd('/'),
                anonKey = param("key"),
                pcId = param("pc"),
                code = code,
                controllerHost = param("ctrl"),
                agentHost = param("host"),
                fingerprint = fingerprint,
            )
        }
    }
}
