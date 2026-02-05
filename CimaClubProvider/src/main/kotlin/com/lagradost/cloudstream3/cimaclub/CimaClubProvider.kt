package com.lagradost.cloudstream3.cimaclub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CimaClubProvider : MainAPI() {

    override var mainUrl = "https://ciimaclub.us"
    override var name = "CimaClub"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept-Language" to "ar-SA,ar;q=0.9,en;q=0.8",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
    )

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("الحلقة\\s*\\d+"), "")
            .replace(Regex("الموسم\\s*\\d+"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun detectTvType(category: String, url: String, title: String = ""): TvType {
        return when {
            category.contains("انمي") || title.contains("انمي") || url.contains("anime") -> TvType.Anime
            url.contains("/series/") || title.contains("مسلسل") || title.contains("الحلقة") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("الحلقة\\s*(\\d+)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = when {
            baseUrl == mainUrl -> if (page == 1) baseUrl else "$mainUrl/page/$page/"
            baseUrl.contains("/movies") -> if (page == 1) baseUrl else "$baseUrl/page/$page/"
            baseUrl.contains("/series") -> if (page == 1) baseUrl else "$baseUrl?offset=$page"
            else -> baseUrl
        }

        val doc = app.get(url, headers = headers, timeout = 30).document
        // Fixed selector - items are direct children with class Small--Box
        val items = doc.select("#MainFiltar > .Small--Box, .Small--Box")
        val home = items.mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val link = fixUrl(a.attr("href"))
        
        // Try multiple selectors for title
        val rawTitle = selectFirst("h2, .inner--title h2")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val category = selectFirst(".category")?.text() ?: ""
        val type = detectTvType(category, link, rawTitle)

        return when (type) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
            else -> newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return doc.select(".Small--Box").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers, timeout = 30).document

        val rawTitle = doc.selectFirst("h1")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".Poster img, .image img")?.attr("src")
        val description = doc.selectFirst(".Story, .StoryArea")?.text()?.trim()
        val category = doc.selectFirst(".category")?.text() ?: ""

        val tvType = detectTvType(category, url, rawTitle)
        
        // Check if it's an episode page (contains "الحلقة" in title or URL)
        val isEpisodePage = rawTitle.contains("الحلقة") || url.contains("الحلقة")
        
        if (isEpisodePage || tvType != TvType.Movie) {
            // For episode pages, return single episode pointing to itself
            val episodeNumber = extractEpisodeNumber(rawTitle)
            val episodes = listOf(
                newEpisode(url) {
                    this.name = rawTitle
                    this.episode = episodeNumber
                }
            )

            return newTvSeriesLoadResponse(
                title,
                url,
                tvType,
                episodes
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = try {
            app.get(data, headers = headers, timeout = 30).document
        } catch (e: Exception) {
            return false
        }

        val loaded = HashSet<String>()

        fun isKnownHost(url: String): Boolean {
            val hosts = listOf(
                "peytonepre", "iplayerhls", "mxdrop", "filemoon", 
                "vudeo", "uqload", "luluvdo", "listeamed", "megaup",
                "1cloudfile", "multiup", "wasuytm", "vidmoly", "streamtape",
                "dood", "embed", "mega", "stream", "hd", "fembed", "govad"
            )
            return hosts.any { url.contains(it, ignoreCase = true) }
        }

        fun detectQuality(text: String): Int {
            return when {
                text.contains("1080") || text.contains("FHD") -> 1080
                text.contains("720") || text.contains("HD") -> 720
                text.contains("480") || text.contains("SD") -> 480
                text.contains("360") -> 360
                else -> Qualities.Unknown.value
            }
        }

        suspend fun loadFromIframe(iframeUrl: String, name: String = "Server") {
            val fixed = fixUrl(iframeUrl)
            if (!loaded.add(fixed)) return

            try {
                // If it's a known host, just use the extractor and return
                // This prevents fetching the page content for well-behaved extractors
                if (isKnownHost(fixed)) {
                    loadExtractor(fixed, mainUrl, subtitleCallback, callback)
                    return
                }

                // For others, fetch and look for direct links
                val iframeDoc = app.get(fixed, headers = headers, timeout = 30).document
                
                // Try to find direct video sources in iframe
                val videoPattern = Regex("""(https?://[^\"'<>\\s]+\\.(mp4|m3u8|mkv|avi))""", RegexOption.IGNORE_CASE)
                val matches = videoPattern.findAll(iframeDoc.toString())
                
                matches.forEach { match ->
                    val videoUrl = match.value
                    // Avoid recursion/loops
                    if (!loaded.contains(videoUrl)) {
                         callback(
                            newExtractorLink(
                                this.name,
                                "$name (Direct)",
                                videoUrl,
                                if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = detectQuality(videoUrl)
                            }
                        )
                        loaded.add(videoUrl)
                    }
                }

                // Try to extract from common embed patterns
                val jwPattern = Regex("""file["']?\\s*:\\s*["']([^"']+)["']""")
                jwPattern.findAll(iframeDoc.toString()).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.startsWith("http") && loaded.add(videoUrl)) {
                        callback(
                            newExtractorLink(
                                this.name,
                                name,
                                fixUrl(videoUrl),
                                if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = fixed
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }

            } catch (e: Exception) {
                // Fail silently for individual iframe
            }
        }

        // ======== 1. WATCH AREA - Try to get watch page first ========
        // Some CimaClub versions use a /watch subpage
        val watchUrl = if (data.endsWith("/")) "${data}watch" else "$data/watch"
        try {
            val watchDoc = app.get(watchUrl, headers = headers, timeout = 30).document
            
            // Find iframe embeds
            watchDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadFromIframe(src, "Watch Server")
                }
            }

            // Find server links with data attributes (legacy/alt support)
            watchDoc.select("[data-server], [data-link], [data-src], #watch li[data-watch]").forEach { el ->
                val serverUrl = el.attr("data-server").ifBlank { 
                    el.attr("data-link").ifBlank { 
                        el.attr("data-src").ifBlank { el.attr("data-watch") } 
                    }
                }
                if (serverUrl.isNotBlank() && serverUrl.startsWith("http")) {
                    loadFromIframe(serverUrl, el.text().trim().ifBlank { "Server" })
                }
            }

            // Find direct links in watch tabs
            watchDoc.select(".watch-tab, .server-item, .tab-item").forEach { tab ->
                val link = tab.attr("data-url").ifBlank { tab.attr("data-link") }
                if (link.isNotBlank() && link.startsWith("http")) {
                    loadFromIframe(link, tab.text().trim().ifBlank { "Server" })
                }
            }

        } catch (e: Exception) {
            // Fallback to main page if /watch doesn't exist or fails
        }

        // ======== 2. MAIN PAGE - Look for embeds ========
        // Also check the main doc we already loaded
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) {
                loadFromIframe(src, "Embed")
            }
        }
        
        // Also check #watch list on main page (if not handled by /watch above)
        doc.select("#watch li[data-watch]").forEach { li ->
             val url = li.attr("data-watch").trim()
             if (url.isNotBlank()) {
                 loadFromIframe(url, li.text().trim().ifBlank { "Server" })
             }
        }

        // ======== 3. DOWNLOAD AREA ========
        doc.select(".DownloadArea a[href], .download-area a[href], .downloads a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isNotBlank() && href.startsWith("http")) {
                val name = a.text().trim().ifBlank { "Download" }
                val quality = detectQuality(name + " " + a.parent()?.text())

                if (isKnownHost(href)) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }

                // Also add direct link (fallback)
                if (loaded.add(href)) {
                    callback(
                        newExtractorLink(
                            this.name,
                            "Download: $name",
                            href,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = quality
                        }
                    )
                }
            }
        }

        // ======== 4. AJAX/API endpoints ========
        // Try to find any API endpoints that return video sources
        val apiPattern = Regex("""(https?://[^\"'<>\\s]+/ajax/[^\"'<>\\s]+)""")
        val apiMatches = apiPattern.findAll(doc.toString())
        apiMatches.forEach { match ->
            try {
                val apiUrl = match.value
                val apiResponse = app.get(apiUrl, headers = headers, timeout = 15).text
                
                val videoPattern = Regex("""(https?://[^\"'<>\\s]+\\.(mp4|m3u8))""", RegexOption.IGNORE_CASE)
                videoPattern.findAll(apiResponse).forEach { videoMatch ->
                    val videoUrl = videoMatch.value
                    if (loaded.add(videoUrl)) {
                        callback(
                            newExtractorLink(
                                this.name,
                                "API Source",
                                videoUrl,
                                if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // Fail silently
            }
        }

        return loaded.isNotEmpty()
    }
}
