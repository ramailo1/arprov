package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class AnimeBlkomProvider : MainAPI() {

    override var name = "AnimeBlkom (Blocked)"
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

    /**
     * Phase 7: Universal International Persona (Chrome 121 on Windows 10)
     * This UA matched with stripped X-Requested-With is the current 'Gold Standard' 
     * for bypassing Turnstile challenges that hang on Mobile WebViews.
     */
    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    /** 
     * Custom "Universal International" Bypass (Phase 7):
     * Uses the 'Double-Dip 2.0' strategy found in Hexated/SoraStream.
     * 1. Detect block.
     * 2. Force WebView solve with Desktop UA and NO X-Requested-With.
     * 3. Sync cookies and perform final data fetch.
     */
    private suspend fun getDoc(url: String): Document {
        // Step 1: Force standard request with desktop persona
        val headers = mapOf(
            "User-Agent" to desktopUA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\""
        )
        
        var response = app.get(url, headers = headers, timeout = 20L)
        var doc = response.document
        
        // Step 2: Detect Managed Challenge
        val isBlocked = response.code in listOf(403, 503) || 
                        doc.title().contains("Just a moment", ignoreCase = true) ||
                        doc.html().contains("cf-turnstile", ignoreCase = true)

        if (isBlocked) {
            // Step 3: Trigger Active Resolver
            // Using a specific Regex that matches the domain to ensure cookie sync
            val resolver = WebViewResolver(Regex("blkom\\.com|animeblkom\\.net"))
            
            // CRITICAL: We do NOT send X-Requested-With here to avoid app-detection
            resolver.resolveUsingWebView(
                requestCreator("GET", url, headers = headers)
            )

            // Step 4: Double-Dip final request now that session is verified
            response = app.get(url, headers = headers)
            doc = response.document
        }
        
        return doc
    }

    // ================= MAIN PAGE =================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()
        val doc = getDoc(mainUrl)

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
        for (it in doc.select("#download a.btn")) {
            val link = it.absUrl("href")
            val quality = it.text().filter(Char::isDigit).toIntOrNull() ?: Qualities.Unknown.value
            if (link.isNotBlank()) {
                callback(
                    newExtractorLink(name, "Download ${quality}p", link, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to desktopUA)
                    }
                )
            }
        }

        // Streaming servers
        for (it in doc.select(".servers a[data-src]")) {
            val link = it.attr("data-src")
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }

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
