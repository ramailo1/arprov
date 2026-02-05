package com.lagradost.cloudstream3.cimaclub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay

class CimaClubProvider : MainAPI() {
    override var mainUrl = "https://ciimaclub.us"
    override var name = "CimaClub"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Anti-bot configuration
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun getRandomUserAgent(): String = userAgents.random()
    
    private fun getRandomDelay(): Long = (500..1500).random().toLong()

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
        "$mainUrl/anime/" to "أحدث الانمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent()), timeout = 60).document
        
        // Add random delay to avoid detection
        delay(getRandomDelay())
        
        val home = doc.select(".Small--Box").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = this.selectFirst(".Box-Title, .Title, h2")?.text()?.trim() ?: linkElement.attr("title").trim()
        val href = fixUrl(linkElement.attr("href"))
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src") ?: "")
        
        // CimaClub specific: Sometimes they put the year in a span or div
        val year = this.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query.replace(" ", "+")}"
        
        delay(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent()), timeout = 60).document
        
        return doc.select(".Small--Box").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        delay(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent()), timeout = 60).document
        
        val title = doc.selectFirst("h1, .PostTitle")?.text()?.trim() ?: return null
        val poster = fixUrl(doc.selectFirst(".image img, .Poster img, .Post--Poster img")?.attr("src") ?: "")
        val year = doc.selectFirst("a[href*='release-year']")?.text()?.trim()?.toIntOrNull()
        
        // Description: Look for 'قصة العرض' or similar
        val description = doc.select("div:contains(قصة العرض) + div, div:contains(قصة العرض) + p, .Story, .StoryArea").firstOrNull()?.text()?.trim()
            ?: doc.select("div:contains(قصة العرض)").text().replace("قصة العرض", "").trim()
        
        val tags = doc.select(".Genres a, .Post-Genres a").map { it.text().trim() }
        
        // Check if it's a series or has episodes
        // CimaClub puts episodes in .BlocksHolder usually
        val episodeElements = doc.select(".Episodes-List .Small--Box, .BlocksHolder .Small--Box")
        // Check if it is series by category or url
        val isSeries = episodeElements.isNotEmpty() || url.contains("/series/") || doc.select(".Episodes-List, .BlocksHolder").isNotEmpty()
        
        if (isSeries) {
            val episodes = ArrayList<Episode>()
            for (element in episodeElements) {
                val link = element.selectFirst("a")
                val episodeUrl = fixUrl(link?.attr("href") ?: continue)
                val episodeTitle = element.selectFirst(".Box-Title")?.text() ?: link?.attr("title") ?: "Episode"
                
                // Try to extract episode number
                // Format often: "مسلسل Name الحلقة 3"
                val episodeNumber = Regex("(\\d+)").findAll(episodeTitle).lastOrNull()?.value?.toIntOrNull()
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                })
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            // It's a movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        delay(getRandomDelay())
        
        // CimaClub: To get links we usually need to go to the Watch page.
        // If the URL ends with /watch/ it's good, otherwise we try to append it or find the button.
        
        var watchUrl = data
        if (!data.endsWith("/watch/")) {
            // Try fetching the page to find the watch button if simply appending doesn't work?
            // Usually appending /watch/ works for CimaClub
             if (!data.endsWith("/")) watchUrl += "/"
             watchUrl += "watch/"
        }

        val doc = try {
            app.get(watchUrl, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        } catch (e: Exception) {
            // Fallback: maybe the original URL was the watch page or we need to find the link
             app.get(data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        }
        
        // 1. Look for iframes
        val iframes = doc.select("iframe")
        for (iframe in iframes) {
             val src = iframe.attr("src")
             if (src.isNotEmpty()) {
                 loadExtractor(src, mainUrl, subtitleCallback, callback)
             }
        }
        
        // 2. Look for server lists (li elements usually)
        // Structure: <ul class="Servers-List"> <li data-server="id">...</li> </ul>
        // Or direct links
        val serverLinks = doc.select(".Servers-List li, .servers-list li")
        // Note: Often CimaClub uses AJAX to load servers or they are just buttons changing the iframe.
        // Ideally we inspect the 'data-url' or 'data-embed' attributes if they exist.
        
        for (server in serverLinks) {
            val embedUrl = server.attr("data-embed") ?: server.attr("data-url")
            if (embedUrl.isNotEmpty()) {
                 loadExtractor(fixUrl(embedUrl), mainUrl, subtitleCallback, callback)
            }
        }
        
        // 3. Fallback: Check for 'a' tags in server lists if `li` doesn't have data
        val anchorServers = doc.select(".Servers-List a, .servers-list a")
        for (a in anchorServers) {
             val href = a.attr("href")
             if (href.contains("watch")) {
                  // This might be a link to another watch page, avoid infinite recursion
             } else {
                 loadExtractor(fixUrl(href), mainUrl, subtitleCallback, callback)
             }
        }

        // 4. Download Links
        val downloadLinks = doc.select(".Download-Links a, .download-servers a")
        for (link in downloadLinks) {
            val href = fixUrl(link.attr("href"))
            val qualityText = link.text() // extract quality
             callback.invoke(
                newExtractorLink(
                    "CimaClub Download",
                    "CimaClub Download",
                    href,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQualityFromString(qualityText)
                }
            )
        }

        return true
    }

    private fun getQualityFromString(quality: String): Int {
        return when {
            quality.contains("1080") -> 1080
            quality.contains("720") -> 720
            quality.contains("480") -> 480
            quality.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }
}