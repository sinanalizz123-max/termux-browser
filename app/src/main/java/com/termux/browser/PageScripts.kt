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

    fun forScope(scope: String): String = when (scope) {
        "title" -> TITLE
        "links" -> LINKS
        "selection" -> SELECTION
        else -> TEXT
    }
}
