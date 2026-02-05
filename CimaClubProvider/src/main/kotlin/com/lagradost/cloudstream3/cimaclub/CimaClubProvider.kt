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
            .replace(Regex("الحلقة\\s*\\d+\\s*مترجمة"), "")
            .replace(Regex("الحلقة\\s*\\d+\\s*مدبلجة"), "")
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
            // Movies and Series both use /page/N/ structure on this site
            baseUrl.contains("/movies") -> if (page == 1) baseUrl else "$baseUrl/page/$page/"
            baseUrl.contains("/series") -> if (page == 1) baseUrl else "$baseUrl/page/$page/"
            else -> baseUrl
        }

        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = doc.select(".Small--Box, .small--box, .MovieItem")
        val home = items.mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val link = fixUrl(a.attr("href"))
        
        val rawTitle = selectFirst("h2, .inner--title h2, .title")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        // Prioritize standard src for some layouts, check data-src for others
        val poster = selectFirst("img")?.let {
            val src = it.attr("src")
            val dataSrc = it.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else src
        }

        val category = selectFirst(".category, .cat")?.text() ?: ""
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
        return doc.select(".Small--Box, .MovieItem").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers, timeout = 30).document

        val rawTitle = doc.selectFirst("h1, .TitleArea h1, .PageTitle")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        // Metadata extraction - ensure we check all possible containers
        val poster = doc.selectFirst(".Poster img, .image img, .Thumb img, .SingleContent img, .PostThumb img")?.let {
            val src = it.attr("src")
            val dataSrc = it.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else src
        }
        
        val description = doc.selectFirst(".Story, .StoryArea, .description, .PostContent, .SinglePost p")?.text()?.trim()
        val category = doc.selectFirst(".category, .cat")?.text() ?: ""

        val tvType = detectTvType(category, url, rawTitle)
        
        // Strict Series Check: Does it have the episode container?
        val episodeContainer = doc.selectFirst(".allepcont, .Episodes--Seasons--Episodes")
        val isSeriesPage = episodeContainer != null
        
        // If it's a "Series" by type/URL but has no episode list container, 
        // it's likely a direct episode page (like "Leil Ep 14").
        // We should treat it as a single episode to avoid empty/wrong episode lists.
        
        if (isSeriesPage) {
            // Fetch episodes from strict container ONLY
            val episodes = mutableListOf<Episode>()
            
            // STRICT SELECTOR: Use the container we found to avoid sidebar leaks
            val potentialEpisodes = episodeContainer!!.select("a")
            
            potentialEpisodes.forEach { epLink ->
                val href = epLink.attr("href")
                val decodedHref = try { java.net.URLDecoder.decode(href, "UTF-8") } catch(e:Exception) { href }
                val text = epLink.text()
                
                // Still do basic sanity checks, but rely mostly on the container
                if (href.isNotBlank()) {
                     val epUrl = fixUrl(href)
                     val epTitle = epLink.text().trim()
                     
                     // De-duplication
                     if (episodes.none { it.data == epUrl }) {
                        val epNum = extractEpisodeNumber(epTitle) ?: episodes.size + 1
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle.ifBlank { "الحلقة $epNum" }
                                this.episode = epNum
                            }
                        )
                     }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                tvType,
                episodes.reversed() // Usually list is new->old, we want old->new generally, or keep as is?
                // Site lists episodes often 1..N or N..1. Let's keep strict order as site for now unless requested.
                // Actually reversed() is safer for some sites if they list latest first.
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
            }
        } else {
            // Single Episode Page OR Movie Page
            // If it's technically a "Series" type but no episode list, treat as 1-episode series
            
            if (tvType == TvType.TvSeries || url.contains("episode") || rawTitle.contains("الحلقة")) {
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
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loaded = HashSet<String>()

        fun isKnownHost(url: String): Boolean {
            val hosts = listOf(
                "peytonepre", "iplayerhls", "mxdrop", "filemoon", "mixdrop",
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

        suspend fun loadFromIframe(iframeUrl: String, name: String = "Server", referer: String) {
            val fixed = fixUrl(iframeUrl)
            if (!loaded.add(fixed)) return

            try {
                if (isKnownHost(fixed)) {
                    loadExtractor(fixed, referer, subtitleCallback, callback)
                    return
                }

                val iframeDoc = app.get(fixed, headers = mapOf("Referer" to referer), timeout = 30).document
                
                val videoPattern = Regex("""(https?://[^\"'<>\\s]+\\.(mp4|m3u8|mkv|avi))""", RegexOption.IGNORE_CASE)
                videoPattern.findAll(iframeDoc.toString()).forEach { match ->
                    val videoUrl = match.value
                    if (!loaded.contains(videoUrl)) {
                         callback(
                            newExtractorLink(
                                this.name,
                                "$name (Direct)",
                                videoUrl,
                                if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer
                                this.quality = detectQuality(videoUrl)
                            }
                        )
                        loaded.add(videoUrl)
                    }
                }

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
            }
        }

        // 1. Fetch the main data URL (Episode Page or Series Page)
        val doc = try {
            app.get(data, headers = headers, timeout = 30).document
        } catch (e: Exception) {
            return false
        }

        // 2. Identify the Watch Page URL
        var watchPageUrl = data
        val watchButton = doc.selectFirst("a.watch, a:contains(مشاهدة وتحميل), .watch-btn")
        
        if (watchButton != null) {
             val href = watchButton.attr("href")
             if (href.isNotBlank()) {
                 watchPageUrl = fixUrl(href)
             }
        } else if (!data.endsWith("/watch") && !data.endsWith("/watch/")) {
             // Fallback: try appending /watch if not found, but only if we suspect it's needed
             // Be careful not to break direct server links if they ever exist
             val testUrl = if (data.endsWith("/")) "${data}watch" else "$data/watch"
             watchPageUrl = testUrl
        }

        // 3. Load the Watch Page content
        val watchDoc = if (watchPageUrl != data) {
            try {
                // IMPORTANT: Use the Episode URL as Referer when fetching Watch Page
                app.get(watchPageUrl, headers = mapOf("Referer" to data)).document
            } catch (e: Exception) {
                null
            }
        } else {
            doc
        }

        // --- Extract Servers ---
        val workingDoc = watchDoc ?: doc
        val workingUrl = if (watchDoc != null) watchPageUrl else data
        
        val serverElements: List<Element> = workingDoc.select("#watch li, .ServersList li").toList()
        serverElements.forEach { li ->
             // Possible attributes for the URL/ID
             val url = li.attr("data-watch").ifBlank { 
                 li.attr("data-source").ifBlank { 
                     li.attr("data-url") 
                 }
             }.trim()
             
             val name = li.text().trim().ifBlank { "Server" }
             if (url.isNotBlank()) {
                 if (url.startsWith("http")) {
                     loadFromIframe(url, name, workingUrl)
                 } else {
                     if (url.contains("/embed/")) {
                         loadFromIframe(fixUrl(url), name, workingUrl)
                     }
                 }
             }
        }
        
        val videoExtPattern = Regex("""\.(mp4|m3u8|mkv|avi|flv|mov|wmv)($|\?)""", RegexOption.IGNORE_CASE)

        val docsToCheck = if (workingDoc != doc) listOf(doc, workingDoc) else listOf(doc)
        val docs: List<Element> = docsToCheck
        docs.forEach { d ->
            val downloadElements: List<Element> = d.select(".DownloadArea a[href], .download-area a[href], .downloads a[href], .ServersList.Download a[href]").toList()
            downloadElements.forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank() && href.startsWith("http")) {
                    val name = a.text().trim().ifBlank { "Download" }
                    val quality = detectQuality(name + " " + a.parent()?.text())
                    
                    if (isKnownHost(href)) {
                        loadExtractor(href, data, subtitleCallback, callback)
                    } else if (videoExtPattern.containsMatchIn(href)) {
                        // Only add as direct link if it looks like a video file
                        if (loaded.add(href)) {
                            callback(
                                newExtractorLink(
                                    this.name,
                                    "Download: $name",
                                    href,
                                    if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = quality
                                }
                            )
                        }
                    }
                }
            }
        }

        // ======== 4. AJAX/API endpoints ========
        // Try to find any API endpoints that return video sources
        val apiPattern = Regex("""(https?://[^\"'<>\\s]+/ajax/[^\"'<>\\s]+)""")
        val apiMatches: Sequence<MatchResult> = apiPattern.findAll(doc.toString())
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
