package com.termux.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TokenStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fakeCrypto = object : TokenCrypto {
        override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> =
            ByteArray(12) { 7 } to plain.copyOf()

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            ciphertext.copyOf()
    }

    @Test
    fun `token is stable across instances`() {
        val dir = folder.newFolder()
        val first = TokenStore(dir, fakeCrypto).getOrCreate()
        val second = TokenStore(dir, fakeCrypto).getOrCreate()
        assertEquals(TokenStore.TOKEN_BYTES, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun `tokens differ per directory`() {
        val first = TokenStore(folder.newFolder(), fakeCrypto).getOrCreate()
        val second = TokenStore(folder.newFolder(), fakeCrypto).getOrCreate()
        assertNotEquals(
            first.joinToString(","),
            second.joinToString(",")
        )
    }

    @Test
    fun `corrupt file regenerates a valid token`() {
        val dir = folder.newFolder()
        java.io.File(dir, "api_token.bin").writeBytes(byteArrayOf(1, 2, 3))
        val token = TokenStore(dir, fakeCrypto).getOrCreate()
        assertEquals(TokenStore.TOKEN_BYTES, token.size)
    }

    @Test
    fun `hex is lowercase without separators`() {
        val store = TokenStore(folder.newFolder(), fakeCrypto)
        val hex = store.hex(byteArrayOf(0x0A, 0xFF.toByte(), 0x00))
        assertEquals("0aff00", hex)
    }
}
