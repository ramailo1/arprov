package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

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
                         // Fallback to searching for standard URLs
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
        // Obfuscation often swaps case: a-z <-> A-Z and sometimes 0-9
        // Based on analysis, the alphabet starts with lowercase 'a'...'z', then 'A'...'Z'
        // Standard Base64 is A...Z, a...z
        // So we swap case to map back to standard Base64.
        val swapped = input.map { char ->
            when {
                char in 'a'..'z' -> char.uppercaseChar()
                char in 'A'..'Z' -> char.lowercaseChar()
                else -> char
            }
        }.joinToString("")
        
        return try {
            String(Base64.decode(swapped, Base64.DEFAULT))
        } catch (e: Exception) {
            try {
                // Fallback for non-Android environments (tests) or if android.util.Base64 fails in pure JVM
                String(java.util.Base64.getDecoder().decode(swapped))
            } catch (e2: Exception) {
                "" 
            }
        }
    }

    private fun extractVideoUrl(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Pattern 1: Obfuscated Array var _0x... = [...]
        try {
            // Find the array
            val arrayRegex = Regex("""var\s+_0x\w+=\[(.*?)\];""")
            val arrayMatch = arrayRegex.find(html) ?: return emptyList()
            
            val rawArray = arrayMatch.groupValues[1]
            // Removing newlines and quotes to parse the list roughly
            val cleanedArray = rawArray.replace("\n", "").replace("\r", "")
            
            // Split by ',' but be careful about strings containing comma (though base64 usually doesn't)
            val arrayItems = cleanedArray.split("','").map { 
                it.trim().removePrefix("'").removePrefix("\"").removeSuffix("'").removeSuffix("\"") 
            }
            
            if (arrayItems.isEmpty()) return emptyList()

            // Brute-force Rotation
            // We know the decoded content must contain "hd_btn" or "jwplayer" or "master.m3u8"
            // The array is rotated by N positions.
            
            // Optimization: Decode *all* unique items once to see if we can find the key without rotation first?
            // No, the items are base64 encoded. If we decode them individually, they are correct strings.
            // Rotation changes the *order* (index), not the content of individual strings.
            // Wait, the obfuscation:
            // _0x4382 = function(index) { return array[index - offset] }
            // So the strings *themselves* are correct in the array, just their position is shifted.
            // AND they are encoded with the custom Base64.
            
            // So we can just decode ALL items and search for URL components.
            // The order matters for reconstruction, so we need to find the rotation if we want to rely on sequence.
            
            val decodedItems = arrayItems.map { decodeBase64Custom(it) }
            
            // 1. Search for known markers to confirm successful decoding
            val hasMarkers = decodedItems.any { it.contains("hd_btn") || it.contains("jwplayer") || it.contains("m3u8") }
            
            if (hasMarkers) {
                // Collect candidates that look like URLs or URL parts
                val potentialParts = decodedItems.filter { 
                    it.length > 3 && (it.contains("http") || it.contains("/") || it.contains("."))
                }
                
                // Aggressive reconstruction: 
                // We don't know the exact order without simulating the rotation count and offsets.
                // However, we can try to chain strings that look like they belong together.
                
                // Find start
                val starts = potentialParts.filter { it.startsWith("http") }
                
                starts.forEach { start ->
                    var current = start
                    var foundExtensions = false
                    
                    // Simple distinct pass to avoid infinite loops if duplicates exist
                    val pool = potentialParts.toMutableList()
                    pool.remove(start)
                    
                    var appended = true
                    while (appended) {
                        appended = false
                        // Find a part that fits at the end of current
                        val candidates = pool.filter { isValidContinuation(current, it) }
                        
                        if (candidates.isNotEmpty()) {
                            // Best candidate? Longest?
                            val best = candidates.first() 
                            current += best
                            pool.remove(best)
                            appended = true
                            
                            if (current.contains(".m3u8") || current.contains(".mp4")) {
                                foundExtensions = true
                            }
                        }
                    }
                    
                    if (foundExtensions && current.startsWith("http")) {
                        urls.add(current)
                    }
                }
                
                // Also add single items that are full URLs
                decodedItems.forEach {
                    if (it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4"))) {
                        urls.add(it)
                    }
                }
            }
            
        } catch (e: Exception) { }
        
        return urls.distinct()
    }
    
    private fun isValidContinuation(prefix: String, next: String): Boolean {
        // Don't append if next starts with http (new URL)
        if (next.startsWith("http")) return false
        
        // Don't append if it creates double extension
        if (prefix.contains(".m3u8") && next.contains(".m3u8")) return false
        
        // Heuristic: URL parts often split at slashes or dots
        if (prefix.endsWith("/") || next.startsWith("/")) return true
        if (prefix.endsWith(".") || next.startsWith(".")) return true
        
        // Specific patterns for FaselHD / scdns
        if (prefix.endsWith("master") && next.startsWith(".m3u8")) return true
        if (prefix.endsWith("index") && next.startsWith(".m3u8")) return true
        if (prefix.endsWith("scdns") && next.startsWith(".io")) return true
        
        // General alphanumeric join check (risky but needed if split mid-word)
        // e.g. "auth" + "Token"
        if (prefix.last().isLetterOrDigit() && next.first().isLetterOrDigit()) {
            return true 
        }
        
        return false
    }
}
