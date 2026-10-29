package com.locky.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Domain-based ad/tracker blocker plus cosmetic CSS for leftover ad slots. */
class AdBlocker(context: Context) {
    // ads_extra.txt is a large list fetched by the CI build; it is optional (missing in local builds).
    private val ads = load(context, "ads.txt").also { it.addAll(loadOptional(context, "ads_extra.txt")) }
    private val trackers = load(context, "trackers.txt")

    private fun load(context: Context, name: String): HashSet<String> =
        context.assets.open(name).bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toHashSet()
        }

    private fun loadOptional(context: Context, name: String): Set<String> =
        try { load(context, name) } catch (e: java.io.IOException) { emptySet() }

    /** True for domain endings that are almost only used by throwaway ad/scam redirect hosts. */
    fun isJunkHost(host: String?): Boolean {
        val tld = host?.lowercase()?.substringAfterLast('.') ?: return false
        return tld in junkTlds
    }

    fun isBlocked(uri: Uri, blockAds: Boolean, blockTrackers: Boolean): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (blockAds && matches(host, ads)) return true
        return blockTrackers && matches(host, trackers)
    }

    /** Matches host and every parent domain (a.b.example.com -> b.example.com -> example.com). */
    private fun matches(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    companion object {
        private val junkTlds = setOf("cfd", "qpon", "sbs", "cyou", "icu", "click", "monster")

        /**
         * Runs at document start in every page: stops window.open() popups and removes invisible
         * full-screen overlays that steal taps (the classic "every click opens an ad" trick).
         */
        fun pageScript(blockOpen: Boolean, removeOverlays: Boolean): String {
            val sb = StringBuilder("(function(){if(window.__lk)return;window.__lk=1;")
            if (blockOpen) sb.append(OPEN_JS)
            if (removeOverlays) sb.append(SWEEP_JS)
            return sb.append("})();").toString()
        }

        private const val OPEN_JS = "try{window.open=function(){return null;};}catch(e){}"

        private const val SWEEP_JS = """function sweep(){try{var b=document.body;if(!b)return;var W=innerWidth,H=innerHeight;
Array.prototype.slice.call(b.children).forEach(function(el){
var t=el.tagName;if(t==='SCRIPT'||t==='STYLE'||t==='LINK')return;
var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return;
var r=el.getBoundingClientRect();if(r.width<W*0.8||r.height<H*0.8)return;
if((parseInt(cs.zIndex)||0)<100)return;
var empty=((el.innerText||'').trim()==='')&&el.querySelectorAll('*').length<=1;
if(parseFloat(cs.opacity)<0.1||empty){el.remove();}});}catch(e){}}
function sticky(){try{var b=document.body;if(!b)return;var W=innerWidth,H=innerHeight;
var list=[];Array.prototype.slice.call(b.children).forEach(function(c){list.push(c);
Array.prototype.slice.call(c.children).forEach(function(d){list.push(d);});});
list.forEach(function(el){var t=el.tagName;if(t==='SCRIPT'||t==='STYLE'||t==='LINK'||t==='NAV'||t==='HEADER')return;
var cs=getComputedStyle(el);if(cs.position!=='fixed')return;
var r=el.getBoundingClientRect();
if(r.width<W*0.7||r.height>H*0.4||r.height<30||r.bottom<H-12)return;
if(el.querySelector('input,textarea,select,form'))return;
if(el.tagName==='IFRAME'||el.querySelector('img,iframe,video,canvas,ins')){el.remove();}});}catch(e){}}
setInterval(function(){sweep();sticky();},1000);"""

        fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        /** Extra resources skipped in Data saver mode: video and web fonts. */
        private val heavyExt = setOf("mp4", "webm", "mkv", "avi", "mov", "woff", "woff2", "ttf", "otf")

        fun isHeavyResource(uri: Uri): Boolean {
            val last = uri.lastPathSegment?.lowercase() ?: return false
            val ext = last.substringAfterLast('.', "")
            return ext in heavyExt
        }

        const val COSMETIC_JS = """(function(){try{var s=document.createElement('style');s.textContent='ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[class*="ad-banner"],[class*="adbox"],.ad-container,.advert,.advertisement,iframe[src*="doubleclick.net"],iframe[src*="googlesyndication"]{display:none!important}';(document.head||document.documentElement).appendChild(s);}catch(e){}})();"""
    }
}
