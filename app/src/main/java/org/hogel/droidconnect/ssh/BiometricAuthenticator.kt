package org.hogel.droidconnect.ssh

import androidx.biometric.BiometricPrompt

/**
 * Shows a biometric prompt to authorize a keystore-backed [BiometricPrompt.CryptoObject].
 *
 * The implementation lives in the UI layer (it needs a FragmentActivity) while
 * the consumer is the SSH signing path on a background thread. Implementations
 * must post to the main thread to show the prompt and block until the user
 * completes or cancels authentication.
 */
interface BiometricAuthenticator {
    /**
     * Shows the biometric prompt for [cryptoObject] and returns the same object
     * once the user has authenticated, ready to be used for a single sign call.
     *
     * @throws BiometricAuthenticationException if the user cancels, fails, or the
     *         device cannot satisfy the prompt.
     */
    fun authenticate(cryptoObject: BiometricPrompt.CryptoObject): BiometricPrompt.CryptoObject
}

class BiometricAuthenticationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
