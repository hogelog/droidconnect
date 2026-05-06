package org.hogel.pocketssh.ssh

/**
 * UI callback invoked by [TofuHostKeyVerifier] on the first connection to a
 * host. The implementation lives in the UI layer (it shows an [AlertDialog])
 * while the consumer is sshlib's host-key verifier on a background thread.
 * Implementations must post to the main thread to show the dialog and block
 * until the user accepts or rejects.
 */
interface HostKeyPrompt {
    /**
     * Shows a dialog displaying the host key fingerprint and blocks until the
     * user accepts or cancels. Returns `true` to proceed (the key will be
     * persisted) or `false` to abort the SSH handshake.
     */
    fun confirmNewHostKey(
        host: String,
        port: Int,
        algorithm: String,
        fingerprint: String,
    ): Boolean
}
