package com.locky.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Domain-based ad/tracker blocker plus cosmetic CSS for leftover ad slots. */
class AdBlocker(context: Context) {
    private val ads = load(context, "ads.txt")
    private val trackers = load(context, "trackers.txt")

    private fun load(context: Context, name: String): HashSet<String> =
        context.assets.open(name).bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toHashSet()
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
