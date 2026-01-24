package com.lagradost.cloudstream3.ristoanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class RistoAnimeProvider : MainAPI() {
    override var mainUrl = "https://ristoanime.org"
    override var name = "RistoAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

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
    private fun getRandomDelay(): Long = (1500..3500).random().toLong()

    override val mainPage = mainPageOf(
        "$mainUrl/anime" to "أحدث الانمي",
        "$mainUrl/anime?type=tv" to "مسلسلات انمي",
        "$mainUrl/anime?type=movie" to "أفلام انمي",
        "$mainUrl/anime?type=ova" to "OVA",
        "$mainUrl/anime?status=ongoing" to "الانمي المستمر",
        "$mainUrl/anime?status=completed" to "الانمي المكتمل"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + if (request.data.contains("?")) "&page=$page" else "?page=$page", 
            headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        Thread.sleep(getRandomDelay())
        
        val home = doc.select(".anime-item, .video-item, .film-item, .item, article").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst(".title, .name, h2, h3, .anime-title, .entry-title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: "")
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src") ?: "")
        val year = this.selectFirst(".year, .date")?.text()?.trim()?.toIntOrNull()
        val episodes = this.selectFirst(".episodes, .episode-count")?.text()?.replace("\\D".toRegex(), "")?.toIntOrNull()
        
        // Determine type from URL or content
        val type = when {
            href.contains("/movie/") || this.text().contains("فيلم") -> TvType.AnimeMovie
            href.contains("/ova/") || this.text().contains("OVA") -> TvType.OVA
            else -> TvType.Anime
        }
        
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            addDubStatus(dubExist = false, subEpisodes = episodes)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        Thread.sleep(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        return doc.select(".search-results .anime-item, .results .video-item, .search-item, article").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Thread.sleep(getRandomDelay())
        val doc = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        val title = doc.selectFirst(".anime-title, .video-title, h1, .entry-title")?.text()?.trim() ?: return null
        val poster = fixUrl(doc.selectFirst(".poster img, .anime-poster img, .thumbnail img")?.attr("src") ?: "")
        val year = doc.selectFirst(".year, .date, .release-date")?.text()?.trim()?.toIntOrNull()
        val description = doc.selectFirst(".description, .plot, .summary, .story, .content")?.text()?.trim()
        val rating = doc.selectFirst(".rating, .score")?.text()?.trim()?.toRatingInt()
        val tags = doc.select(".genres a, .categories a, .tags a, .genre-tags a").map { it.text().trim() }
        
        // Determine if it's a series or movie
        val isSeries = doc.select(".episodes, .episode-list, .eps-list").isNotEmpty() || 
                      !url.contains("/movie/")
        
        val type = when {
            url.contains("/movie/") || doc.text().contains("فيلم") -> TvType.AnimeMovie
            url.contains("/ova/") || doc.text().contains("OVA") -> TvType.OVA
            else -> TvType.Anime
        }
        
        return if (isSeries && type == TvType.Anime) {
            val episodes = mutableListOf<Episode>()
            doc.select(".episode-item, .episodes li, .eps-item, article.episode").forEach { episodeElement ->
                val episodeName = episodeElement.selectFirst(".episode-title, .episode-name, .title")?.text()?.trim()
                val episodeUrl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
                val episodeNumber = episodeElement.selectFirst(".episode-number, .ep-num")?.text()?.replace("\\D".toRegex(), "")?.toIntOrNull()
                    ?: episodeName?.replace("\\D".toRegex(), "")?.toIntOrNull()
                
                if (episodeName != null && episodeUrl != null) {
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeName
                        this.episode = episodeNumber
                    })
                }
            }
            
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Thread.sleep(getRandomDelay() + 1000)
        val doc = app.get(data, headers = headers + mapOf("User-Agent" to getRandomUserAgent())).document
        
        // Try multiple selectors for video links
        doc.select(".video-links a, .download-links a, .watch-links a, .server-list a, .quality-list a").forEach { linkElement ->
            val linkUrl = fixUrl(linkElement.attr("href") ?: "") ?: return@forEach
            val quality = linkElement.select(".quality, .resolution, .res").text().trim()
            val serverName = linkElement.select(".server-name, .host, .server").text().trim()
            
            callback.invoke(
                newExtractorLink(
                    source = serverName.ifEmpty { "RistoAnime" },
                    name = serverName.ifEmpty { "RistoAnime" } + if (quality.isNotEmpty()) " - $quality" else "",
                    url = linkUrl,
                    referer = mainUrl,
                    quality = getQualityFromString(quality),
                    type = if (linkUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        
        // Also try to load external extractors
        doc.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }
        
        return true
    }

    private fun getQualityFromString(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            quality.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
