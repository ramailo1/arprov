package com.lagradost.cloudstream3.shahidmbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class ShahidMBCProvider : MainAPI() {
    override var mainUrl = "https://shahid.mbc.net"
    override var name = "ShahidMBC (Under Development)"
    override val hasMainPage = true
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
    private fun getRandomDelay(): Long = (2000..4000).random().toLong()

    // Data classes for JSON parsing (Simplified)
    data class ShahidMedia(
        val title: String,
        val url: String,
        val poster: String?,
        val year: Int?,
        val type: TvType
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ar/movies" to "أفلام شاهد",
        "$mainUrl/ar/series" to "مسلسلات شاهد",
        "$mainUrl/ar/anime" to "انمي شاهد",
        "$mainUrl/ar/sports" to "رياضة"
    )

    private fun extractNextData(html: String): String? {
        val pattern = """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""".toRegex()
        return pattern.find(html)?.groupValues?.get(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data, headers = headers + mapOf("User-Agent" to getRandomUserAgent()))
        val nextData = extractNextData(response.text) ?: return newHomePageResponse(request.name, emptyList())
        
        val items = mutableListOf<SearchResponse>()
        
        // Robust regex to capture title, product URL, and image path
        // Pattern: "title":"...", "productUrl":{"url":"..."}, "image":{"path":"..."}
        val itemPattern = """"title":"([^"]+)".*?"productUrl":\{"url":"([^"]+)".*?"image":\{"path":"([^"]+)"""".toRegex()
        itemPattern.findAll(nextData).take(40).forEach { match ->
            val title = match.groupValues[1]
            val url = match.groupValues[2]
            val poster = match.groupValues[3]
            
            // Map to movie or series based on URL pattern
            val type = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
            
            items.add(newMovieSearchResponse(title, fixUrl(url), type) {
                this.posterUrl = fixUrl(poster)
            })
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ar/search?term=${query.replace(" ", "+")}"
        val response = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent()))
        val nextData = extractNextData(response.text) ?: return emptyList()
        
        val items = mutableListOf<SearchResponse>()
        // Simplified search pattern
        val resultPattern = """"title":"([^"]+)".*?"productUrl":\{"url":"([^"]+)"""".toRegex()
        resultPattern.findAll(nextData).forEach { match ->
            val title = match.groupValues[1]
            val path = match.groupValues[2]
            
            items.add(newMovieSearchResponse(title, fixUrl(path), TvType.Movie))
        }
        
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = headers + mapOf("User-Agent" to getRandomUserAgent()))
        val nextData = extractNextData(response.text) ?: return null
        
        val title = Regex(""""title":"([^"]+)"""").find(nextData)?.groupValues?.get(1) ?: return null
        val poster = Regex(""""image":\{"path":"([^"]+)"""").find(nextData)?.groupValues?.get(1)
        val plot = Regex(""""description":"([^"]+)"""").find(nextData)?.groupValues?.get(1)
        val year = Regex(""""releaseDate":(\d{4})""").find(nextData)?.groupValues?.get(1)?.toIntOrNull()
        
        val isSeries = url.contains("/series/") || nextData.contains("\"seasons\":")
        
        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // Extract episodes from JSON
            val epPattern = """"episodeNumber":(\d+).*?"title":"([^"]+)".*?"productUrl":\{"url":"([^"]+)"""".toRegex()
            epPattern.findAll(nextData).forEach { match ->
                val epNum = match.groupValues[1].toIntOrNull()
                val epTitle = match.groupValues[2]
                val epUrl = match.groupValues[3]
                
                episodes.add(newEpisode(fixUrl(epUrl)) {
                    this.name = epTitle
                    this.episode = epNum
                })
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.year = year
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data, headers = headers + mapOf("User-Agent" to getRandomUserAgent()))
        val nextData = extractNextData(response.text) ?: return false
        
        // Shahid uses dynamic stream tokens. This is the hardest part.
        // We look for any obvious manifest URLs or stream IDs in the JSON.
        val manifestUrl = Regex("""(https?://[^"]+\.m3u8[^"]*)""").find(nextData)?.groupValues?.get(1)
        
        if (manifestUrl != null) {
            callback.invoke(
                newExtractorLink(
                    "Shahid",
                    "Shahid",
                    fixUrl(manifestUrl),
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = mainUrl
                }
            )
            return true
        }
        
        return false
    }
}
