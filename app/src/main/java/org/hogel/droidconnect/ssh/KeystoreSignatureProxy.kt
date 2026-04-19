package org.hogel.droidconnect.ssh

import androidx.biometric.BiometricPrompt
import com.trilead.ssh2.auth.SignatureProxy
import com.trilead.ssh2.crypto.SimpleDERReader
import com.trilead.ssh2.packets.TypesWriter
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * SSH publickey-authentication signer that routes signing through an
 * Android Keystore entry guarded by [BiometricAuthenticator].
 *
 * sshlib's [SignatureProxy] is the supported extension point for custom key
 * stores: passing this proxy to `Connection.authenticateWithPublicKey` makes
 * sshlib call back into [sign] instead of touching the private key directly.
 * That lets the real key stay in the TEE — we only hand sshlib the public key
 * and the signature bytes it needs to assemble the auth packet.
 */
class KeystoreSignatureProxy(
    publicKey: ECPublicKey,
    private val keyManager: SshKeyManager,
    private val authenticator: BiometricAuthenticator,
) : SignatureProxy(publicKey) {

    override fun sign(message: ByteArray, hashAlgorithm: String): ByteArray {
        val privateKey = keyManager.loadPrivateKey()
            ?: throw BiometricAuthenticationException("Keystore private key is missing")

        // SHA256withECDSA matches the DIGEST_SHA256 authorization on the
        // keystore entry; sshlib requests SHA-256 for P-256 keys via
        // ECDSASHA2Verify.getDigestAlgorithmForParams.
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }

        val authed = authenticator.authenticate(BiometricPrompt.CryptoObject(signature))
        val authedSig = authed.signature
            ?: throw BiometricAuthenticationException("Biometric prompt returned no signature")

        authedSig.update(message)
        val derSignature = authedSig.sign()

        return encodeSshEcdsaSignature(derSignature)
    }

    /**
     * Converts a DER-encoded ECDSA signature (`SEQUENCE { INTEGER r, INTEGER s }`)
     * into the SSH wire format (RFC 5656 §3.1.2):
     * `string "ecdsa-sha2-nistp256"` followed by `string mpint(r) || mpint(s)`.
     *
     * This mirrors `ECDSASHA2Verify.encodeSSHECDSASignature`, which is private
     * in sshlib, so we reimplement it here using the library's public
     * `SimpleDERReader` / `TypesWriter` helpers.
     */
    private fun encodeSshEcdsaSignature(derSignature: ByteArray): ByteArray {
        val reader = SimpleDERReader(derSignature)
        reader.resetInput(reader.readSequenceAsByteArray())
        val r = reader.readInt()
        val s = reader.readInt()

        val rs = TypesWriter().apply {
            writeMPInt(r)
            writeMPInt(s)
        }.getBytes()

        return TypesWriter().apply {
            writeString(KEY_FORMAT)
            writeString(rs, 0, rs.size)
        }.getBytes()
    }

    companion object {
        private const val KEY_FORMAT = "ecdsa-sha2-nistp256"
    }
}
