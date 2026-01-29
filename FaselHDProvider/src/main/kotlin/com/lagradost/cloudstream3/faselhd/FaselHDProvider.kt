package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FaselHDProvider : MainAPI() {
    override var mainUrl = "https://web1296x.faselhdx.bid"
    override var name = "FaselHD"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/most_recent" to "Recently Added",
        "$mainUrl/series" to "Series",
        "$mainUrl/movies" to "Movies",
        "$mainUrl/asian-series" to "Asian Series",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/tvshows" to "TV Shows",
        "$mainUrl/dubbed-movies" to "Dubbed Movies",
        "$mainUrl/hindi" to "Hindi",
        "$mainUrl/asian-movies" to "Asian Movies",
        "$mainUrl/anime-movies" to "Anime Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url).document
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val img = selectFirst("div.imgdiv-class img")
            ?: selectFirst("div.postInner img")
            ?: selectFirst("img")

        var posterUrl = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("data-image").ifEmpty {
                        it.attr("data-srcset").ifEmpty { it.attr("src") }
                    }
                }
            }
        }

        if (!posterUrl.isNullOrEmpty() && posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val quality = selectFirst("span.quality")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.title")?.text() ?: doc.selectFirst("title")?.text() ?: ""
        val poster = doc.selectFirst("div.posterImg img")?.attr("src")
        val desc = doc.selectFirst("div.singleDesc p")?.text()

        val tags = doc.select("div#singleList .col-xl-6").map { it.text() }
        val year = tags.find { it.contains("سنة الإنتاج") }?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = tags.find { it.contains("مدة") }?.substringAfter(":")?.trim()
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        val isSeries = doc.select("div.epAll").isNotEmpty() || doc.select("div.seasonLoop").isNotEmpty()

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("div#epAll a").forEach { ep ->
                val epTitle = ep.text()
                val epUrl = ep.attr("href")
                val epNumber = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNumber
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Extract player iframe URLs from onclick attributes
        doc.select("li[onclick*='player_iframe']").forEach { li ->
            val onclick = li.attr("onclick")
            // Match both ' and &#39; (HTML entity for apostrophe)
            val playerUrlMatch = Regex("""player_iframe\.location\.href\s*=\s*['"&#39;]+([^'"&#39;]+)['"&#39;]+""").find(onclick)
            
            if (playerUrlMatch != null) {
                val playerUrl = playerUrlMatch.groupValues[1]
                    .replace("&amp;", "&")  // Decode HTML entities
                    .replace("&#39;", "'")
                
                extractVideoFromPlayer(playerUrl, data, callback)
            }
        }
        
        // Direct download links
        doc.select("div.downloadLinks a").forEach { a ->
            val url = a.absUrl("href")
            if (url.isNotBlank()) {
                callback(newExtractorLink(name, "Download", url, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    quality = Qualities.Unknown.value
                })
            }
        }

        return true
    }

    private suspend fun extractVideoFromPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerHtml = app.get(
            playerUrl,
            referer = referer,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).text

        // Extract m3u8 URLs from data-url attributes in buttons
        val dataUrlRegex = Regex("""data-url=["']([^"']+\.m3u8)["']""")
        val qualityRegex = Regex(""">(\d+p|auto)</button>""")
        
        dataUrlRegex.findAll(playerHtml).forEach { match ->
            val m3u8Url = match.groupValues[1]
            
            // Try to find quality label near this URL
            val contextStart = maxOf(0, match.range.first - 200)
            val contextEnd = minOf(playerHtml.length, match.range.last + 200)
            val context = playerHtml.substring(contextStart, contextEnd)
            
            val qualityMatch = qualityRegex.find(context)
            val qualityLabel = qualityMatch?.groupValues?.get(1) ?: "Unknown"
            
            callback(newExtractorLink(
                name,
                "$name - $qualityLabel",
                m3u8Url,
                ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                quality = when (qualityLabel) {
                    "1080p" -> Qualities.P1080.value
                    "720p" -> Qualities.P720.value
                    "480p" -> Qualities.P480.value
                    "360p" -> Qualities.P360.value
                    "auto" -> Qualities.Unknown.value
                    else -> Qualities.Unknown.value
                }
            })
        }
    }
}
