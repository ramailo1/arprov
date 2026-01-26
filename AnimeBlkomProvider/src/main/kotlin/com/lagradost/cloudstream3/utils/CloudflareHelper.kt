package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import org.jsoup.nodes.Document

/**
 * Cloudflare-aware request helper for CloudStream providers.
 *
 * Usage:
 *  - getDocOrNull(): returns null if blocked (main page / search)
 *  - getDocOrThrow(): throws ErrorLoadingException if blocked (load / loadLinks)
 */
object CloudflareHelper {

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
    )

    private fun headers(referer: String, custom: Map<String, String>): Map<String, String> {
        val ref = if (referer.isNotBlank()) mapOf("Referer" to referer) else emptyMap()
        return baseHeaders() + ref + custom
    }

    /** Detects Cloudflare IUAM, Managed, Turnstile, Bot Fight pages */
    fun isCloudflare(doc: Document): Boolean {
        val title = doc.title()
        return (
            title.contains("Just a moment", ignoreCase = true) ||
            title.isEmpty() && (
                doc.select("iframe[src*='challenge']").isNotEmpty() ||
                doc.select("script[src*='challenge']").isNotEmpty()
            ) ||
            doc.select("form[action*='cloudflare']").isNotEmpty() ||
            doc.html().contains("cf-turnstile", true) ||
            doc.html().contains("cf_chl_opt", true) ||
            doc.select("div#cf-wrapper").isNotEmpty()
        )
    }

    /** Fetch a document, return null if Cloudflare blocks */
    suspend fun getDocOrNull(
        url: String,
        referer: String = "",
        customHeaders: Map<String, String> = emptyMap()
    ): Document? {
        val response = app.get(
            url,
            headers = headers(referer, customHeaders),
            allowRedirects = true,
            timeout = 15 // 15s timeout to avoid long black pages
        )

        if (response.code in listOf(403, 429, 503)) return null

        val doc = response.document
        return if (isCloudflare(doc)) null else doc
    }

    /** Fetch a document, throws ErrorLoadingException if blocked */
    suspend fun getDocOrThrow(
        url: String,
        referer: String = "",
        errorMessage: String = "Cloudflare protection – please open in WebView"
    ): Document {
        return getDocOrNull(url, referer) ?: throw ErrorLoadingException(errorMessage)
    }
}
