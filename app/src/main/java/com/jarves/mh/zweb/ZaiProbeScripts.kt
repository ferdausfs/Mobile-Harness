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
    //
    // `text-button:=<literal>` matches a VISIBLE clickable element (button /
    // [role=button] / anchor) whose innerText or aria-label contains <literal>
    // (case-insensitive). Added for the sign-in sheet: the drawer's Sign in
    // control is a plain button without a stable id (live probe 2026-10-18).
    private const val HELPERS_JS =
        "function __mhPick(list){for(var i=0;i<list.length;i++){var s=list[i];" +
            "if(s.lastIndexOf('url-contains:',0)===0){if(location.href.indexOf(s.slice(13))!==-1)return{by:s,url:true};continue;}" +
            "if(s.lastIndexOf('text:=',0)===0){var t=s.slice(6);if(document.body&&document.body.innerText&&document.body.innerText.indexOf(t)!==-1)return{by:s,text:true};continue;}" +
            "if(s.lastIndexOf('text-button:=',0)===0){var tb=s.slice(13).toLowerCase();" +
            "var cands=document.querySelectorAll('button,[role=\"button\"],a');" +
            "for(var ti=0;ti<cands.length;ti++){var ce=cands[ti];" +
            "var ct=((ce.innerText||'')+' '+(ce.getAttribute('aria-label')||'')).toLowerCase();" +
            "if(ct.indexOf(tb)!==-1&&ce.getClientRects&&ce.getClientRects().length>0)return{by:s,el:ce};}" +
            "continue;}" +
            "try{var els=document.querySelectorAll(s);if(els&&els.length>0)return{by:s,els:els};}catch(e){}}" +
            "return null;}" +
            "function __mhFirst(list){var r=__mhPick(list);if(!r)return null;" +
            "if(r.url||r.text)return{by:r.by};" +
            "try{return{by:r.by,el:r.els?r.els[0]:r.el};}catch(e){return null;}}"

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

    /**
     * Fetches the session auth probe on the page origin and reports signed-in state.
     * The path comes from the selector config (`page.authProbePath`) — never hardcoded.
     *
     * v1.0.21: mirrors chat.z.ai's own session check (verified in the prod
     * bundle): `Authorization: Bearer <localStorage.token>` when a token exists,
     * plus a cookie-only fetch as a fallback — either response confirming a real
     * (non-guest) user means signed in. The previous cookie-only probe always
     * read "guest" after a Google sign-in whose token only reached localStorage,
     * because the server answers unauthenticated requests with a FRESH GUEST
     * user (HTTP 200, role:"guest") instead of a 401.
     *
     * v1.0.23 — store-and-poll contract: `WebView.evaluateJavascript` returns a
     * script's SYNCHRONOUS completion value to its callback; a `Promise`
     * completion value serializes to `"{}"` and is never awaited on a real
     * Android WebView (on-device Diagnostics 2026-10-18: every probe read
     * `probe-error` while the site session cookies existed). This script now
     * RETURNS NOTHING — it clears `window.__mhAuthResult`, starts the two
     * fetches, and stores the JSON result in `window.__mhAuthResult` when they
     * settle. Callers read it back with [buildAuthResultReaderScript] using the
     * [AuthProbePollPolicy] loop ([runAuthProbeScript]). The in-flight guard
     * (15s expiry) keeps the probe single-flight per document: while a fetch is
     * settling, later injectors skip WITHOUT clearing, so a slow result still
     * lands and is picked up by the next read.
     *
     * Secrets: the script never returns the token value or the account email —
     * presence booleans, the role string and truncated error text only. The
     * top-level `signedIn` boolean is a compat mirror of
     * [com.jarves.mh.zweb.ZaiAuthProbeClassifier.classify] for callers that
     * have not switched to the parsed sources yet.
     */
    fun buildAuthProbe(config: ZaiWebConfig): String = buildAuthProbeForPath(config.authProbePath)

    fun buildAuthProbeForPath(path: String): String {
        val pathJson = JSONObject.quote(path)
        return """
            (function(){
              var path=$pathJson;
              function slim(j,status){
                if(j===null||typeof j!=='object')return{status:status,shape:'empty',role:'',emailPresent:false,guest:false,error:''};
                if(j.detail!=null&&j.detail!==undefined)return{status:status,shape:'detail',role:'',emailPresent:false,guest:false,error:String(j.detail).slice(0,160)};
                var role=(typeof j.role==='string')?j.role:'';
                var email=(typeof j.email==='string')?j.email:'';
                return{status:status,shape:'user',role:role,emailPresent:!!email,
                  guest:(role==='guest')||/guest[0-9.\-]*@guest\.com$/i.test(email),error:''};
              }
              function call(headers){
                return fetch(path,{credentials:'include',headers:headers})
                  .then(function(r){
                    return r.json().then(function(j){return slim(j,r.status);},function(){return slim(null,r.status);});
                  })
                  .catch(function(e){return{status:null,shape:'error',role:'',emailPresent:false,guest:false,error:String(e).slice(0,160)};});
              }
              var now=Date.now();
              if(window.__mhAuthProbeInFlight&&(now-(window.__mhAuthProbeAt||0))<15000){return;}
              window.__mhAuthProbeInFlight=true;
              window.__mhAuthProbeAt=now;
              window.__mhAuthResult=null;
              var ls=null;
              try{ls=window.localStorage?window.localStorage.getItem('token'):null;}catch(e){ls=null;}
              var bh={Accept:'application/json'};
              if(ls){bh.Authorization='Bearer '+ls;}
              Promise.all([call(bh),call({Accept:'application/json'})]).then(function(rs){
                var b=rs[0],c=rs[1];
                var bu=!!b&&b.shape==='user'&&!b.guest&&b.role!=='';
                var cu=!!c&&c.shape==='user'&&!c.guest&&c.role!=='';
                window.__mhAuthResult=JSON.stringify({
                  signedIn:bu||cu,
                  hasLocalToken:!!ls,
                  bearer:b,
                  cookie:c
                });
                window.__mhAuthProbeInFlight=false;
              });
            })()
        """.trimIndent()
    }

    /**
     * The synchronous reader for [buildAuthProbeForPath]'s stored result.
     * `evaluateJavascript`-safe by construction: its completion value is the
     * stored JSON string (WebView wraps it in one level of JSON quoting) or
     * null — never a Promise. [ZaiAuthProbeClassifier.parseEvalResult] handles
     * both forms.
     */
    fun buildAuthResultReaderScript(): String = "window.__mhAuthResult||null"

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

    // ── Phase 1: live visibility + model picker ──
    //
    // These snippets are polled/read while a turn is generating. They follow
    // the same contract as every other script here: synchronous IIFE, JSON
    // string completion value, NO Promise (the v1.0.23 store-and-poll rule).

    /**
     * Live status probe for the streaming chat card. Reads, from the CURRENT
     * page: the thinking/reasoning block text (latest details element wins),
     * whether it is still open, the partial answer text of the newest
     * assistant message, and the latest tool/step status line. Never touches
     * cookies or storage — visible DOM text only. Uses separate window keys
     * from [buildStateProbe] so the finish detector's change tracking is
     * untouched.
     */
    fun buildLiveStatusScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "thinkingBlock", "partialAnswer", "stepStatus", "stopIndicator")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              function pickAll(list){
                for(var i=0;i<list.length;i++){
                  try{var els=document.querySelectorAll(list[i]);
                    if(els&&els.length>0)return{by:list[i],els:els};}catch(e){}
                }
                return null;
              }
              var stop=__mhFirst(S.stopIndicator);
              var thinkPick=pickAll(S.thinkingBlock);
              var thinkingText='';
              var thinkingActive=false;
              if(thinkPick){
                var last=thinkPick.els[thinkPick.els.length-1];
                thinkingText=last.innerText||'';
                try{thinkingActive=last.tagName.toLowerCase()==='details'?(last.hasAttribute('open')&&last.open):false;}catch(e){thinkingActive=false;}
                if(!thinkingText&&last.querySelector){var sum=last.querySelector('summary');thinkingText=sum?(sum.innerText||''):'';}
              }
              var ansPick=pickAll(S.partialAnswer);
              var answerText=ansPick?(ansPick.els[ansPick.els.length-1].innerText||''):'';
              var stepPick=pickAll(S.stepStatus);
              var stepText='';
              if(stepPick){
                for(var s=stepPick.els.length-1;s>=0;s--){
                  var t=(stepPick.els[s].innerText||'').trim();
                  if(t){stepText=t;break;}
                }
              }
              return JSON.stringify({
                thinkingText:thinkingText,
                thinkingActive:thinkingActive,
                answerText:answerText,
                stepText:stepText,
                stopPresent:!!stop,
                url:location.href
              });
            })()
        """.trimIndent()
    }

    /** Reads the current model name from the site's own label button. */
    fun buildModelLabelScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "modelLabel")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var el=__mhFirst(S.modelLabel);
              if(!el||!el.el)return JSON.stringify({ok:false});
              var t=(el.el.innerText||'').trim();
              if(!t)return JSON.stringify({ok:false});
              return JSON.stringify({ok:true,model:t.split('\n')[0].trim()});
            })()
        """.trimIndent()
    }

    /** Clicks the site's model dropdown trigger so its options render. Best effort. */
    fun buildClickModelMenuScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "modelMenuButton")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var el=__mhFirst(S.modelMenuButton);
              if(!el||!el.el)return JSON.stringify({ok:false,why:'no-button'});
              try{el.el.click();}catch(e){return JSON.stringify({ok:false,why:'click-failed'});}
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
    }

    /**
     * Reads the open model dropdown's option rows. Only meaningful right after
     * [buildClickModelMenuScript] succeeded; the caller retries/closes on failure.
     */
    fun buildModelOptionsScript(config: ZaiWebConfig): String {
        val sel = selJson(config, "modelOption")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var pick=null;
              for(var i=0;i<S.modelOption.length;i++){
                try{var els=document.querySelectorAll(S.modelOption[i]);
                  if(els&&els.length>0){pick={by:S.modelOption[i],els:els};break;}}catch(e){}
              }
              if(!pick)return JSON.stringify([]);
              var seen={};var out=[];
              for(var k=0;k<pick.els.length&&out.length<40;k++){
                var row=pick.els[k];
                var text=(row.innerText||'').trim();
                if(!text)continue;
                var name=text.split('\n')[0].trim();
                if(!name||seen[name.toLowerCase()])continue;
                seen[name.toLowerCase()]=1;
                var desc='';
                for(var d=1;d<text.split('\n').length;d++){
                  var line=text.split('\n')[d].trim();
                  if(line&&line!=='NEW'&&line.toLowerCase().indexOf('new')!==0){desc=line;break;}
                }
                out.push({name:name,description:desc,by:pick.by});
              }
              return JSON.stringify(out);
            })()
        """.trimIndent()
    }

    /**
     * Clicks the dropdown row whose FIRST line equals [name] (case-insensitive,
     * visible rows only). Returns which selector fired so a mismatch is visible.
     */
    fun buildClickModelOptionScript(config: ZaiWebConfig, name: String): String {
        val sel = selJson(config, "modelOption")
        val nameJson = JSONObject.quote(name)
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var want=$nameJson.toLowerCase();
              for(var i=0;i<S.modelOption.length;i++){
                var rows=null;
                try{rows=document.querySelectorAll(S.modelOption[i]);}catch(e){continue;}
                if(!rows||rows.length===0)continue;
                for(var k=0;k<rows.length;k++){
                  var row=rows[k];
                  if(row.getClientRects&&row.getClientRects().length===0)continue;
                  var text=(row.innerText||'').trim();
                  if(!text)continue;
                  var first=text.split('\n')[0].trim().toLowerCase();
                  if(first===want){
                    try{row.click();return JSON.stringify({ok:true,by:S.modelOption[i]});}
                    catch(e){return JSON.stringify({ok:false,why:'click-failed'});}
                  }
                }
              }
              return JSON.stringify({ok:false,why:'no-match'});
            })()
        """.trimIndent()
    }

    // ── Sign-in sheet automation (visible WebView) ──
    //
    // The sheet drives the chat.z.ai login UI. Every lookup goes through the
    // same JSON selector groups as the hidden engine — the sheet never embeds
    // a selector in Kotlin. The selectors travel from the engine's config to
    // the UI as a small JSON payload ([parseSheetSelectors] reads it back).

    private const val SHEET_GROUPS_JSON_KEYS =
        "loginFormMarker, captchaMarker, sidebarToggle, signInButton, authProbePath"

    /** The four selector groups + auth probe path the visible sheet needs. */
    data class ZaiSheetSelectors(
        val loginFormMarker: List<String>,
        val captchaMarker: List<String>,
        val sidebarToggle: List<String>,
        val signInButton: List<String>,
        val authProbePath: String,
    )

    /**
     * Pure parser for the sheet automation payload published by
     * [com.jarves.mh.zweb.ZaiWebEngine.openSignInSheet]. Returns null when the
     * payload is absent or unreadable — the sheet then falls back to hints only.
     */
    fun parseSheetSelectors(json: String?): ZaiSheetSelectors? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(json)
            fun group(name: String): List<String> {
                val arr = obj.optJSONArray(name) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).trim().takeIf(String::isNotEmpty)
                }
            }
            ZaiSheetSelectors(
                loginFormMarker = group("loginFormMarker"),
                captchaMarker = group("captchaMarker"),
                sidebarToggle = group("sidebarToggle"),
                signInButton = group("signInButton"),
                authProbePath = obj.optString("authProbePath", "/api/v1/auths/").ifBlank { "/api/v1/auths/" },
            )
        }.getOrNull()
    }

    private fun sheetSelectorJson(selectors: ZaiSheetSelectors, vararg groups: String): String {
        val obj = JSONObject()
        groups.forEach { group ->
            val list = when (group) {
                "loginFormMarker" -> selectors.loginFormMarker
                "captchaMarker" -> selectors.captchaMarker
                "sidebarToggle" -> selectors.sidebarToggle
                "signInButton" -> selectors.signInButton
                else -> emptyList()
            }
            obj.put(group, JSONArray(list))
        }
        return obj.toString()
    }

    /**
     * Sheet page-state probe: is the login form on screen, is a captcha showing,
     * and are the automation targets (sidebar toggle / sign-in button) present?
     * The login-form check deliberately ignores URL markers — the auth route can
     * keep the /auth URL while rendering guest home content.
     */
    fun buildSheetProbeScript(selectors: ZaiSheetSelectors): String {
        val sel = sheetSelectorJson(selectors, "loginFormMarker", "captchaMarker", "sidebarToggle", "signInButton")
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var form=__mhPick(S.loginFormMarker);
              var captcha=__mhPick(S.captchaMarker);
              var side=__mhFirst(S.sidebarToggle);
              var signBtn=__mhFirst(S.signInButton);
              return JSON.stringify({
                loginFormPresent: !!(form&&(form.text||(form.els&&form.els.length>0))),
                captchaPresent: !!captcha,
                sidebarFound: !!(side&&side.el),
                signInFound: !!(signBtn&&signBtn.el),
                url: location.href
              });
            })()
        """.trimIndent()
    }

    /**
     * Clicks the first VISIBLE match of a selector group, trying every fallback
     * in order. `text-button:=` and `text:=` entries match on button text /
     * aria-label (case-insensitive); plain CSS entries scan all matches for a
     * visible one. Returns which selector fired (or ok:false) — the sheet logs
     * nothing sensitive here, just the selector that matched.
     */
    fun buildClickVisibleScript(selectors: ZaiSheetSelectors, group: String): String {
        require(group in setOf("sidebarToggle", "signInButton")) {
            "clickable sheet groups are $SHEET_GROUPS_JSON_KEYS (clickable: sidebarToggle/signInButton)"
        }
        val sel = sheetSelectorJson(selectors, group)
        return """
            (function(){
              $HELPERS_JS
              var S=$sel;
              var list=S.$group||[];
              for(var i=0;i<list.length;i++){
                var s=list[i];
                if(s.lastIndexOf('url-contains:',0)===0)continue;
                var el=null;
                if(s.lastIndexOf('text-button:=',0)===0||s.lastIndexOf('text:=',0)===0){
                  var t=s.slice(s.indexOf(':=')+2).toLowerCase();
                  var cands=document.querySelectorAll('button,[role=\"button\"],a');
                  for(var k=0;k<cands.length;k++){
                    var c=cands[k];
                    var txt=((c.innerText||'')+' '+(c.getAttribute('aria-label')||'')).toLowerCase();
                    if(txt.indexOf(t)!==-1&&c.getClientRects&&c.getClientRects().length>0){el=c;break;}
                  }
                }else{
                  try{
                    var els=document.querySelectorAll(s);
                    for(var m=0;m<els.length;m++){
                      if(els[m].getClientRects&&els[m].getClientRects().length>0){el=els[m];break;}
                    }
                  }catch(e){}
                }
                if(el){try{el.click();return JSON.stringify({ok:true,by:s});}catch(e){}}
              }
              return JSON.stringify({ok:false,group:'$group'});
            })()
        """.trimIndent()
    }
}
