package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

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
        // println("🔍 FaselHD loadLinks called for: $data")
        val doc = app.get(data).document
        
        // Extract player URLs from onclick attributes
        val playerElements = doc.select("li[onclick*=player_iframe]")
       
        playerElements.forEach { li ->
            val onclick = li.attr("onclick")
            val urlPattern = Regex("""(?:&#39;|['"])([^'"]+video_player[^'"]+)(?:&#39;|['"])""")
            val match = urlPattern.find(onclick)
            
            if (match != null) {
                val playerUrl = match.groupValues[1]
                    .replace("&#39;", "'")
                    .replace("&amp;", "&")
                
                try {
                    val playerDoc = app.get(playerUrl, referer = data).document
                    val playerHtml = playerDoc.html()
                    
                    val videoUrls = extractVideoUrl(playerHtml)
                    if (videoUrls.isNotEmpty()) {
                        videoUrls.forEach { videoUrl -> 
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "$name - Main",
                                    videoUrl,
                                    if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = playerUrl
                                }
                            )
                        }
                    } else {
                         // Fallback to searching for standard URLs if obfuscation fails
                         val standardPattern = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
                         standardPattern.findAll(playerHtml).forEach { m ->
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "$name - Direct",
                                    m.groupValues[1],
                                    ExtractorLinkType.M3U8
                                )
                            )
                         }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return true
    }

    private fun decodeBase64Custom(input: String): String {
        // Swap case: a-z <-> A-Z
        val swapped = input.map { char ->
            when {
                char in 'a'..'z' -> char.uppercaseChar()
                char in 'A'..'Z' -> char.lowercaseChar()
                else -> char
            }
        }.joinToString("")
        
        return try {
            String(Base64.getDecoder().decode(swapped))
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractVideoUrl(html: String): List<String> {
        return try {
            val arrayRegex = Regex("""var\s+_0x\w+=\[(.*?)\];""")
            val arrayMatch = arrayRegex.find(html) ?: return emptyList()
            
            val rawArray = arrayMatch.groupValues[1]
            val arrayItems = rawArray.split("','").map { it.replace("'", "") }
            
            if (arrayItems.isEmpty()) return emptyList()

            val decodedItems = arrayItems.mapNotNull { 
                val decoded = decodeBase64Custom(it)
                if (decoded.isNotEmpty()) decoded else null
            }

            val urls = mutableListOf<String>()
            
            // Look for URL parts and try to reconstruct or find whole URLs
            // Strategy: Gather all parts that look like URL components
            val httpPart = decodedItems.firstOrNull { it.startsWith("http") }
            val m3u8Part = decodedItems.firstOrNull { it.contains(".m3u8") }
            
            if (httpPart != null && m3u8Part != null) {
                // Determine if we need to join them or if one contains the other
                if (httpPart.contains(".m3u8")) {
                    urls.add(httpPart)
                } else {
                    // Try to find the middle parts
                    if (Regex("https?://").containsMatchIn(httpPart)) {
                         // It might be a base URL.
                    }
                }
            }
            
            // Better Strategy: Return any decoded string that is a valid URL
            decodedItems.forEach { item ->
                if (item.contains("http") && (item.contains(".m3u8") || item.contains(".mp4"))) {
                    urls.add(item)
                }
            }
            
            urls
        } catch (e: Exception) {
            emptyList()
        }
    }
}
