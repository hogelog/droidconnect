package org.hogel.droidconnect.ssh

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.spec.NamedParameterSpec
import java.util.Base64

/**
 * Manages Ed25519 SSH key pair generation and storage.
 * Keys are stored as files in the app's private directory.
 */
class SshKeyManager(context: Context) {

    private val keyDir = File(context.filesDir, "ssh_keys").also { it.mkdirs() }
    private val privateKeyFile = File(keyDir, "id_ed25519")
    private val publicKeyFile = File(keyDir, "id_ed25519.pub")

    fun hasKey(): Boolean = privateKeyFile.exists() && publicKeyFile.exists()

    /**
     * Generate a new Ed25519 key pair and save to app-private storage.
     * Returns the public key in OpenSSH format.
     */
    fun generateKey(): String {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        kpg.initialize(NamedParameterSpec.ED25519)
        val keyPair = kpg.generateKeyPair()

        // Save private key in PKCS#8 PEM format
        val privateKeyBytes = keyPair.private.encoded
        val privatePem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(privateKeyBytes))
            appendLine("-----END PRIVATE KEY-----")
        }
        privateKeyFile.writeText(privatePem)
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setReadable(true, true)

        // Build OpenSSH public key format
        val publicKey = keyPair.public as EdECPublicKey
        val pubKeyOpenSsh = buildOpenSshPublicKey(publicKey)
        publicKeyFile.writeText(pubKeyOpenSsh)

        return pubKeyOpenSsh
    }

    /** Get the public key in OpenSSH format, or null if no key exists. */
    fun getPublicKey(): String? {
        if (!publicKeyFile.exists()) return null
        return publicKeyFile.readText().trim()
    }

    /** Get the private key PEM content for SSH authentication. */
    fun getPrivateKeyPem(): CharArray? {
        if (!privateKeyFile.exists()) return null
        return privateKeyFile.readText().toCharArray()
    }

    private fun buildOpenSshPublicKey(publicKey: EdECPublicKey): String {
        val keyType = "ssh-ed25519"
        val point = publicKey.point
        // Ed25519 public key is 32 bytes
        val rawKey = edPointToBytes(point)

        val blob = buildSshBlob(keyType, rawKey)
        val encoded = Base64.getEncoder().encodeToString(blob)
        return "$keyType $encoded droidconnect"
    }

    private fun edPointToBytes(point: java.security.spec.EdECPoint): ByteArray {
        val y = point.y.toByteArray()
        // EdDSA public key encoding: 32 bytes, little-endian y coordinate with x sign bit
        val result = ByteArray(32)
        // BigInteger.toByteArray() is big-endian, we need little-endian
        for (i in y.indices) {
            if (y.size - 1 - i < 32) {
                result[y.size - 1 - i] = y[i]
            }
        }
        // Set the high bit of the last byte if x is odd
        if (point.isXOdd) {
            result[31] = (result[31].toInt() or 0x80).toByte()
        }
        return result
    }

    private fun buildSshBlob(keyType: String, rawKey: ByteArray): ByteArray {
        val typeBytes = keyType.toByteArray(Charsets.UTF_8)
        val result = ByteArray(4 + typeBytes.size + 4 + rawKey.size)
        var offset = 0

        // key type length + key type
        putInt(result, offset, typeBytes.size)
        offset += 4
        typeBytes.copyInto(result, offset)
        offset += typeBytes.size

        // raw key length + raw key
        putInt(result, offset, rawKey.size)
        offset += 4
        rawKey.copyInto(result, offset)

        return result
    }

    private fun putInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value shr 24).toByte()
        buf[offset + 1] = (value shr 16).toByte()
        buf[offset + 2] = (value shr 8).toByte()
        buf[offset + 3] = value.toByte()
    }
}
