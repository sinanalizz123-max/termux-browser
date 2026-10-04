package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class PluginBundleTest {

    private fun manifest(
        id: String = "deepseek",
        version: Int = 2,
        extra: String = ""
    ): String {
        return """{"format":"tb-plugin/1","id":"$id","version":$version,"minCore":1,"keyId":"termux-1","hosts":["chat.deepseek.com"],"files":{"selectors.json":"$SEL","health.json":"$HEA","plan.json":"$PLA"}$extra}"""
    }

    private val selJson =
        """{"prompt":["textarea"],"submit":["div.send"],"messages":["[class*='ds-markdown']"],"stop":[]}"""
    private val heaJson = """{"marker":"[class*='ds-']","strategies":["click","enter"]}"""
    private val plaJson = """{"strategies":["click","enter"]}"""

    private val SEL = sha("selectors")
    private val HEA = sha("health")
    private val PLA = sha("plan")

    private fun sha(name: String): String = PluginBundle.sha256Hex(
        when (name) {
            "selectors" -> selJson.toByteArray()
            "health" -> heaJson.toByteArray()
            else -> plaJson.toByteArray()
        }
    )

    private fun files(manifest: String = manifest()): Map<String, ByteArray> = mapOf(
        "manifest.json" to manifest.toByteArray(),
        "selectors.json" to selJson.toByteArray(),
        "health.json" to heaJson.toByteArray(),
        "plan.json" to plaJson.toByteArray(),
        "signature.sig" to ByteArray(256) { 1 }
    )

    @Test
    fun `valid bundle passes`() {
        assertTrue(PluginBundle.validate(files()) is BundleCheck.Ok)
    }

    @Test
    fun `unexpected file set rejected`() {
        assertTrue(PluginBundle.validate(files() - "plan.json") is BundleCheck.Rejected)
        assertTrue(
            PluginBundle.validate(files() + ("evil.js" to ByteArray(10))) is BundleCheck.Rejected
        )
    }

    @Test
    fun `tampered content rejected`() {
        val tampered = files().toMutableMap()
        tampered["selectors.json"] = """{"prompt":[],"submit":[],"messages":[],"stop":[]}""".toByteArray()
        assertTrue(PluginBundle.validate(tampered) is BundleCheck.Rejected)
    }

    @Test
    fun `unknown manifest field rejected`() {
        val bad = manifest(extra = ""","exec":"evil()"""")
        assertTrue(PluginBundle.validate(files(bad)) is BundleCheck.Rejected)
    }

    @Test
    fun `bad version core key and hosts rejected`() {
        val badVersion = files(manifest(version = 0))
        assertTrue(PluginBundle.validate(badVersion) is BundleCheck.Rejected)
        val badCore = manifest().replace("\"minCore\":1", "\"minCore\":99")
        assertTrue(PluginBundle.validate(files(badCore)) is BundleCheck.Rejected)
        val badHost = manifest().replace("chat.deepseek.com", "not a host!!")
        assertTrue(PluginBundle.validate(files(badHost)) is BundleCheck.Rejected)
    }

    @Test
    fun `empty selectors rejected`() {
        val empty = files().toMutableMap()
        val blank = """{"prompt":[],"submit":[],"messages":[],"stop":[]}""".toByteArray()
        empty["selectors.json"] = blank
        // Hash must match first: recompute a consistent-but-empty bundle.
        val hex = PluginBundle.sha256Hex(blank)
        val m = manifest().replace(SEL, hex)
        empty["manifest.json"] = m.toByteArray()
        assertTrue(PluginBundle.validate(empty) is BundleCheck.Rejected)
    }
}

class PluginCryptoTest {

    private fun keyPair(): java.security.KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    @Test
    fun `sign then verify round trip`() {
        val keys = keyPair()
        val message = """{"format":"tb-plugin/1"}""".toByteArray()
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update(message)
        val signature = signer.sign()
        assertEquals(256, signature.size)
        assertTrue(PluginCrypto.verify(message, signature, keys.public))
    }

    @Test
    fun `tampered bytes fail verification`() {
        val keys = keyPair()
        val message = """{"a":1}""".toByteArray()
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update(message)
        val signature = signer.sign()
        assertFalse(PluginCrypto.verify("""{"a":2}""".toByteArray(), signature, keys.public))
    }

    @Test
    fun `wrong key fails verification`() {
        val a = keyPair()
        val b = keyPair()
        val message = """{"a":1}""".toByteArray()
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(a.private)
        signer.update(message)
        assertFalse(PluginCrypto.verify(message, signer.sign(), b.public))
    }

    @Test
    fun `short signatures rejected without crypto`() {
        val keys = keyPair()
        assertFalse(PluginCrypto.verify(ByteArray(10), ByteArray(10), keys.public))
    }
}
