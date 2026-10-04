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
            "var text=window.__tbPrompt;" +
            "if('value' in pe){pe.focus();pe.value=text;" +
            "pe.dispatchEvent(new Event('input',{bubbles:true}));" +
            "pe.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "else if(pe.isContentEditable){pe.focus();pe.textContent=text;" +
            "pe.dispatchEvent(new InputEvent('input',{bubbles:true}));}" +
            "else{R.reason='prompt-readonly';return JSON.stringify(R);}" +
            "var se=null;for(var j=0;j<S.length;j++){if(${submitIndex}>=0&&j!=${submitIndex})continue;" +
            "try{var b=document.querySelector(S[j]);if(b){se=b;R.submitIndex=j;break;}}catch(_){}}" +
            "if(se){se.click();R.submitted=true;R.method='button';}" +
            "else{" +
            "var ke=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true});" +
            "pe.dispatchEvent(ke);R.submitted=true;R.method='enter';}" +
            "}finally{try{delete window.__tbPrompt;}catch(_){window.__tbPrompt=null;}}" +
            "return JSON.stringify(R);})()"
    }
}
