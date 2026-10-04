package com.termux.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearnedStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `round trip per host`() {
        val store = LearnedStore(folder.newFolder())
        assertNull(store.get("chat.deepseek.com"))
        store.put("chat.deepseek.com", "div.send")
        assertEquals("div.send", store.get("chat.deepseek.com")?.sendSelector)
        assertNull(store.get("chatgpt.com"))
    }

    @Test
    fun `blank and oversize selectors rejected`() {
        val store = LearnedStore(folder.newFolder())
        var threw = 0
        try {
            store.put("x.com", "  ")
        } catch (_: IllegalArgumentException) {
            threw++
        }
        try {
            store.put("x.com", "a".repeat(600))
        } catch (_: IllegalArgumentException) {
            threw++
        }
        assertEquals(2, threw)
        assertNull(store.get("x.com"))
    }

    @Test
    fun `clear removes the profile`() {
        val store = LearnedStore(folder.newFolder())
        store.put("x.com", "div.send")
        store.clear("x.com")
        assertNull(store.get("x.com"))
    }

    @Test
    fun `named controls coexist per host`() {
        val store = LearnedStore(folder.newFolder())
        store.putControl("x.com", "send", "div.send")
        store.putControl("x.com", "menu", "button.burger")
        assertEquals("div.send", store.getControl("x.com", "send"))
        assertEquals("button.burger", store.getControl("x.com", "menu"))
        assertNull(store.getControl("x.com", "nope"))
        assertNull(store.getControl("y.com", "send"))
    }

    @Test
    fun `legacy send file migrates to named control`() {
        // Old files carry only sendSelector; they must keep working.
        val store = LearnedStore(folder.newFolder())
        store.put("x.com", "div.send")
        assertEquals("div.send", store.getControl("x.com", "send"))
    }

    @Test
    fun `bad control names rejected`() {
        val store = LearnedStore(folder.newFolder())
        for (bad in listOf("", "Send", "send now", "x".repeat(17))) {
            var threw = false
            try {
                store.putControl("x.com", bad, "div.a")
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("must reject $bad", threw)
        }
    }
}

class LearnedSelectorsTest {

    @Test
    fun `id wins`() {
        assertEquals(
            "#send-btn",
            LearnedSelectors.derive(ElementInfo(tag = "DIV", id = "send-btn"))
        )
    }

    @Test
    fun `testid beats classes`() {
        assertEquals(
            "[data-testid='send-button']",
            LearnedSelectors.derive(
                ElementInfo(tag = "DIV", testId = "send-button", classes = listOf("a", "b"))
            )
        )
    }

    @Test
    fun `aria label is quoted safely`() {
        assertEquals(
            "[aria-label='Send it\\'s']",
            LearnedSelectors.derive(ElementInfo(tag = "DIV", ariaLabel = "Send it's"))
        )
    }

    @Test
    fun `role plus class`() {
        assertEquals(
            "div[role='button'].abc",
            LearnedSelectors.derive(
                ElementInfo(tag = "div", role = "button", classes = listOf("abc", "1bad"))
            )
        )
    }

    @Test
    fun `tag plus class`() {
        assertEquals(
            "div.abc",
            LearnedSelectors.derive(ElementInfo(tag = "DIV", classes = listOf("abc")))
        )
    }

    @Test
    fun `nothing trustworthy yields null`() {
        assertNull(LearnedSelectors.derive(ElementInfo(tag = "???")))
        assertNull(LearnedSelectors.derive(ElementInfo()))
        assertNull(
            LearnedSelectors.derive(ElementInfo(tag = "div", classes = listOf("9lives")))
        )
    }
}

class LearnedAdapterTest {

    @Test
    fun `learned submit leads while health stays base-owned`() {
        val base = ChatGPTAdapter()
        val learned = LearnedAdapter(base, "div.send-now")
        assertEquals("div.send-now", learned.selectors().submit.first())
        assertTrue(learned.snapshotScript().contains("div.send-now"))
        assertEquals(base.id, learned.id)
        assertEquals(base.hosts, learned.hosts)
        val health = learned.health(
            Snapshot(promptFound = true, submitFound = true),
            "https://chatgpt.com/"
        )
        assertTrue(health.promptInput)
        assertTrue(health.submitControl)
    }
}
