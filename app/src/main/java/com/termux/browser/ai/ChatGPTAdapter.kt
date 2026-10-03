package com.termux.browser.ai

/**
 * M8 first provider adapter. ChatGPT web UI, semantic-first candidate sets.
 * Candidates are ordered data; the registry never executes them blindly and
 * the health gate fails closed when prompt/submit cannot be confirmed.
 */
class ChatGPTAdapter : SiteAdapter {
    override val id = "chatgpt"
    override val hosts = setOf("chatgpt.com", "chat.openai.com")

    companion object {
        const val VERSION = "m8-1"

        val PROMPT_CANDIDATES = listOf(
            "#prompt-textarea",
            "div[contenteditable='true'][data-testid]",
            "textarea[name='prompt']",
            "textarea[placeholder*='Message']",
            "div[contenteditable='true']",
            "textarea"
        )
        val SUBMIT_CANDIDATES = listOf(
            "button[data-testid='send-button']",
            "button[aria-label*='Send']",
            "button[data-composer-submit]",
            "button[type='submit']"
        )
        val MESSAGE_CANDIDATES = listOf(
            "[data-message-author-role='assistant']",
            "[data-message-role='assistant']",
            ".markdown"
        )
        val STOP_CANDIDATES = listOf(
            "button[data-testid='stop-button']",
            "button[aria-label*='Stop']"
        )
    }

    override fun detect(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return host in hosts || hosts.any { host == it || host.endsWith(".$it") }
    }

    override fun selectors(): SelectorSet = SelectorSet(
        prompt = PROMPT_CANDIDATES,
        submit = SUBMIT_CANDIDATES,
        messages = MESSAGE_CANDIDATES,
        stop = STOP_CANDIDATES
    )

    /**
     * Static snapshot script with baked candidate lists (data, zero params).
     * Reports candidate presence so health() can fail closed, plus the usual
     * message/generating observation and a login-wall heuristic.
     */
    override fun snapshotScript(): String {
        val json = kotlinx.serialization.json.Json
        val prompt = json.encodeToString(PROMPT_CANDIDATES)
        val messages = json.encodeToString(MESSAGE_CANDIDATES)
        val stop = json.encodeToString(STOP_CANDIDATES)
        val submit = json.encodeToString(SUBMIT_CANDIDATES)
        return "(function(){function any(L){for(var k=0;k<L.length;k++){try{if(document.querySelector(L[k]))return true;}catch(e){}}return false;}" +
            "var P=$prompt;var M=$messages;var S=$stop;var U=$submit;" +
            "var nodes=[];for(var k=0;k<M.length;k++){try{var f=document.querySelectorAll(M[k]);for(var j=0;j<f.length;j++){nodes.push(f[j]);}}catch(e){}}" +
            "var last=nodes.length?nodes[nodes.length-1].innerText:'';" +
            "var body=(document.body?document.body.innerText:'').slice(0,2000);" +
            "var promptFound=any(P);" +
            "return JSON.stringify({messageCount:nodes.length,lastText:(last||'').slice(-4000)," +
            "promptFound:promptFound,submitFound:any(U),generating:any(S)," +
            "loginRequired:!promptFound&&/log\\s*in|sign\\s*up/i.test(body)})})()"
    }

    override fun health(snapshot: Snapshot, url: String): AdapterHealth {
        val recognized = detect(url)
        return AdapterHealth(
            site = id,
            recognized = recognized,
            promptInput = recognized && snapshot.promptFound,
            submitControl = recognized && snapshot.submitFound,
            responseContainer = recognized && snapshot.messageCount > 0,
            loginState = if (snapshot.loginRequired) "logged_out" else "unknown",
            adapterVersion = VERSION,
            host = url.substringAfter("://", url).substringBefore('/'),
            uiVariant = "candidate-probe"
        )
    }
}
