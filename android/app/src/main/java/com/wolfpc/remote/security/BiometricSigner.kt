package com.wolfpc.remote.security

import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface SignResult {
    data class Ok(val signatureB64Url: String) : SignResult
    data object Cancelled : SignResult
    data class Failed(val message: String) : SignResult
    /** The key is gone, usually because a fingerprint was added or removed. */
    data object KeyInvalidated : SignResult
}

/**
 * Turns "the user touched the sensor" into a signature the server and the
 * ESP32 will accept.
 *
 * The biometric is never sent anywhere and this app never sees it. All that
 * crosses the process boundary is the Keystore's willingness to perform one
 * signing operation. (PRD §10, §12)
 */
object BiometricSigner {

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun canAuthenticate(activity: FragmentActivity): Int =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)

    fun describe(status: Int): String = when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> "ready"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "this phone has no biometric sensor"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "the biometric sensor is unavailable"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "no fingerprint or face is enrolled"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
            "a security update is required before biometrics can be used"
        else -> "biometric authentication is not available"
    }

    /**
     * Prompt, then sign [message].
     *
     * @return base64url of the 64-byte IEEE P1363 signature the protocol wants.
     */
    suspend fun sign(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        message: String,
    ): SignResult = suspendCancellableCoroutine { continuation ->

        val signature = KeyManager.signatureForSigning()
        if (signature == null) {
            continuation.resume(SignResult.KeyInvalidated)
            return@suspendCancellableCoroutine
        }

        val prompt = BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    val cryptoSignature = result.cryptoObject?.signature
                    if (cryptoSignature == null) {
                        continuation.resume(SignResult.Failed("no signature in crypto object"))
                        return
                    }
                    val outcome = runCatching {
                        cryptoSignature.update(message.toByteArray(Charsets.UTF_8))
                        val der = cryptoSignature.sign()
                        SignResult.Ok(
                            Base64.encodeToString(
                                derToP1363(der),
                                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                            ),
                        )
                    }.getOrElse { SignResult.Failed(it.message ?: "signing failed") }
                    continuation.resume(outcome)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (!continuation.isActive) return
                    val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_CANCELED
                    continuation.resume(
                        if (cancelled) SignResult.Cancelled else SignResult.Failed(message.toString()),
                    )
                }

                // onAuthenticationFailed fires for a finger that did not match.
                // The prompt stays up and the user tries again, so there is
                // nothing to resume here.
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    /**
     * Convert a DER `SEQUENCE { INTEGER r, INTEGER s }` into the fixed 64-byte
     * r||s form.
     *
     * Java signs in DER; WebCrypto in the Edge Functions and mbedTLS-via-P1363
     * on the ESP32 both want the fixed form, so the protocol standardises on it
     * and the conversion happens here, once. DER integers are signed and
     * variable length, which is why each half is right-aligned into 32 bytes
     * rather than copied verbatim.
     */
    internal fun derToP1363(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "not a DER sequence" }

        var index = 2
        if (der[1].toInt() and 0xFF > 0x80) index = 2 + (der[1].toInt() and 0x7F)

        require(der[index] == 0x02.toByte()) { "expected INTEGER r" }
        val rLength = der[index + 1].toInt() and 0xFF
        val rStart = index + 2

        val sTagIndex = rStart + rLength
        require(der[sTagIndex] == 0x02.toByte()) { "expected INTEGER s" }
        val sLength = der[sTagIndex + 1].toInt() and 0xFF
        val sStart = sTagIndex + 2

        val out = ByteArray(64)
        copyRightAligned(der, rStart, rLength, out, 0)
        copyRightAligned(der, sStart, sLength, out, 32)
        return out
    }

    private fun copyRightAligned(
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        // A DER INTEGER may carry a leading 0x00 so it reads as positive, and
        // may be shorter than 32 bytes when the value has leading zero bytes.
        var offset = sourceOffset
        var remaining = length
        while (remaining > 32) { offset++; remaining-- }
        System.arraycopy(source, offset, destination, destinationOffset + (32 - remaining), remaining)
    }
}
