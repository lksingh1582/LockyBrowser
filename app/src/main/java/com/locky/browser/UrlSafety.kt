package com.locky.browser

import android.net.Uri

/** Simple offline heuristics for suspicious URLs. This is a warning aid, not a full phishing database. */
object UrlSafety {
    private val brands = listOf(
        "paypal", "google", "facebook", "instagram", "whatsapp", "amazon", "apple", "microsoft",
        "netflix", "hdfcbank", "icicibank", "paytm", "phonepe", "binance", "gmail", "youtube"
    )
    private val badTlds = setOf("tk", "ml", "ga", "cf", "gq", "zip", "mov")
    private val secondLevel = setOf("co", "com", "org", "net", "gov", "ac")
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Rough "registrable domain" (example.com, example.co.uk) used to compare sites. */
    fun siteOf(host: String?): String {
        val h = host?.lowercase() ?: return ""
        val l = h.split('.')
        if (l.size <= 2) return h
        val n = if (l[l.size - 2] in secondLevel && l.last().length == 2) 3 else 2
        return l.takeLast(n).joinToString(".")
    }

    /** Returns a human-readable reason if the URL looks suspicious, otherwise null. */
    fun check(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "localhost") return null

        if (uri.userInfo != null) return "The address contains a hidden username part (@), a common phishing trick."
        if (ipv4.matches(host)) return "This site uses a raw IP address instead of a normal domain name."
        if (host.contains("xn--")) return "The domain uses look-alike (punycode) characters."

        val labels = host.split('.')
        if (labels.size >= 2) {
            val tld = labels.last()
            if (tld in badTlds) return "The domain ending .$tld is often used by scam sites."

            val sldIndex = if (labels.size >= 3 && labels[labels.size - 2] in secondLevel) labels.size - 3 else labels.size - 2
            val sld = labels[sldIndex]
            val others = labels.filterIndexed { i, _ -> i != sldIndex }
            for (brand in brands) {
                if (sld == brand) continue
                if (others.any { it == brand }) return "The domain imitates \"$brand\" but is not the real site."
                if (sld.contains(brand) && sld.contains('-')) return "The domain imitates \"$brand\" but is not the real site."
            }
        }
        return null
    }
}
