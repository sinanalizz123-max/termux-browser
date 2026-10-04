package com.termux.browser

/**
 * Static, application-owned extraction scripts. Data NEVER flows into these
 * strings: they take no parameters and return JSON, which the native side
 * parses and truncates. There is intentionally no arbitrary-JS endpoint.
 */
object PageScripts {
    const val TITLE = "(function(){return JSON.stringify({title:document.title||''})})()"
    const val TEXT =
        "(function(){return JSON.stringify({text:(document.body?document.body.innerText:'')})})()"
    const val LINKS =
        "(function(){return JSON.stringify({links:Array.prototype.slice.call(document.links,0,500).map(function(a){return{text:(a.innerText||'').slice(0,200),href:a.href||'',visible:!!(a.offsetWidth||a.offsetHeight)}})})})()"
    const val SELECTION =
        "(function(){return JSON.stringify({text:(window.getSelection?window.getSelection().toString():'')})})()"
    const val IMAGES =
        "(function(){return JSON.stringify({images:Array.prototype.slice.call(document.images,0,200).map(function(m){return{src:m.currentSrc||m.src||'',alt:(m.alt||'').slice(0,200)}})})})()"

    /**
     * Deterministic readability-style heuristic: prefers article/main
     * content, falls back to bounded body extraction (native truncation is
     * authoritative). No network, no waiting, no async DOM dependence.
     */
    const val MAIN_CONTENT =
        "(function(){function t(e){return e?(e.innerText||''):'';}var el=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=\"main\"]');var s=t(el);if(!s||s.length<200){s=t(document.body);}return JSON.stringify({text:s})})()"

    fun forScope(scope: String): String = when (scope) {
        "title" -> TITLE
        "links" -> LINKS
        "selection" -> SELECTION
        "main_content" -> MAIN_CONTENT
        "images" -> IMAGES
        else -> TEXT
    }
}
