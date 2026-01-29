package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
// Removing Android imports to prevent build/runtime issues, will use custom Base64 if needed or standard java if safe.
// Cloudstream usually supports java.util.Base64 on supported devices (API 26+), but to be safe for lower APIs, 
// we will use a pure Kotlin Base64 implementation or `android.util.Base64` appropriately.
// Since `android.util.Base64` caused R8 issues, and `java.util.Base64` might crash on old Android,
// I'll add a simple pure Kotlin decoder.

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
        // Swap case: a-z <-> A-Z
        val swapped = input.map { char ->
            when {
                char in 'a'..'z' -> char.uppercaseChar()
                char in 'A'..'Z' -> char.lowercaseChar()
                else -> char
            }
        }.joinToString("")
        
        return try {
            // Use Android Base64 if available, otherwise pure kotlin implementation could do,
            // but for CloudStream android.util.Base64 is standard. 
            // Previous build fail might be due to R8. Let's try simple android.util.Base64 again
            // but ensure we import it correctly or use full path.
            String(android.util.Base64.decode(swapped, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            try {
                // Fallback for non-Android environments (tests)
                String(java.util.Base64.getDecoder().decode(swapped))
            } catch (e2: Exception) {
                "" 
            }
        }
    }

    private fun extractVideoUrl(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Strategy 1: K-Variable De-obfuscation (Old Pattern)
        try {
            val kVarPattern = Regex("""var\s+K\s*=\s*['"]([^'"]+)['"]""")
            kVarPattern.find(html)?.let { match ->
                val kString = match.groupValues[1]
                val charArray = kString.toCharArray()
                if (charArray.isNotEmpty()) {
                    val result = StringBuilder(charArray[0].toString())
                    for (i in 1 until charArray.size) {
                        val c = charArray[i]
                        if (i % 2 != 0) result.append(c) else result.insert(0, c)
                    }
                    val decodedString = result.toString()
                    decodedString.split("z").forEach { token ->
                        if (token.contains("http") && (token.contains(".m3u8") || token.contains(".mp4"))) {
                            urls.add(token)
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        if (urls.isNotEmpty()) return urls

        // Strategy 2: Array Rotation & Concatenation (New Pattern - Obfuscator.io)
        try {
            // 1. Extract the master array
            val arrayRegex = Regex("""var\s+_0x\w+=\[(.*?)\];""")
            val arrayMatch = arrayRegex.find(html) ?: return emptyList()
            
            val rawArray = arrayMatch.groupValues[1]
            // Handle multi-line arrays
            val cleanedArray = rawArray.replace("\n", "").replace("\r", "")
            val arrayItems = cleanedArray.split("','").map { it.replace("'", "") }
            
            if (arrayItems.isEmpty()) return emptyList()

            val decodedItems = arrayItems.mapNotNull { 
                val decoded = decodeBase64Custom(it)
                if (decoded.isNotEmpty()) decoded else null
            }

            // 2. Identify String Literals in the code to use as Anchors
            // format: 'ster.c.scdns.io' or "ster.c.scdns.io"
            val literalPattern = Regex("""['"]([^'"]{5,})['"]""") // Min 5 chars to avoid noise
            val literals = literalPattern.findAll(html).map { it.groupValues[1] }.toList()
            
            // 3. Brute-force reconstruction
            // We look for a "Start" chunk (http) and an "End" chunk (m3u8/mp4)
            // But they are separated.
            // Try to find a valid URL by concatenating ANY two decoded items or Literal + Decoded Item
            // Since the offset is constant, if we find match unique pieces we can solve it.
            
            // Heuristic A: Look for "http" in decoded items, and "m3u8" in decoded items.
            // The middle parts might be literals.
            
            // Heuristic B: Scan for known FaselHD domains in literals or decoded items
            val allParts = decodedItems + literals
            
            // If we have a part ending in 'ma' and a part starting with 'ster', we can join them.
            // This is O(N^2) which is fine for N=500.
            
            val potentialStarts = allParts.filter { it.startsWith("http") }
            
            potentialStarts.forEach { start ->
                // Try to build a chain. 
                // Current string: start
                // Find matching next part.
                var current = start
                var foundNext = true
                var iterations = 0
                
                while (foundNext && iterations < 20) {
                    // Check if current is a valid URL by itself
                    if (current.contains(".m3u8") || current.contains(".mp4")) {
                        urls.add(current)
                        break
                    }
                    
                    foundNext = false
                    // Look for a piece that continues 'current' 
                    // Matches at least 3 chars of overlap? No, pieces are usually adjacent.
                    // Just purely concatenation?
                    
                    // Actually, the pieces are concatenated. 
                    // "https://ma" + "ster.c.scdns.io" -> "https://master.c.scdns.io"
                    // Overlap is 'ma' + 'ster' = 'master'. No overlap character-wise usually.
                    // But semantically:
                    
                    // Let's look for parts that complete words.
                    // 'stream' 'master' 'index' 'fasel'
                    
                    // Brute force: Try to append ANY other part. If the result contains a common keyword?
                    for (part in allParts) {
                        if (part == current) continue // Skip self
                        
                        val combined = current + part
                        
                        // Heuristic: Does the join point look nice?
                        // e.g. "https://ma" + "ster" -> "https://master" (Good)
                        // "https://ma" + "http" -> "https://mahttp" (Bad)
                        
                        // We need a validator.
                        if (isValidContinuation(current, part)) {
                            current = combined
                            foundNext = true
                            break 
                        }
                    }
                    iterations++
                }
            }
            
            // Fallback: If we just find a single string that is the URL (some encoders do this)
            decodedItems.forEach { 
                if (it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4"))) {
                    urls.add(it)
                }
            }
            
        } catch (e: Exception) { }
        
        return urls.distinct()
    }
    
    private fun isValidContinuation(prefix: String, next: String): Boolean {
        val combined = prefix + next
        
        // Reject invalid protocols
        if (combined.count { it == ':' } > 2) return false
        
        // Common patterns in FaselHD URLs
        if (prefix.endsWith("ma") && next.startsWith("ster")) return true
        if (prefix.endsWith("scdns") && next.startsWith(".io")) return true
        if (prefix.endsWith("stream") && next.startsWith("/v")) return true
        if (prefix.endsWith("/v") && next.firstOrNull()?.isDigit() == true) return true
        if (prefix.endsWith("/") && next.startsWith("web")) return true
        
        // If we are at the end, checks
        if (next.contains(".m3u8") || next.contains(".mp4")) return true
        
        // General sanity check: don't join if it creates "httphttp"
        if (next.startsWith("http")) return false
        
        return false
    }
}
