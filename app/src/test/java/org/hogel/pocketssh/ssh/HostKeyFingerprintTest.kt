package org.hogel.pocketssh.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class HostKeyFingerprintTest {

    @Test
    fun `fingerprint uses SHA256 prefix and unpadded base64`() {
        val fp = sha256HostKeyFingerprint(byteArrayOf(0x00, 0x01, 0x02))
        assertTrue("starts with SHA256:", fp.startsWith("SHA256:"))
        // OpenSSH-style fingerprints have no padding.
        assertFalse("must not include base64 padding", fp.contains('='))
    }

    @Test
    fun `fingerprint is deterministic and matches a hand-computed digest`() {
        val key = "hello".toByteArray(Charsets.UTF_8)
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        assertEquals(expected, sha256HostKeyFingerprint(key))
    }

    @Test
    fun `fingerprint changes when even a single byte changes`() {
        val a = sha256HostKeyFingerprint(byteArrayOf(1, 2, 3))
        val b = sha256HostKeyFingerprint(byteArrayOf(1, 2, 4))
        assertFalse("digests differ for different inputs", a == b)
    }

    @Test
    fun `fingerprint of empty key is still well-formed`() {
        // Not a real host key, but the helper should not blow up on edge inputs.
        val fp = sha256HostKeyFingerprint(ByteArray(0))
        assertTrue(fp.startsWith("SHA256:"))
        assertEquals("SHA256:".length + 43, fp.length) // sha256 = 32B → 43 chars unpadded
    }
}
