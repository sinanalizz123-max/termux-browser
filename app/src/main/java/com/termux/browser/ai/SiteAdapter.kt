package com.termux.browser.ai

/**
 * M7 provider-agnostic AI adapter contract.
 *
 * Hard rule: detect(url) is side-effect-free. It operates solely on the URL,
 * host, and immutable adapter metadata — no WebView inspection, no JS
 * execution, no network, no state mutation. Detection must stay a cheap,
 * deterministic pure function.
 */
data class SelectorSet(
    val prompt: List<String> = emptyList(),
    val submit: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val stop: List<String> = emptyList()
)

data class AdapterHealth(
    val site: String,
    val recognized: Boolean,
    val promptInput: Boolean = false,
    val submitControl: Boolean = false,
    val responseContainer: Boolean = false,
    val loginState: String = "unknown",
    val adapterVersion: String = "1",
    val host: String = "",
    val uiVariant: String = ""
)

interface SiteAdapter {
    val id: String

    /** Exact hosts this adapter owns. Empty = generic fallback. */
    val hosts: Set<String>

    fun detect(url: String): Boolean
    fun selectors(): SelectorSet

    /** Static, zero-parameter snapshot script returning Snapshot JSON. */
    fun snapshotScript(): String

    /** Optional provider-identity marker selector for the snapshot report. */
    fun markerSelector(): String? = null

    /**
     * Deterministic, side-effect-free health from a snapshot. Default fails
     * closed: only recognized adapters with confirmed controls pass.
     */
    fun health(snapshot: Snapshot, url: String): AdapterHealth {
        val recognized = detect(url)
        return AdapterHealth(site = id, recognized = recognized)
    }
}

/**
 * Registry with deterministic precedence: exact host owners first (in
 * registration order), generic fallback last. A generic adapter can never
 * shadow a specific one.
 */
class AdapterRegistry(adapters: List<SiteAdapter>) {

    private val ordered = adapters.toList()

    fun detect(url: String): SiteAdapter? {
        val host = hostOf(url)
        ordered.firstOrNull { adapter ->
            adapter.hosts.isNotEmpty() &&
                (host in adapter.hosts || adapter.detect(url))
        }?.let { return it }
        return ordered.firstOrNull { it.hosts.isEmpty() && it.detect(url) }
    }

    fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        return withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    }
}

/**
 * Shared static snapshot script builder. Candidate lists are JSON data
 * baked into a fixed script shape — never string-concatenated code.
 * An optional provider marker selector adds markerFound to the report.
 */
fun buildSnapshotScript(
    prompt: List<String>,
    messages: List<String>,
    stop: List<String>,
    submit: List<String>,
    markerSelector: String? = null
): String {
    val json = kotlinx.serialization.json.Json
    val p = json.encodeToString(prompt)
    val m = json.encodeToString(messages)
    val s = json.encodeToString(stop)
    val u = json.encodeToString(submit)
    val marker = if (markerSelector != null) {
        "markerFound:!!document.querySelector(${json.encodeToString(markerSelector)}),"
    } else {
        ""
    }
    return "(function(){function any(L){for(var k=0;k<L.length;k++){try{if(document.querySelector(L[k]))return true;}catch(e){}}return false;}" +
        "function first(L){for(var k=0;k<L.length;k++){try{var e=document.querySelector(L[k]);if(e)return e;}catch(_){}}return null;}" +
        "var P=$p;var M=$m;var S=$s;var U=$u;" +
        "var nodes=[];for(var k=0;k<M.length;k++){try{var f=document.querySelectorAll(M[k]);for(var j=0;j<f.length;j++){nodes.push(f[j]);}}catch(e){}}" +
        "var last=nodes.length?nodes[nodes.length-1].innerText:'';" +
        "var body=(document.body?document.body.innerText:'').slice(0,2000);" +
        "var pe=first(P);var promptFound=!!pe;" +
        "var composerKind=!pe?'none':((pe.tagName==='TEXTAREA'||pe.tagName==='INPUT')?'textarea':(pe.isContentEditable?'contenteditable':'unknown'));" +
        "return JSON.stringify({messageCount:nodes.length,lastText:(last||'').slice(-4000)," +
        "promptFound:promptFound,submitFound:any(U),generating:any(S),composerKind:composerKind,$marker" +
        "loginRequired:!promptFound&&/log\\s*in|sign\\s*up/i.test(body)})})()"
}

/**
 * Learned override: a base adapter whose submit candidates lead with a
 * user-taught selector. Detection, health, waiting, and cancellation stay
 * base-owned; only the submit list is upgraded.
 */
class LearnedAdapter(
    private val base: SiteAdapter,
    learnedSubmit: String
) : SiteAdapter {
    override val id = base.id
    override val hosts = base.hosts

    private val merged = (listOf(learnedSubmit) + base.selectors().submit).distinct()

    override fun detect(url: String): Boolean = base.detect(url)
    override fun selectors(): SelectorSet = base.selectors().copy(submit = merged)
    override fun snapshotScript(): String {
        val selectors = base.selectors()
        return buildSnapshotScript(
            selectors.prompt, selectors.messages, selectors.stop, merged,
            base.markerSelector()
        )
    }

    override fun markerSelector(): String? = base.markerSelector()

    override fun health(snapshot: Snapshot, url: String): AdapterHealth =
        base.health(snapshot, url)
}

/**
 * Generic fallback adapter. Only generic, semantic selectors — no
 * provider-specific knowledge. Specific adapters (later milestones) own
 * their hosts and take precedence in the registry.
 */
class GenericAdapter : SiteAdapter {
    override val id = "generic"
    override val hosts: Set<String> = emptySet()

    override fun detect(url: String): Boolean = true

    override fun selectors(): SelectorSet = SelectorSet(
        prompt = listOf(
            "textarea",
            "textarea[name='prompt']",
            "[contenteditable='true']",
            "input[type='text']"
        ),
        submit = listOf(
            "button[type='submit']",
            "[data-composer-submit]",
            "button[aria-label*='Send']"
        ),
        messages = listOf(
            "[data-message-role='assistant']",
            "[role='log']",
            "main",
            "article"
        ),
        stop = listOf("[aria-label*='Stop']")
    )

    override fun snapshotScript(): String =
        "(function(){var m=document.querySelectorAll('[data-message-role],article,[role=\"log\"]');" +
            "var last=m.length?m[m.length-1].innerText:'';" +
            "var stop=!!document.querySelector('[aria-label*=\"Stop\"]');" +
            "return JSON.stringify({messageCount:m.length,lastText:(last||'').slice(-4000),generating:stop})})()"
}
