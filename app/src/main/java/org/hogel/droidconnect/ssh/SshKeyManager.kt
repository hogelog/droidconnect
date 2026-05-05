package org.hogel.droidconnect.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.trilead.ssh2.signature.ECDSASHA2Verify
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * ECDSA P-256 SSH key pair stored in the Android Keystore.
 *
 * The private key never leaves the TEE. A successful biometric authentication
 * keeps the key usable for [VALIDITY_SECONDS]; any system-wide biometric event
 * (including the lock-screen unlock) refreshes that window, so signing stays
 * seamless during active use. The public key is derived from the keystore
 * entry on demand and formatted as an OpenSSH authorized_keys line.
 */
class SshKeyManager {

    fun hasKey(): Boolean = loadKeyStore().containsAlias(KEY_ALIAS)

    /** Generates a new keystore-backed key, replacing any existing one. Returns the OpenSSH public key. */
    fun generateKey(): String {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                // One biometric authentication authorizes signing for
                // VALIDITY_SECONDS; after that, sign() throws
                // UserNotAuthenticatedException and KeystoreSignatureProxy
                // re-prompts. Keeps active use seamless without prompting on
                // every SSH handshake or tmux reconnect.
                .setUserAuthenticationParameters(
                    VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        kpg.generateKeyPair()
        return getPublicKey() ?: error("Key generation succeeded but public key is missing")
    }

    /** Returns the authorized_keys-formatted public key, or null if no key exists. */
    fun getPublicKey(): String? {
        val publicKey = loadPublicKey() ?: return null
        val sshBlob = ECDSASHA2Verify.ECDSASHA2NISTP256Verify.get().encodePublicKey(publicKey)
        val encoded = Base64.getEncoder().encodeToString(sshBlob)
        return "ecdsa-sha2-nistp256 $encoded $KEY_COMMENT"
    }

    /** Returns the keystore-backed public key, or null if no key exists. */
    fun loadPublicKey(): ECPublicKey? {
        val cert = loadKeyStore().getCertificate(KEY_ALIAS) ?: return null
        return cert.publicKey as ECPublicKey
    }

    /** Returns the keystore-backed private key (only a handle; the raw key stays in the TEE). */
    fun loadPrivateKey(): PrivateKey? =
        loadKeyStore().getKey(KEY_ALIAS, null) as? PrivateKey

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val KEY_ALIAS = "droidconnect-ssh-key"

        /**
         * How long a single biometric authentication keeps the keystore key
         * usable, in seconds. The Android Keystore counts any system-wide
         * biometric event (including the lock-screen unlock) as a fresh
         * authentication, so 30 minutes is enough to keep the experience
         * seamless during active use without extending the unlocked window
         * unnecessarily after the device has been idle.
         */
        const val VALIDITY_SECONDS = 30 * 60

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_COMMENT = "droidconnect"
    }
}
