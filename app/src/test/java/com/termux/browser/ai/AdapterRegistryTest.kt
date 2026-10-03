package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterRegistryTest {

    private class FakeAdapter(
        override val id: String,
        override val hosts: Set<String>,
        private val matches: (String) -> Boolean = { true }
    ) : SiteAdapter {
        override fun detect(url: String): Boolean = matches(url)
        override fun selectors(): SelectorSet = SelectorSet()
        override fun snapshotScript(): String = ""
    }

    private val generic = FakeAdapter("generic", emptySet())

    @Test
    fun `exact host owner wins over generic`() {
        val chat = FakeAdapter("chat", setOf("chat.example.com"))
        val registry = AdapterRegistry(listOf(generic, chat))
        assertEquals("chat", registry.detect("https://chat.example.com/")?.id)
    }

    @Test
    fun `generic is the fallback`() {
        val registry = AdapterRegistry(listOf(generic))
        assertEquals("generic", registry.detect("https://anything.example/")?.id)
    }

    @Test
    fun `registration order decides overlapping owners`() {
        val first = FakeAdapter("first", setOf("x.example.com"))
        val second = FakeAdapter("second", setOf("x.example.com"))
        assertEquals(
            "first",
            AdapterRegistry(listOf(first, second)).detect("https://x.example.com/")?.id
        )
        assertEquals(
            "second",
            AdapterRegistry(listOf(second, first)).detect("https://x.example.com/")?.id
        )
    }

    @Test
    fun `no match returns null without a generic adapter`() {
        val specific = FakeAdapter("s", setOf("s.example.com"), matches = { false })
        assertNull(AdapterRegistry(listOf(specific)).detect("https://other.example/"))
    }

    @Test
    fun `detection is pure and deterministic`() {
        val chat = FakeAdapter("chat", setOf("chat.example.com"))
        val registry = AdapterRegistry(listOf(generic, chat))
        val url = "https://chat.example.com/c/123?q=1"
        val first = registry.detect(url)
        repeat(100) { assertSame(first, registry.detect(url)) }
    }

    @Test
    fun `host parsing ignores scheme path and query`() {
        val registry = AdapterRegistry(listOf(generic))
        assertEquals("Chat.Example.COM".lowercase(), registry.hostOf("https://Chat.Example.COM/a?b=c"))
    }
}
