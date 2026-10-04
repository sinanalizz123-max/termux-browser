package com.termux.browser.ai

/**
 * M9 second provider. Same safety machinery as ChatGPT (M8): JSON-quoted
 * prompt bridge with finally-cleanup, fail-closed health gate, generation
 * invalidation. DeepSeek-specific identity comes from the marker check:
 * a generic textarea match on the right host is NOT sufficient to submit.
 */
class DeepSeekAdapter : SiteAdapter {
    override val id = "deepseek"
    override val hosts = setOf("chat.deepseek.com")

    companion object {
        const val VERSION = "m9-1"

        const val MARKER_SELECTOR = "[class*='ds-']"

        val PROMPT_CANDIDATES = listOf(
            "textarea[placeholder*='Message']",
            "textarea",
            "div[contenteditable='true']",
            "[contenteditable='true']"
        )
        // DeepSeek renders zero <button> elements: the send control is the
        // empty div adjacent to the composer textarea (CSS-icon button).
        // Structural candidate first; semantic button fallbacks after.
        val SUBMIT_CANDIDATES = listOf(
            "div:has(> textarea) + div",
            "button[type='submit']",
            "button[aria-label*='Send']",
            "[data-testid='send-button']"
        )
        val MESSAGE_CANDIDATES = listOf(
            "[class*='ds-markdown']",
            "[class*='ds-message']",
            ".markdown",
            "article"
        )
        val STOP_CANDIDATES = listOf(
            "button[aria-label*='Stop']",
            "[class*='ds-stop']"
        )
    }

    override fun detect(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return host in hosts || hosts.any { host.endsWith(".$it") }
    }

    override fun selectors(): SelectorSet = SelectorSet(
        prompt = PROMPT_CANDIDATES,
        submit = SUBMIT_CANDIDATES,
        messages = MESSAGE_CANDIDATES,
        stop = STOP_CANDIDATES
    )

    override fun snapshotScript(): String {
        val json = kotlinx.serialization.json.Json
        val prompt = json.encodeToString(PROMPT_CANDIDATES)
        val messages = json.encodeToString(MESSAGE_CANDIDATES)
        val stop = json.encodeToString(STOP_CANDIDATES)
        val submit = json.encodeToString(SUBMIT_CANDIDATES)
        return "(function(){function any(L){for(var k=0;k<L.length;k++){try{if(document.querySelector(L[k]))return true;}catch(e){}}return false;}" +
            "function first(L){for(var k=0;k<L.length;k++){try{var e=document.querySelector(L[k]);if(e)return e;}catch(_){}}return null;}" +
            "var P=$prompt;var M=$messages;var S=$stop;var U=$submit;" +
            "var nodes=[];for(var k=0;k<M.length;k++){try{var f=document.querySelectorAll(M[k]);for(var j=0;j<f.length;j++){nodes.push(f[j]);}}catch(e){}}" +
            "var last=nodes.length?nodes[nodes.length-1].innerText:'';" +
            "var body=(document.body?document.body.innerText:'').slice(0,2000);" +
            "var pe=first(P);var promptFound=!!pe;" +
            "var composerKind=!pe?'none':((pe.tagName==='TEXTAREA'||pe.tagName==='INPUT')?'textarea':(pe.isContentEditable?'contenteditable':'unknown'));" +
            "return JSON.stringify({messageCount:nodes.length,lastText:(last||'').slice(-4000)," +
            "promptFound:promptFound,submitFound:any(U),generating:any(S),composerKind:composerKind," +
            "markerFound:!!document.querySelector(\"$MARKER_SELECTOR\")," +
            "loginRequired:!promptFound&&/log\\s*in|sign\\s*up/i.test(body)})})()"
    }

    override fun health(snapshot: Snapshot, url: String): AdapterHealth {
        val recognized = detect(url)
        // Provider-specific identity: the DeepSeek marker must be present.
        // A generic textarea on the right host is never enough to submit.
        val identity = recognized && snapshot.markerFound
        val enterCapable = snapshot.composerKind == "textarea" ||
            snapshot.composerKind == "contenteditable"
        return AdapterHealth(
            site = id,
            recognized = recognized,
            promptInput = identity && snapshot.promptFound,
            submitControl = identity && (snapshot.submitFound || enterCapable),
            responseContainer = identity && snapshot.messageCount > 0,
            loginState = if (snapshot.loginRequired) "logged_out" else "unknown",
            adapterVersion = VERSION,
            host = url.substringAfter("://", url).substringBefore('/'),
            uiVariant = "candidate-probe"
        )
    }
}
