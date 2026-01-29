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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document
        val list = doc.select("div.postDiv").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleDiv = this.selectFirst("div.postInner > div.h1")
        val title = titleDiv?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Improve image extraction: check typical lazy load attributes and other potential classes
        val img = this.selectFirst("div.imgdiv-class img") 
            ?: this.selectFirst("div.postInner img") 
            ?: this.selectFirst("img") // Fallback to any img in the div
        
        var posterUrl = img?.let { 
             it.attr("data-src").ifEmpty { 
                 it.attr("data-original").ifEmpty {
                      it.attr("data-image").ifEmpty {
                          it.attr("src") 
                      }
                 }
             }
        }
        
        // Ensure protocol
        if (posterUrl != null && posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val quality = this.selectFirst("span.quality")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.postDiv").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // Basic Info
        val title = doc.selectFirst("div.title")?.text() ?: doc.selectFirst("title")?.text() ?: ""
        val poster = doc.selectFirst("div.posterImg img")?.attr("src")
        val desc = doc.selectFirst("div.singleDesc p")?.text()

        val tags = doc.select("div#singleList .col-xl-6").map { it.text() }
        val year = tags.find { it.contains("سنة الإنتاج") }?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = tags.find { it.contains("مدة") }?.substringAfter(":")?.trim()
        // val rating = doc.selectFirst("span.pImdb")?.text()?.substringAfter(" ")?.toRatingInt()
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        // Determine type: Series have episodes/seasons
        val isSeries = doc.select("div.epAll").isNotEmpty() || doc.select("div.seasonLoop").isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                // this.rating = rating
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            
            // Current page episodes
            doc.select("div#epAll a").forEach { ep ->
                val epTitle = ep.text()
                val epUrl = ep.attr("href")
                // Try to extract number from "الحلقة 1" -> 1
                val epNumber = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()
                
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNumber
                })
            }
            // Check for seasons and fetch if needed (simplification: only current page for now)
            // If the provider supports multi-season fetching, we would iterate "div.seasonLoop a" here.

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                // this.rating = rating
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

        doc.select("ul.tabs-ul li").forEach { li ->
            val onclick = li.attr("onclick")
            // onclick="player_iframe.location.href = '...'"
            // Use regex to be safer about spaces and quotes
            val playerUrl = Regex("""href\s*=\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)

            if (!playerUrl.isNullOrEmpty()) {
                if (playerUrl.contains("video_player")) {
                   // Internal player
                   FaselPlayer().getUrl(playerUrl, data).forEach(callback)
                } else {
                   // External or other
                   loadExtractor(playerUrl, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    class FaselPlayer : ExtractorApi() {
        override val name = "FaselHD"
        override val mainUrl = "https://web1296x.faselhdx.bid"
        override val requiresReferer = true

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
            val pageReferer = referer
            val doc = app.get(url, referer = pageReferer).document
            val links = mutableListOf<ExtractorLink>()
            
            // 1. Try to find sources using simple regex (if obfuscation is weak or contains clear text)
            // Look for .mp4 or .m3u8 urls in the whole html
            val html = doc.html()
            
            val validExtensions = listOf(".mp4", ".m3u8")
            // Simple regex to find URLs ending with extension
            // This is a "hail mary" regex
            val urlRegex = Regex("""https?://[^\s"']+\.(mp4|m3u8)""")
            
            urlRegex.findAll(html).forEach { match ->
                 val link = match.value
                 val isM3u8 = link.contains(".m3u8")
                 links.add(
                     newExtractorLink(
                         name,
                         "$name ${if(isM3u8) "HLS" else "MP4"}",
                         link,
                         if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                     ) {
                         this.referer = pageReferer ?: mainUrl
                         this.quality = Qualities.Unknown.value
                     }
                 )
            }
            
            // 2. Try to find "file": "..." pattern common in JWPlayer
            // Broaden the search to catch variations like "file":"..." or "file" : "..."
            // and don't strictly require "hlshtml" if the file pattern is clear
            
            // Priority 1: Specific FaselHD pattern (Greedy match until specific suffix)
            // Matches: "file":"HTTP_LINK","hlshtml"
            val greedyRegex = Regex(""""file"\s*:\s*"(.+?)"\s*,\s*"hlshtml"""")
            greedyRegex.find(html)?.groupValues?.get(1)?.replace("\\", "")?.let { link ->
                val isM3u8 = link.contains(".m3u8")
                 links.add(
                     newExtractorLink(
                         name,
                         "$name HLS",
                         link,
                         if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                     ) {
                         this.referer = pageReferer ?: mainUrl
                         this.quality = Qualities.Unknown.value
                     }
                 )
            }

            // Priority 2: Generic JWPlayer file pattern
            Regex("""file["']?\s*:\s*["']([^"']+)["']""").findAll(html).forEach { match ->
                val link = match.groupValues[1].replace("\\", "")
                if (link.startsWith("http") && links.none { it.url == link }) {
                     val isM3u8 = link.contains(".m3u8")
                     links.add(
                     newExtractorLink(
                         name,
                         "$name JW",
                         link,
                         if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                     ) {
                         this.referer = pageReferer ?: mainUrl
                         this.quality = Qualities.Unknown.value
                     }
                     )
                }
            }

            // Priority 3: Look for any http/https link ending in .m3u8 inside quotes
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html).forEach { match ->
                 val link = match.groupValues[1].replace("\\", "")
                 if (links.none { it.url == link }) {
                     links.add(
                     newExtractorLink(
                         name,
                         "$name Raw",
                         link,
                         ExtractorLinkType.M3U8
                     ) {
                         this.referer = pageReferer ?: mainUrl
                         this.quality = Qualities.Unknown.value
                     }
                     )
                 }
            }

            return links.distinctBy { it.url }
        }
    }
}
