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
        "$mainUrl/movies/page/%d/" to "أحدث الأفلام",
        "$mainUrl/episodes/page/%d/" to "أحدث الحلقات",
        "$mainUrl/category/افلام-انمي/page/%d/" to "أفلام الانمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        // Use only .GridItem to avoid duplicates (.Thumb--GridItem is nested inside .GridItem)
        val home = doc.select(".GridItem").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = link.attr("title")
            .ifEmpty { selectFirst(".title, strong")?.text() }
            ?.trim() ?: return null

        // Extract poster and strip quotes that WebView may inject
        val poster = selectFirst(".BG--GridItem, .GridItem--BG")
            ?.attr("style")
            ?.substringAfter("url(")
            ?.substringBefore(")")
            ?.replace("\"", "")
            ?.replace("'", "")
            ?.trim()
            ?.let { fixUrl(it) }

        val year = selectFirst(".year")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        // Use only .GridItem to avoid duplicates
        return doc.select(".GridItem").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document

        // Use h1 with itemprop or fallback to any h1
        val title = doc.selectFirst("h1[itemprop='name'], h1")?.text()?.trim() ?: return null

        // Use og:image meta tag as primary source (most reliable)
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".Img--Poster--Single-begin")
                ?.attr("style")
                ?.substringAfter("url(")
                ?.substringBefore(")")
                ?.replace("\"", "")
                ?.replace("'", "")
                ?.let { fixUrl(it) }

        val year = doc.selectFirst("a[href*='release-year']")?.text()?.toIntOrNull()

        val plot = doc.selectFirst("li:contains(قصة)")?.text()?.replace("قصة العرض", "")?.trim()

        val tags = doc.select("a[href*='/genre/']").map { it.text() }

        val isSeries = doc.select(".EpisodesList, .episodes-list, .SeasonEpisodes").isNotEmpty()

        val ratingText = doc.selectFirst("li:contains(IMDb)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
        
        // Check for series
        // val isSeries = doc.select(".EpisodesList, .Episodes, .SeasonEpisodes, .episodes-list").isNotEmpty() || 
        //                title.contains("مسلسل") || title.contains("انمي")

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
                this.plot = plot
                // this.rating = rating 
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
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