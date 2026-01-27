package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class AnimeBlkomProvider : MainAPI() {

    override var name = "AnimeBlkom"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val usesWebView = false 

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val domains = listOf(
        "https://blkom.com",
        "https://animeblkom.net",
        "https://animeblkom.com"
    )
    override var mainUrl = domains.first()

    private val cfKiller = CloudflareKiller()
    
    // Professional Standard: Use Desktop Chrome UA to bypass aggressive mobile-targeted Turnstile challenges
    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Centralized request helper with Industry Standard bypass pattern */
    private suspend fun getDoc(url: String): Document {
        // Step 1: Attempt direct request with Desktop UA
        var response = app.get(url, headers = mapOf("User-Agent" to desktopUA), timeout = 15L)
        var doc = response.document
        
        // Step 2: Detect if blocked by Cloudflare (Just a moment / Turnstile)
        if (doc.select("title").text() == "Just a moment..." || doc.html().contains("cf-turnstile")) {
            // Step 3: Trigger native CloudStream interceptor with the SAME headers
            response = app.get(
                url, 
                interceptor = cfKiller, 
                headers = mapOf("User-Agent" to desktopUA),
                timeout = 120L 
            )
            doc = response.document
        }
        
        return doc
    }

    // ================= MAIN PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()
        val doc = getDoc(mainUrl)

        // Recently Added Episodes
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
        
        // Episodes
        val episodes = doc.select(".episodes .episode a").mapNotNull {
            val epNum = it.text().filter { c -> c.isDigit() }.toIntOrNull()
            val link = it.absUrl("href")
            newEpisode(link) {
                 this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
