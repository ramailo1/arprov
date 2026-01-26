package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import org.jsoup.nodes.Document

/**
 * Cloudflare-aware request helper for CloudStream providers.
 * 
 * Usage in any provider:
 * ```
 * // For main page / search (graceful failure)
 * val doc = CloudflareHelper.getDocOrNull(url) ?: return emptyList()
 * 
 * // For load() / loadLinks() (throws for WebView trigger)
 * val doc = CloudflareHelper.getDocOrThrow(url)
 * ```
 * 
 * This helper:
 * - Detects all Cloudflare protection types (IUAM, Managed Challenge, Turnstile)
 * - Returns null or throws ErrorLoadingException if blocked
 * - Works with usesWebView = true to trigger WebView when needed
 */
object CloudflareHelper {

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
    )

    private fun headers(
        referer: String,
        custom: Map<String, String>
    ): Map<String, String> {
        // Only add Referer if not blank (some sites break with empty Referer)
        val ref = if (referer.isNotBlank()) mapOf("Referer" to referer) else emptyMap()
        return baseHeaders() + ref + custom
    }

    /**
     * Detects Cloudflare challenge pages (IUAM, Managed, Turnstile, Bot Fight)
     */
    fun isCloudflare(doc: Document): Boolean {
        val title = doc.title()

        return (
            title.contains("Just a moment", true) ||
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

    /**
     * Fetches a URL and returns null if Cloudflare is detected.
     * Use for main page / search where graceful failure is appropriate.
     * 
     * @param url The URL to fetch
     * @param referer Optional referer header (only sent if not blank)
     * @param customHeaders Optional additional headers to merge
     * @return Document or null if Cloudflare blocked
     */
    suspend fun getDocOrNull(
        url: String,
        referer: String = "",
        customHeaders: Map<String, String> = emptyMap()
    ): Document? {

        val response = app.get(
            url,
            headers = headers(referer, customHeaders),
            allowRedirects = true
        )

        // Cloudflare often returns 403, 429, or 503
        if (response.code in listOf(403, 429, 503)) return null

        val doc = response.document
        if (isCloudflare(doc)) return null
        return doc
    }

    /**
     * Fetches a URL and throws ErrorLoadingException if Cloudflare is detected.
     * Use in load() and loadLinks() to trigger WebView flow.
     * 
     * @param url The URL to fetch
     * @param referer Optional referer header
     * @param errorMessage Custom error message for the exception
     * @throws ErrorLoadingException if Cloudflare is detected
     */
    suspend fun getDocOrThrow(
        url: String,
        referer: String = "",
        errorMessage: String = "Cloudflare protection – please open in WebView"
    ): Document {
        return getDocOrNull(url, referer)
            ?: throw ErrorLoadingException(errorMessage)
    }
}
