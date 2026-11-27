package com.medusko.hotboi

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.Security

class SecurityAndOptimizationTest {

    @Test
    fun collectorHttpClient_allowsPrivateLanHttp_andBlocksPublicCleartextHttp() {
        // Private RFC1918, Loopback, Tailscale CGNAT & mDNS allowed over HTTP
        assertEquals("http://127.0.0.1:8080", CollectorHttpClient.validateCollectorBaseUrl("http://127.0.0.1:8080/"))
        assertEquals("http://localhost:8080", CollectorHttpClient.validateCollectorBaseUrl("http://localhost:8080"))
        assertEquals("http://192.168.0.134:8080", CollectorHttpClient.validateCollectorBaseUrl("http://192.168.0.134:8080"))
        assertEquals("http://10.0.0.25:8080", CollectorHttpClient.validateCollectorBaseUrl("http://10.0.0.25:8080"))
        assertEquals("http://172.16.5.10:8080", CollectorHttpClient.validateCollectorBaseUrl("http://172.16.5.10:8080"))
        assertEquals("http://100.101.102.103:8080", CollectorHttpClient.validateCollectorBaseUrl("http://100.101.102.103:8080"))
        assertEquals("http://hotboi-mac.local:8080", CollectorHttpClient.validateCollectorBaseUrl("http://hotboi-mac.local:8080"))

        // Public WAN HTTPS is allowed
        assertEquals("https://telemetry.medusko.com", CollectorHttpClient.validateCollectorBaseUrl("https://telemetry.medusko.com/"))

        // Public WAN cleartext HTTP must fail closed with SecurityException
        val blockedUrls = listOf(
            "http://8.8.8.8:8080",
            "http://172.32.0.1:8080",
            "http://telemetry.medusko.com:8080",
            "ftp://192.168.0.134:8080"
        )
        for (badUrl in blockedUrls) {
            try {
                CollectorHttpClient.validateCollectorBaseUrl(badUrl)
                fail("Expected SecurityException for $badUrl")
            } catch (_: SecurityException) {
                // Expected
            }
        }
    }

    @Test
    fun posixRemoteScript_hasZeroPythonDependency_sanitizesInputs_andGatesPonSubnet() {
        val defaultScript = SshCollector.buildRemoteScript(
            pingTarget = "1.1.1.1; rm -rf /",
            sfpInterface = "eth6\$(reboot)",
            allowPonStickAutoIp = false
        )
        assertTrue(defaultScript.startsWith("sh -c '"))
        assertFalse(defaultScript.contains("python3"))
        // Hostile inputs must be replaced with safe defaults
        assertFalse(defaultScript.contains("rm -rf"))
        assertFalse(defaultScript.contains("reboot"))
        assertTrue(defaultScript.contains("IFACE=\"eth6\""))
        assertTrue(defaultScript.contains("TARGET=\"1.1.1.1\""))
        assertTrue(defaultScript.contains("AUTO_PON=\"0\""))
        assertTrue(defaultScript.contains("StrictHostKeyChecking=accept-new"))

        val optInPonScript = SshCollector.buildRemoteScript(
            pingTarget = "9.9.9.9",
            sfpInterface = "sfp1",
            allowPonStickAutoIp = true
        )
        assertTrue(optInPonScript.contains("IFACE=\"sfp1\""))
        assertTrue(optInPonScript.contains("TARGET=\"9.9.9.9\""))
        assertTrue(optInPonScript.contains("AUTO_PON=\"1\""))

        val discovery = SshCollector.buildDiscoveryScript()
        assertTrue(discovery.startsWith("sh -c '"))
        assertFalse(discovery.contains("python3"))
    }

    @Test
    fun bouncyCastleAndJsch_ed25519SignatureAndCurve25519XdhWorkCleanly() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519, 256)
        val payload = "hotboi-ssh-verify".toByteArray(Charsets.UTF_8)
        val sig = kp.getSignature(payload)
        assertTrue(sig != null && sig.isNotEmpty())
        val pubBlob = kp.publicKeyBlob
        assertTrue(pubBlob != null && pubBlob.isNotEmpty())
        kp.dispose()

        // Verify Curve25519 / X25519 key agreement used by modern OpenSSH KEX
        val alice = com.jcraft.jsch.bc.XDH()
        alice.init("X25519", 32)
        val bob = com.jcraft.jsch.bc.XDH()
        bob.init("X25519", 32)
        val sharedA = alice.getSecret(bob.q)
        val sharedB = bob.getSecret(alice.q)
        assertTrue(sharedA.contentEquals(sharedB))
    }
}
