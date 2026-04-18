package org.hogel.droidconnect.ssh

import android.content.Context
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey
import com.trilead.ssh2.crypto.keys.Ed25519Provider
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
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
        val kpg = KeyPairGenerator.getInstance(Ed25519Provider.KEY_ALGORITHM, Ed25519Provider())
        val keyPair = kpg.generateKeyPair()
        val seed = (keyPair.private as Ed25519PrivateKey).seed
        val publicBytes = (keyPair.public as Ed25519PublicKey).abyte

        privateKeyFile.writeText(buildOpenSshPrivateKey(seed, publicBytes))
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setReadable(true, true)

        val pubKeyOpenSsh = buildOpenSshPublicKey(publicBytes)
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

    private fun buildOpenSshPublicKey(publicBytes: ByteArray): String {
        val blob = buildSshBlob(ED25519_KEYTYPE, publicBytes)
        val encoded = Base64.getEncoder().encodeToString(blob)
        return "$ED25519_KEYTYPE $encoded $KEY_COMMENT"
    }

    private fun buildOpenSshPrivateKey(seed: ByteArray, publicBytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        out.write(OPENSSH_MAGIC)
        writeSshString(out, "none".toByteArray(Charsets.UTF_8))   // ciphername
        writeSshString(out, "none".toByteArray(Charsets.UTF_8))   // kdfname
        writeSshString(out, ByteArray(0))                         // kdfoptions
        writeUint32(out, 1)                                       // numkeys
        writeSshString(out, buildSshBlob(ED25519_KEYTYPE, publicBytes))

        val priv = ByteArrayOutputStream()
        val checkInt = SecureRandom().nextInt()
        writeUint32(priv, checkInt)
        writeUint32(priv, checkInt)
        writeSshString(priv, ED25519_KEYTYPE.toByteArray(Charsets.UTF_8))
        writeSshString(priv, publicBytes)
        writeSshString(priv, seed + publicBytes)
        writeSshString(priv, KEY_COMMENT.toByteArray(Charsets.UTF_8))
        var pad = 1
        while (priv.size() % 8 != 0) {
            priv.write(pad)
            pad++
        }
        writeSshString(out, priv.toByteArray())

        val encoded = Base64.getEncoder().encodeToString(out.toByteArray())
        return buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            encoded.chunked(70).forEach { appendLine(it) }
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
    }

    private fun buildSshBlob(keyType: String, rawKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeSshString(out, keyType.toByteArray(Charsets.UTF_8))
        writeSshString(out, rawKey)
        return out.toByteArray()
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeUint32(out, bytes.size)
        out.write(bytes)
    }

    private fun writeUint32(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 24 and 0xff)
        out.write(value ushr 16 and 0xff)
        out.write(value ushr 8 and 0xff)
        out.write(value and 0xff)
    }

    companion object {
        private const val ED25519_KEYTYPE = "ssh-ed25519"
        private const val KEY_COMMENT = "droidconnect"
        private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1)
    }
}
