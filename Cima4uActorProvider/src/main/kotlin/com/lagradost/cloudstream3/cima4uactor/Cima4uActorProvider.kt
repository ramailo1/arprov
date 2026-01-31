package com.lagradost.cloudstream3.cima4uactor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class Cima4uActorProvider : MainAPI() {
    override var mainUrl = "https://cima4u.forum"
    override var name = "Cima4uActor"
    override val hasMainPage = true
    override val usesWebView = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Anti-bot configuration
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
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

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/episodes/" to "أحدث الحلقات",
        "$mainUrl/category/افلام-انمي/" to "أفلام الانمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        val home = doc.select(".Thumb--GridItem").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = linkElement.attr("title").ifEmpty { this.selectFirst("strong")?.text()?.trim() } ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Extract poster from CSS variable --image in style attribute
        val posterStyle = this.selectFirst(".BG--GridItem")?.attr("style") ?: ""
        val posterUrl = if (posterStyle.contains("--image:")) {
            posterStyle.substringAfter("--image: url(").substringBefore(")")
        } else {
            this.selectFirst("img")?.attr("src") ?: ""
        }

        val year = this.selectFirst(".year")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        return doc.select(".Thumb--GridItem").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        val title = doc.selectFirst(".PostTitle h1, .PostTitle span")?.text()?.trim() ?: return null
        val poster = fixUrl(doc.selectFirst(".Img--Poster--Single-begin img")?.attr("src") ?: "")
        val year = doc.selectFirst("a[href*='/release-year/']")?.text()?.trim()?.toIntOrNull()
        val description = doc.selectFirst(".PostDetail li:contains(قصة العرض)")?.text()?.replace("قصة العرض", "")?.trim() 
            ?: doc.selectFirst(".PostDetail")?.text()?.trim()

        val ratingText = doc.selectFirst("li:contains(IMDb)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
        
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }
        
        // Check for series
        val isSeries = doc.select(".EpisodesList, .Episodes, .SeasonEpisodes, .episodes-list").isNotEmpty() || 
                       title.contains("مسلسل") || title.contains("انمي")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select(".EpisodesList a, .episodes-list a").forEach { episodeElement ->
                val episodeName = episodeElement.text().trim()
                val episodeUrl = fixUrl(episodeElement.attr("href"))
                val episodeNumber = episodeElement.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeName
                    this.episode = episodeNumber
                })
            }
            // If no episodes found in list, might be a single season view or different layout
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                // this.rating = rating 
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                // this.rating = rating
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        // Watch servers
        doc.select("ul#watch li[data-watch]").forEach { linkElement ->
            val linkUrl = linkElement.attr("data-watch")
            if (linkUrl.isBlank()) return@forEach
            
            val serverName = linkElement.text().trim()
            
            callback.invoke(
                newExtractorLink(
                    serverName.ifEmpty { "Cima4uActor" },
                    serverName.ifEmpty { "Cima4uActor" },
                    linkUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }

        // Download servers
        doc.select(".DownloadServers a").forEach { linkElement ->
            val linkUrl = linkElement.attr("href")
            if (linkUrl.isBlank()) return@forEach
            
            val serverName = linkElement.select("quality").text().trim()
            
            callback.invoke(
                newExtractorLink(
                    "DL $serverName",
                    "DL $serverName",
                    linkUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }
        
        return true
    }
}