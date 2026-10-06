package com.wolfpc.remote.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's signing identity (PRD §12).
 *
 * The private key is generated inside the Android Keystore and is never
 * exportable — not by this app, not by a rooted shell, not by a backup. What
 * leaves the device is the public half, and nothing else. A command is
 * therefore proof that this specific phone, unlocked by this specific person's
 * biometric, asked for it.
 */
object KeyManager {

    private const val TAG = "WolfKeyManager"
    private const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "wolfpc_command_signing_key"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    fun hasKey(): Boolean = runCatching { keyStore.containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Create the signing key if it does not exist.
     *
     * @return true if a usable key is present afterwards.
     */
    fun ensureKey(context: Context): Boolean {
        if (hasKey()) return true
        return try {
            generate(strongBox = hasStrongBox(context))
            true
        } catch (e: StrongBoxUnavailableException) {
            // Some phones advertise StrongBox and then refuse EC keys in it.
            Log.w(TAG, "StrongBox refused the key, falling back to TEE", e)
            runCatching { generate(strongBox = false) }.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "key generation failed", e)
            false
        }
    }

    private fun hasStrongBox(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")

    private fun generate(strongBox: Boolean) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Re-enrolling a fingerprint destroys this key on purpose. If
            // someone with the unlock PIN adds their own finger, they must not
            // inherit the ability to power-cycle the PC; the user re-pairs
            // instead, which is a small cost for a real protection.
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // timeout 0 = authorise every single signature, not a window
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)   // -1 = per use
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()

        generator.initialize(spec)
        generator.generateKeyPair()
        Log.i(TAG, "generated signing key (strongBox=$strongBox)")
    }

    /** Base64url SPKI, exactly what `device_keys.public_key` stores. */
    fun publicKeyB64Url(): String? {
        val certificate = runCatching { keyStore.getCertificate(ALIAS) }.getOrNull() ?: return null
        return Base64.encodeToString(
            certificate.publicKey.encoded,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    /**
     * A Signature initialised for signing, ready to be wrapped in a
     * BiometricPrompt.CryptoObject.
     *
     * initSign() succeeds without a biometric; the actual sign() is what the
     * Keystore refuses until the prompt has authorised this exact operation.
     */
    fun signatureForSigning(): Signature? = runCatching {
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }.getOrElse {
        Log.e(TAG, "could not initialise signature; key may have been invalidated", it)
        null
    }

    /** Used when unpairing, and when a re-enrolled biometric has invalidated the key. */
    fun deleteKey() {
        runCatching { keyStore.deleteEntry(ALIAS) }
    }
}
