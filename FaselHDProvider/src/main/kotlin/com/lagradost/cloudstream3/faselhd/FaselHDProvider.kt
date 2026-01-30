package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element


class FaselHDProvider : MainAPI() {
    override var mainUrl = "https://web12918x.faselhdx.bid"
    override var name = "FaselHD"
    override val usesWebView = true
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

        // Extract quality buttons from main page first (overlaying the player)
        val mainPageQualityButtons = doc.select("button.hd_btn")
        if (mainPageQualityButtons.isNotEmpty()) {
            mainPageQualityButtons.forEach { button ->
                val videoUrl = button.attr("data-url")
                val qualityText = button.text()

                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "$name - $qualityText",
                            videoUrl,
                            if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = getQualityInt(qualityText)
                        }
                    )
                }
            }
        }

        // Fallback: Extract the player iframe URL if no links found or as additional attempt
        val playerIframe = doc.selectFirst("iframe[name=\"player_iframe\"], iframe[src*=\"video_player\"]")
        val playerUrl = playerIframe?.absUrl("src")

        if (!playerUrl.isNullOrEmpty()) {
            try {
                // Fetch the player page
                val playerDoc = app.get(playerUrl, referer = data).document
                
                // Extract M3U8 links from quality buttons inside iframe
                val qualityButtons = playerDoc.select("button.hd_btn")
                qualityButtons.forEach { button ->
                    val videoUrl = button.attr("data-url")
                    val qualityText = button.text()
                    
                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "$name - $qualityText (Iframe)",
                                videoUrl,
                                if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = playerUrl
                                this.quality = getQualityInt(qualityText)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Regex Fallback for Iframe (extract from document.write scripts and JWPlayer config)
        val playerIframeFallback = doc.selectFirst("iframe[name=\"player_iframe\"], iframe[src*=\"video_player\"]")
        val playerUrlFallback = playerIframeFallback?.absUrl("src")
        
        if (!playerUrlFallback.isNullOrEmpty()) {
             try {
                val playerResponse = app.get(playerUrlFallback, referer = data).text
                
                // Pattern 1: Extract URLs from data-url attributes in document.write button scripts
                // Example: document.write('<button class="hd_btn" data-url="https://...">1080p</button>')
                val dataUrlPattern = Regex("""data-url=["']([^"']+)["']""")
                val dataUrlMatches = dataUrlPattern.findAll(playerResponse)
                
                // Pattern 2: Extract quality labels from button text
                // Example: >1080p< or >720p<
                val qualityPattern = Regex(""">(\d+p?)<""")
                
                // Pattern 3: JWPlayer config pattern (existing)
                val filePattern = Regex("""file\s*:\s*["']([^"']+)["']""")
                val fileMatches = filePattern.findAll(playerResponse)
                
                // Combine all matches
                val allMatches = mutableListOf<Pair<String, String?>>() // Pair<URL, Quality?>
                
                // Extract from data-url attributes
                dataUrlMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.startsWith("http")) {
                        // Try to find quality label near this URL
                        val contextStart = maxOf(0, match.range.first - 100)
                        val contextEnd = minOf(playerResponse.length, match.range.last + 100)
                        val context = playerResponse.substring(contextStart, contextEnd)
                        val qualityMatch = qualityPattern.find(context)
                        val quality = qualityMatch?.groupValues?.get(1)
                        allMatches.add(videoUrl to quality)
                    }
                }
                
                // Extract from file: pattern
                fileMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.startsWith("http")) {
                        allMatches.add(videoUrl to null)
                    }
                }
                
                // Deduplicate and create extractor links
                allMatches.distinctBy { it.first }.forEach { (videoUrl, qualityLabel) ->
                    val quality = if (qualityLabel != null) {
                        getQualityInt(qualityLabel)
                    } else {
                        Qualities.Unknown.value
                    }
                    
                    val linkName = if (qualityLabel != null) {
                        "$name - $qualityLabel"
                    } else {
                        "$name - Auto"
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            linkName,
                            videoUrl,
                            if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = playerUrlFallback
                            this.quality = quality
                        }
                    )
                }
             } catch (e: Exception) {
                 e.printStackTrace()
             }
        }


        // Also extract download links from main page (.downloadLinks container)
        doc.select(".downloadLinks a, a[href*=\"t7meel\"]").forEach { link ->
            val dlUrl = link.absUrl("href")
            val linkText = link.text()
            if (dlUrl.startsWith("http")) {
                 callback.invoke(
                    newExtractorLink(
                        this.name,
                        "$name - Download ($linkText)",
                        dlUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            quality.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
