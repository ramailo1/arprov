package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeBlkomProvider : MainAPI() {

    override var name = "AnimeBlkom"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val usesWebView = true  // Critical: forces WebView for Turnstile

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val domains = listOf(
        "https://animeblkom.tv",
        "https://animeblkom.com",
        "https://animeblkom.net"
    )
    override var mainUrl = domains.first()

    // ================= MAIN PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Turnstile cannot be bypassed programmatically
        throw ErrorLoadingException("Please open in WebView to solve Cloudflare")
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        throw ErrorLoadingException("Please open in WebView to solve Cloudflare")
    }

    // ================= LOAD =================
    override suspend fun load(url: String): LoadResponse {
        throw ErrorLoadingException("Please open in WebView to solve Cloudflare")
    }

    // ================= LOAD LINKS =================
    // This runs after the user opens WebView and CloudStream has cookies
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try to fetch the page using WebView session cookies
        val doc = try {
            CloudflareHelper.getDocOrThrow(data)
        } catch (_: Exception) {
            // If cookies don't work, force WebView again
            throw ErrorLoadingException("Unable to load links; open in WebView first")
        }

        // Direct downloads
        doc.select("#download a.btn").forEach {
            val link = it.absUrl("href")
            val quality = it.text().filter(Char::isDigit).toIntOrNull() ?: Qualities.Unknown.value
            if (link.isNotBlank()) {
                callback(
                    newExtractorLink(name, "Download ${quality}p", link, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                    }
                )
            }
        }

        // Streaming servers
        doc.select(".servers a[data-src]").forEach {
            val link = it.attr("data-src")
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ================= ELEMENT PARSER =================
    private fun Element.toSearch(): SearchResponse? {
        val title = selectFirst(".name")?.text() ?: return null
        val link = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.absUrl("data-original")?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.absUrl("src")
        return newAnimeSearchResponse(title, link, TvType.Anime) { 
            posterUrl = poster 
        }
    }
}
