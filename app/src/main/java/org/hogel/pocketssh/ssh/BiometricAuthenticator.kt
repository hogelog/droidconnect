package org.hogel.pocketssh.ssh

/**
 * Shows a biometric prompt that unlocks the keystore for the configured
 * validity window (see [SshKeyManager.VALIDITY_SECONDS]).
 *
 * The implementation lives in the UI layer (it needs a FragmentActivity) while
 * the consumer is the SSH signing path on a background thread. Implementations
 * must post to the main thread to show the prompt and block until the user
 * completes or cancels authentication.
 */
interface BiometricAuthenticator {
    /**
     * Shows the biometric prompt and blocks until the user authenticates. After
     * a successful return the keystore key is usable without re-prompting until
     * the validity window expires.
     *
     * @throws BiometricAuthenticationException if the user cancels, fails, or the
     *         device cannot satisfy the prompt.
     */
    fun authenticate()
}

class BiometricAuthenticationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
