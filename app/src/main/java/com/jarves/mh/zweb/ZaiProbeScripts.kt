package com.jarves.mh.zweb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JavaScript snippets the engine evaluates inside the chat.z.ai WebView.
 *
 * Every DOM lookup inside these snippets goes through the fallback lists from
 * [ZaiWebConfig] — no selector is ever embedded in Kotlin. The snippets are
 * pure string builders so they stay JVM-testable.
 */
object ZaiProbeScripts {

    // Shared helper injected at the top of every snippet.
    private const val HELPERS_JS =
        "function __mhPick(list){for(var i=0;i<list.length;i++){var s=list[i];" +
            "if(s.lastIndexOf('url-contains:',0)===0){if(location.href.indexOf(s.slice(13))!==-1)return{by:s,url:true};continue;}" +
            "if(s.lastIndexOf('text:=',0)===0){var t=s.slice(6);if(document.body&&document.body.innerText&&document.body.innerText.indexOf(t)!==-1)return{by:s,text:true};continue;}" +
            "try{var els=document.querySelectorAll(s);if(els&&els.length>0)return{by:s,els:els};}catch(e){}}" +
            "return null;}" +
            "function __mhFirst(list){var r=__mhPick(list);if(!r)return null;" +
            "if(r.url||r.text)return{by:r.by};" +
            "try{return{by:r.by,el:r.els[0]};}catch(e){return null;}}"

    private fun selJson(config: ZaiWebConfig, vararg groups: String): String {
        val obj = JSONObject()
        groups.forEach { group -> obj.put(group, JSONArray(config.selectorsFor(group))) }
        return obj.toString()
    }

    /**
     * Page-state probe. Returns a JSON string with:
     * inputFound, sendFound, sendDisabled, stopPresent, assistantCount,
     * normalizedText (null when unchanged since the previous probe on this page),
     * loginMatched, captchaMatched, url, pageReady.
     */
    fun buildStateProbe(config: ZaiWebConfig): String {
        val sel = selJson(
            config,
            "input", "sendButton", "stopIndicator", "assistantMessage",
            "loginMarker", "captchaMarker", "newChatButton",
        )
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var input=__mhFirst(S.input), send=__mhFirst(S.sendButton);
              var stop=__mhFirst(S.stopIndicator);
              var login=__mhPick(S.loginMarker), captcha=__mhPick(S.captchaMarker);
              var asstPick=null;
              for(var i=0;i<S.assistantMessage.length;i++){
                try{var els=document.querySelectorAll(S.assistantMessage[i]);
                  if(els&&els.length>0){asstPick={by:S.assistantMessage[i],els:els};break;}}catch(e){}
              }
              var count=asstPick?asstPick.els.length:0;
              var lastText=asstPick?(asstPick.els[asstPick.els.length-1].innerText||''):'';
              var norm=lastText.replace(/\s+/g,'');
              var unchanged=false;
              try{
                if(window.__mhProbePrev && window.__mhProbePrev.count===count && window.__mhProbePrev.norm===norm){unchanged=true;}
              }catch(e){}
              window.__mhProbePrev={count:count,norm:norm};
              return JSON.stringify({
                pageReady: !!input,
                inputFound: !!input,
                sendFound: !!send,
                sendDisabled: send ? !!send.el.disabled : true,
                stopPresent: !!stop,
                assistantCount: count,
                normalizedText: unchanged ? null : norm,
                loginMatched: !!login,
                captchaMatched: !!captcha,
                url: location.href
              });
            })()
        """.trimIndent()
    }

    /** Fetches the session auth probe on the page origin (cookies included) and reports signed-in state. */
    fun buildAuthProbe(config: ZaiWebConfig): String {
        val path = JSONObject.quote(config.authProbePath)
        return """
            (function(){
              return fetch($path,{credentials:'include',headers:{'Accept':'application/json'}})
                .then(function(r){return r.json();})
                .then(function(j){
                  var role=(j&&j.role)||''; var email=(j&&j.email)||'';
                  var guest=(role==='guest')||(/\bguest[0-9.-]*@guest\.com$/i.test(email));
                  return JSON.stringify({signedIn: !!j && !j.error && !guest, email: guest?'':email, role: role});
                })
                .catch(function(e){return JSON.stringify({signedIn:false, email:'', error:String(e)});});
            })()
        """.trimIndent()
    }

    /** Sets the prompt text on the input element using the native value setter (Svelte-safe). */
    fun buildInjectScript(config: ZaiWebConfig, text: String): String {
        val sel = selJson(config, "input")
        val textJson = JSONObject.quote(text)
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var ta=__mhFirst(S.input);
              if(!ta||!ta.el)return JSON.stringify({ok:false,why:'no-input'});
              ta.el.focus();
              var proto=ta.el.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
              var desc=Object.getOwnPropertyDescriptor(proto,'value');
              if(desc&&desc.set){desc.set.call(ta.el,$textJson);}else{ta.el.value=$textJson;}
              ta.el.dispatchEvent(new Event('input',{bubbles:true}));
              ta.el.dispatchEvent(new Event('change',{bubbles:true}));
              return JSON.stringify({ok:true,len:(ta.el.value||'').length});
            })()
        """.trimIndent()
    }

    /** Clicks the send button; reports why it could not (still disabled, missing). */
    fun buildClickSendScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "sendButton")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var b=__mhFirst(S.sendButton);
              if(!b||!b.el)return JSON.stringify({ok:false,why:'no-send'});
              if(b.el.disabled)return JSON.stringify({ok:false,why:'send-disabled'});
              b.el.click();
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
    }

    /** Keyboard fallback: dispatch Enter on the input (chat.z.ai sends on Enter). */
    fun buildEnterFallbackScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "input")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var ta=__mhFirst(S.input);
              if(!ta||!ta.el)return JSON.stringify({ok:false,why:'no-input'});
              var opts={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
              ta.el.dispatchEvent(new KeyboardEvent('keydown',opts));
              ta.el.dispatchEvent(new KeyboardEvent('keyup',opts));
              return JSON.stringify({ok:true,via:'enter'});
            })()
        """.trimIndent()
    }

    /** Clicks the stop indicator button (used on user-cancel). Best effort. */
    fun buildClickStopScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "stopIndicator")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var stop=__mhFirst(S.stopIndicator);
              if(!stop||!stop.el)return JSON.stringify({ok:false});
              var btn=stop.el.querySelector('button')||stop.el;
              try{btn.click();}catch(e){return JSON.stringify({ok:false});}
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
    }

    /** Clicks "new chat" so every run starts from a clean conversation. Best effort. */
    fun buildClickNewChatScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "newChatButton")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var el=__mhFirst(S.newChatButton);
              if(!el||!el.el)return JSON.stringify({ok:false});
              try{el.el.click();}catch(e){return JSON.stringify({ok:false});}
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
    }

    /**
     * Final extraction: raw innerText of the last assistant message plus every
     * fenced code block inside it (language + exact textContent).
     */
    fun buildExtractScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "assistantMessage", "codeBlock")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var pick=null;
              for(var i=0;i<S.assistantMessage.length;i++){
                try{var els=document.querySelectorAll(S.assistantMessage[i]);
                  if(els&&els.length>0){pick={by:S.assistantMessage[i],els:els};break;}}catch(e){}
              }
              if(!pick)return JSON.stringify({ok:false,why:'no-assistant'});
              var last=pick.els[pick.els.length-1];
              var codeEls=[];
              for(var j=0;j<S.codeBlock.length;j++){
                try{codeEls=last.querySelectorAll(S.codeBlock[j]);if(codeEls&&codeEls.length>0)break;}catch(e){}
              }
              var blocks=[];
              for(var k=0;k<codeEls.length;k++){
                var c=codeEls[k];var m=((c.className||'')+'').match(/language-([A-Za-z0-9_+#.-]+)/);
                blocks.push({lang:m?m[1]:'',text:c.textContent||''});
              }
              return JSON.stringify({ok:true,text:last.innerText||'',blocks:blocks});
            })()
        """.trimIndent()
    }
}
