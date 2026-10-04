package com.termux.browser.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * M8 prompt bridge lifecycle. The prompt travels into the page ONLY as a
 * kotlinx-serialized JSON string literal assigned to window.__tbPrompt
 * immediately before submission, inside a single atomic script whose finally
 * block always deletes it — on success, failure, and JS-side exceptions.
 * Native code additionally best-effort clears it after waiter completion,
 * cancellation, timeout, navigation, and takeover.
 *
 * Nothing here concatenates prompt text into executable code: the JSON
 * literal is data, candidate selectors are data, indices are integers.
 */
object JsPrompt {
    private val json = Json

    /** JSON string literal for the prompt (safe to embed as a JS value). */
    fun quotedPrompt(prompt: String): String = json.encodeToString(prompt)

    fun quotedStringList(values: List<String>): String = json.encodeToString(values)

    const val CLEAR_SCRIPT =
        "(function(){try{delete window.__tbPrompt;}catch(e){window.__tbPrompt=null;}return 'cleared';})()"

    /**
     * Fill-only variant: sets the composer text exactly like submitScript
     * but performs NO click and NO Enter. Used when a learned send control
     * will deliver the message with a genuine platform tap instead —
     * required for controls that ignore script-synthesized events.
     */
    fun fillScript(
        promptJson: String,
        promptCandidatesJson: String
    ): String {
        return "(function(){window.__tbPrompt=$promptJson;" +
            "var P=$promptCandidatesJson;" +
            "var R={filled:false,reason:'unknown'};" +
            "try{" +
            "var pe=null;for(var k=0;k<P.length;k++){" +
            "try{var e=document.querySelector(P[k]);if(e){pe=e;R.promptIndex=k;break;}}catch(_){}}" +
            "if(!pe){R.reason='no-prompt';return JSON.stringify(R);}" +
            fillBody() +
            "R.filled=true;" +
            "}finally{try{delete window.__tbPrompt;}catch(_){window.__tbPrompt=null;}}" +
            "return JSON.stringify(R);})()"
    }

    /**
     * Atomic assign + submit + cleanup script. promptJson must come from
     * [quotedPrompt]; promptIndex/submitIndex are integer indices into the
     * baked candidate arrays (or -1 to auto-pick the first match).
     */
    fun submitScript(
        promptJson: String,
        promptCandidatesJson: String,
        submitCandidatesJson: String,
        promptIndex: Int = -1,
        submitIndex: Int = -1
    ): String {
        // Indices are integers baked in by the Kotlin template (safe by
        // construction); promptJson must come from quotedPrompt (data).
        return "(function(){window.__tbPrompt=$promptJson;" +
            "var P=$promptCandidatesJson;var S=$submitCandidatesJson;" +
            "var R={submitted:false,reason:'unknown'};" +
            "try{" +
            "var pe=null;for(var k=0;k<P.length;k++){if(${promptIndex}>=0&&k!=${promptIndex})continue;" +
            "try{var e=document.querySelector(P[k]);if(e){pe=e;R.promptIndex=k;break;}}catch(_){}}" +
            "if(!pe){R.reason='no-prompt';return JSON.stringify(R);}" +
            fillBody() +
            "var se=null;for(var j=0;j<S.length;j++){if(${submitIndex}>=0&&j!=${submitIndex})continue;" +
            "try{var q=S[j];var last=false;" +
            "if(q.indexOf('LAST:')===0){q=q.slice(5);last=true;}" +
            "var b=null;if(last){var all=document.querySelectorAll(q);if(all.length)b=all[all.length-1];}" +
            "else{b=document.querySelector(q);}" +
            "if(b){se=b;R.submitIndex=j;break;}}catch(_){}}" +
            "if(se){se.click();R.submitted=true;R.method='button';}" +
            "else{" +
            "function key(t){var e=new KeyboardEvent(t,{key:'Enter',code:'Enter',bubbles:true,cancelable:true});" +
            "try{Object.defineProperty(e,'keyCode',{value:13});Object.defineProperty(e,'which',{value:13});}catch(_){}" +
            "return e;}" +
            "pe.dispatchEvent(key('keydown'));pe.dispatchEvent(key('keypress'));pe.dispatchEvent(key('keyup'));" +
            "R.submitted=true;R.method='enter';}" +
            "}finally{try{delete window.__tbPrompt;}catch(_){window.__tbPrompt=null;}}" +
            "return JSON.stringify(R);})()"
    }

    /**
     * Single-element rectangle probe. Returns JSON {x,y,w,h} in CSS pixels
     * or null. The selector travels as JSON data, like everywhere else.
     */
    fun rectScript(selectorJson: String): String =
        "(function(){try{var e=document.querySelector($selectorJson);" +
            "if(!e)return JSON.stringify(null);var r=e.getBoundingClientRect();" +
            "return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height});}" +
            "catch(_){return JSON.stringify(null);}})()"

    /** Shared composer-fill fragment: set text, notify the framework. */
    private fun fillBody(): String =
        "var text=window.__tbPrompt;" +
            "function setNative(el,txt){" +
            "try{var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;" +
            "setter.call(el,txt);return true;}catch(_){try{el.value=txt;return true;}catch(_){return false;}}}" +
            "if('value' in pe){pe.focus();if(!setNative(pe,text)){R.reason='prompt-readonly';return JSON.stringify(R);}" +
            "pe.dispatchEvent(new Event('input',{bubbles:true}));" +
            "pe.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "else if(pe.isContentEditable){pe.focus();pe.textContent=text;" +
            "pe.dispatchEvent(new InputEvent('input',{bubbles:true}));" +
            "try{var r=document.createRange();r.selectNodeContents(pe);r.collapse(false);" +
            "var s2=getSelection();s2.removeAllRanges();s2.addRange(r);}catch(_){}}" +
            "else{R.reason='prompt-readonly';return JSON.stringify(R);}"
}
