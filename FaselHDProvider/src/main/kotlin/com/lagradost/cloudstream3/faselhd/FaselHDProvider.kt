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
        println("🔍 FaselHD loadLinks called for: $data")
        val doc = app.get(data).document
        val pageHtml = doc.html()
        
        println("📄 Page HTML length: ${pageHtml.length}")
        println("📝 First 2000 chars of HTML: ${pageHtml.take(2000)}")
        
        // Extract player URLs from onclick attributes
        // The HTML contains: onclick="player_iframe.location.href = &#39;URL&#39;"
        val playerElements = doc.select("li[onclick*=player_iframe]")
        println("📋 Found ${playerElements.size} player elements with selector: li[onclick*=player_iframe]")
        
        // Try alternative selectors
        val allLiWithOnclick = doc.select("li[onclick]")
        println("📋 Found ${allLiWithOnclick.size} <li> elements with onclick")
        
        val allLi = doc.select("li")
        println("📋 Found ${allLi.size} total <li> elements")
        
        // Show first few li elements
        allLi.take(5).forEach { li ->
            println("  <li> sample: ${li.html().take(200)}")
        }
        
        playerElements.forEach { li ->
            val onclick = li.attr("onclick")
            println("🔗 onclick: ${onclick.take(150)}")
            
            // Extract URL from onclick, handling both regular quotes and HTML entities
            val urlPattern = Regex("""(?:&#39;|['"])([^'"]+video_player[^'"]+)(?:&#39;|['"])""")
            val match = urlPattern.find(onclick)
            
            if (match != null) {
                val playerUrl = match.groupValues[1]
                    .replace("&#39;", "'")
                    .replace("&amp;", "&")
                
                println("🎬 Player URL: ${playerUrl.take(100)}")
                
                // Load the player page and extract video links
                try {
                    val playerDoc = app.get(playerUrl, referer = data).document
                    val playerHtml = playerDoc.html()
                    println("📄 Player HTML length: ${playerHtml.length}")
                    
                    // Look for m3u8 and mp4 URLs directly in the HTML/JavaScript
                    // The player pages contain obfuscated JS with embedded video URLs
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
                    
                    val m3u8Matches = m3u8Regex.findAll(playerHtml)
                    val mp4Matches = mp4Regex.findAll(playerHtml)
                    
                    var foundAny = false
                    
                    m3u8Matches.forEach { match ->
                        val videoUrl = match.groupValues[1]
                        println("🎥 Found m3u8 URL: ${videoUrl.take(100)}")
                        foundAny = true
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "$name - M3U8",
                                videoUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    
                    mp4Matches.forEach { match ->
                        val videoUrl = match.groupValues[1]
                        println("🎥 Found mp4 URL: ${videoUrl.take(100)}")
                        foundAny = true
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "$name - MP4",
                                videoUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = playerUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    
                    if (!foundAny) {
                        println("⚠️ No video URLs found in player page")
                    }
                } catch (e: Exception) {
                    println("❌ Error loading player: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("❌ No match found in onclick")
            }
        }
        
        return true
    }
}
