package org.hogel.droidconnect.ssh

import com.trilead.ssh2.ServerHostKeyVerifier
import java.security.MessageDigest
import java.util.Base64

/**
 * Trust-On-First-Use host key verifier.
 *
 * - First connection to `host:port`: ask the user to confirm the
 *   `SHA256:...` fingerprint via [HostKeyPrompt]. On accept, persist the key
 *   bytes in [HostKeyStore]; on cancel, abort the handshake.
 * - Subsequent connections: the received key bytes must match the stored
 *   bytes exactly. Any mismatch (algorithm or key) rejects the connection
 *   without prompting — that is the point of the check, since a silent
 *   prompt-and-replace would defeat MITM detection.
 *
 * On rotation the stored entry has to be cleared out-of-band (clear app
 * data) before reconnecting; we do not currently expose an in-app way to
 * forget a host key.
 */
class TofuHostKeyVerifier(
    private val store: HostKeyStore,
    private val prompt: HostKeyPrompt,
) : ServerHostKeyVerifier {

    override fun verifyServerHostKey(
        hostname: String,
        port: Int,
        serverHostKeyAlgorithm: String,
        serverHostKey: ByteArray,
    ): Boolean {
        val stored = store.get(hostname, port)
        if (stored != null) {
            return stored.algorithm == serverHostKeyAlgorithm &&
                MessageDigest.isEqual(stored.key, serverHostKey)
        }
        val fingerprint = sha256Fingerprint(serverHostKey)
        val accepted = prompt.confirmNewHostKey(
            hostname,
            port,
            serverHostKeyAlgorithm,
            fingerprint,
        )
        if (accepted) {
            store.put(hostname, port, serverHostKeyAlgorithm, serverHostKey)
        }
        return accepted
    }

    /**
     * `SHA256:<base64-no-padding>` over the raw server host key blob — the
     * same format `ssh -o FingerprintHash=sha256` prints, so users can
     * compare against `ssh-keygen -lf` output on the server side.
     */
    private fun sha256Fingerprint(serverHostKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(serverHostKey)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(hash)
        return "SHA256:$encoded"
    }
}
