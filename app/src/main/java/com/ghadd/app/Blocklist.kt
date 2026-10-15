package com.ghadd.app

/** قوائم محلية مصغّرة. الحجب الأساسي يتم عبر DNS العائلي (1.1.1.3)، وهذه طبقة إضافية. */
object Blocklist {
    // خوادم DoH التي تتجاوز فلتر الـ DNS
    private val doh = setOf(
        "dns.google", "dns.google.com", "cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com", "dns.quad9.net", "doh.opendns.com", "dns.nextdns.io",
        "dns.adguard.com", "doh.cleanbrowsing.org"
    )
    private val hostKeywords = listOf(
        "porn", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "hentai",
        "brazzers", "onlyfans", "nsfw"
    )
    private val textKeywords = hostKeywords + listOf("سكس", "بورن", "اباحي", "إباحي", "نيك")

    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase()
        if (doh.any { h == it || h.endsWith(".$it") }) return true
        return hostKeywords.any { h.contains(it) }
    }

    fun matchesText(text: String): Boolean {
        val t = text.lowercase()
        return textKeywords.any { t.contains(it) }
    }
}
