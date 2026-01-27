package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class AnimeBlkomProvider : MainAPI() {

    override var name = "AnimeBlkom"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val usesWebView = false // Disable built-in webview to avoid black screen hang

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val domains = listOf(
        "https://animeblkom.com",
        "https://animeblkom.tv",
        "https://animeblkom.net"
    )
    override var mainUrl = domains.first()

    /** Helper to perform requests through CloudflareHelper */
    private suspend fun getDoc(url: String): org.jsoup.nodes.Document {
        // This will automatically throw ErrorLoadingException("Please open in WebView...") if blocked
        return CloudflareHelper.getDocOrThrow(
            url, 
            referer = mainUrl,
            errorMessage = "Please open in WebView to solve Cloudflare"
        )
    }

    // ================= MAIN PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()
        val doc = getDoc(mainUrl)

        // 1. Recently Added Episodes
        val recentList = doc.select(".content .item").mapNotNull { it.toSearch() }
        if (recentList.isNotEmpty()) {
            homeList.add(HomePageList("Recently Added", recentList))
        }

        return newHomePageResponse(homeList, hasNext = false)
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?search=$query"
        val doc = getDoc(url)
        return doc.select(".content .item").mapNotNull { it.toSearch() }
    }

    // ================= LOAD =================
    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)

        val title = doc.selectFirst("h1.name")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".poster img")?.absUrl("src")
        val description = doc.selectFirst(".story")?.text()?.trim()
        val tags = doc.select(".genre a").map { it.text() }
        val statusText = doc.selectFirst(".info .status")?.text()
        val status = when {
            statusText?.contains("مستمر") == true -> ShowStatus.Ongoing
            statusText?.contains("منتهي") == true -> ShowStatus.Completed
            else -> null
        }
        val type = TvType.Anime

        // Episodes
        val episodes = doc.select(".episodes .episode a").mapNotNull {
            val epNum = it.text().filter { c -> c.isDigit() }.toIntOrNull()
            val link = it.absUrl("href")
            newEpisode(link) {
                 this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ================= LOAD LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val doc = getDoc(data)

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
