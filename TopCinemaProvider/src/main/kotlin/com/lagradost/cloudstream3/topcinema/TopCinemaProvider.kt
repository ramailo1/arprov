package com.lagradost.cloudstream3.topcinema


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope


class TopCinemaProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://topcima.online"
    override var name = "TopCinema"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
        "Referer" to "$mainUrl/"
    )

    // WordPress API Data Classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpTitle(
        @JsonProperty("rendered") val rendered: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpFeaturedMedia(
        @JsonProperty("source_url") val sourceUrl: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpEmbedded(
        @JsonProperty("wp:featuredmedia") val featuredMedia: List<WpFeaturedMedia>?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpPost(
        @JsonProperty("id") val id: Int,
        @JsonProperty("date") val date: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: WpTitle,
        @JsonProperty("content") val content: WpTitle?, 
        @JsonProperty("categories") val categories: List<Int>?,
        @JsonProperty("yoast_head_json") val yoastHead: YoastHead?,
        @JsonProperty("_embedded") val embedded: WpEmbedded?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YoastHead(
        @JsonProperty("og_image") val ogImage: List<Map<String, Any>>? // Handling as generic map to be safe
    )

    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|فيلم|مترجم|مسلسل|اون لاين|كامل|جميع الحلقات|الموسم|الحلقة|انمي|تحميل".toRegex(), "")
            .replace(Regex("\\(\\d+\\)"), "")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun WpPost.toSearchResponse(): SearchResponse {
        val titleRaw = this.title.rendered
            .replace("&#8211;", "-")
            .replace("&#038;", "&")
            .replace("&#8217;", "'")
        
        val title = titleRaw.cleanTitle()
        
        // Extract Image safely - Try Embedded first, then Yoast
        val posterUrl = this.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?: this.yoastHead?.ogImage?.firstOrNull()?.get("url")?.toString()

        val href = this.link

        // Accurate Type Detection via Category IDs
        val seriesCategories = setOf(70137, 4, 17979, 53293, 56428, 53911, 53256, 56302, 76, 38, 59186, 67)
        val isSeries = this.categories?.any { it in seriesCategories } == true || 
                       titleRaw.contains("مسلسل") || 
                       titleRaw.contains("انمي")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Map keywords/names to WordPress Category IDs
    override val mainPage = mainPageOf(
        "" to "الأحدث",
        "70137" to "مسلسلات رمضان 2026",
        "1207" to "أفلام أجنبية",
        "20349" to "أفلام عربية",
        "1895" to "أفلام أنمي",
        "4" to "مسلسلات أجنبية",
        "17979" to "مسلسلات عربية",
        "38" to "مسلسلات أنمي",
        "59186" to "مسلسلات كورية"
    )




    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        // Parallel fetching to prevent timeouts
        val items = mainPage.map { (name, data) ->
            async {
                try {
                    val url = if (data.isEmpty()) {
                        "$mainUrl/wp-json/wp/v2/posts?per_page=10&_embed"
                    } else {
                        "$mainUrl/wp-json/wp/v2/posts?categories=$data&per_page=10&_embed"
                    }

                    // Log.d("TopCinema", "Fetching: $url")
                    val responseText = app.get(url).text
                    val response = mapper.readValue(responseText, object : com.fasterxml.jackson.core.type.TypeReference<List<WpPost>>() {})
                    val searchResponses = response.mapNotNull { it.toSearchResponse() }
                    
                    if (searchResponses.isNotEmpty()) {
                        HomePageList(name, searchResponses)
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }.awaitAll().filterNotNull()

        newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/wp-json/wp/v2/posts?search=$query&per_page=10&_embed"
        return try {
            val responseText = app.get(url).text
            val response = mapper.readValue(responseText, object : com.fasterxml.jackson.core.type.TypeReference<List<WpPost>>() {})
            response.mapNotNull { it.toSearchResponse() } // 'it' works here
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // We still fetch the page HTML for 'load' because we need the episode list/server links which might be stored in meta or HTML structure not fully exposed in the basic API (or requires separate calls).
        // However, we can optimize by checking cached data if we implemented caching, but standard load is fine for detailed info.
        
        // Improvement: Use the '/watch/' url logic directly here if it's a movie to save a click? 
        // No, we need metadata (plot, year, etc) which is on the main page.
        
        var doc = app.get(url, headers = headers).document
        
        // Smart Redirect: If on episode page, find breadcrumb series link
        val seriesLink = doc.select(".breadcrumbs a[href*='/series/'], .breadcrumbs a[href*='/مسلسل/'], .breadcrumbs a:nth-last-child(2)").firstOrNull()
        if (seriesLink != null && url.contains("/episodes/|/الحلقة/".toRegex())) {
            val seriesUrl = fixUrl(seriesLink.attr("href"))
            if (seriesUrl != url) {
                doc = app.get(seriesUrl, headers = headers).document
            }
        }

        val titleRaw = doc.select("h1.title, .movie-title, .PostTitle, h1").text()
        val title = titleRaw.cleanTitle()
        // Determine type: If it has seasons/episodes list, it is a series
        val hasEpisodes = doc.select(".allepcont, .EpisodesList, .list-episodes, .seasonslist").isNotEmpty()
        val isMovie = !hasEpisodes && !url.contains("/series/|/مسلسل/|/season/".toRegex())

        val posterUrl = doc.select("meta[property='og:image']").attr("content")
        val synopsis = doc.select(".description, .plot, .summary, .StoryArea, .Story").text()
        val year = doc.select(".year, .release-year, a[href*='/release-year/']").text().filter { it.isDigit() }.toIntOrNull()
        val tags = doc.select(".genre a, .categories a").map { it.text() }
        
        val recommendations = doc.select(".related-movies .movie-item, .Block--Item, .Small--Box, .AsidePost").mapNotNull { element ->
             val recTitle = element.select("h3").text()
             val recHref = element.select("a").attr("href")
             val recPoster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
             if (recHref.isNotEmpty()) {
                 newMovieSearchResponse(recTitle, recHref, TvType.Movie) { this.posterUrl = recPoster }
             } else null
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url // Use original URL, loadLinks will handle /watch/ or /download/
            ) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = arrayListOf<Episode>()
            val cleanSeriesTitle = title.split(" ").take(3).joinToString(" ") 
            
            // Layout 1: List style (.allepcont .row a)
            doc.select(".allepcont .row a, .EpisodesList .row a").forEach { episode ->
                val epLink = fixUrl(episode.attr("href"))
                
                val epRawName = episode.select(".ep-info h2").text().ifEmpty { episode.text() }
                var epName = epRawName.cleanTitle()
                if (epName.contains(cleanSeriesTitle, true)) {
                    epName = epName.replace(cleanSeriesTitle, "", true).trim()
                }
                val epNum = episode.select(".epnum").text().toIntOrNull() ?: Regex("\\d+").find(epRawName)?.value?.toIntOrNull()
                val epPoster = episode.select("img").attr("src").ifEmpty { posterUrl }
                
                episodes.add(newEpisode(epLink) {
                    this.name = epName.ifBlank { "الحلقة $epNum" }
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.season = 1 
                })
            }
            
            // Layout 2: Grid style (.Small--Box)
            if (episodes.isEmpty()) {
                doc.select(".Small--Box, .Block--Item, .GridItem").forEach { episode ->
                    val a = episode.selectFirst("a") ?: return@forEach
                    val epLink = fixUrl(a.attr("href"))
                    val epRawName = a.select("h3").text()
                    var epName = epRawName.cleanTitle()
                     if (epName.contains(cleanSeriesTitle, true)) {
                        epName = epName.replace(cleanSeriesTitle, "", true).trim()
                    }
                    val epNum = episode.select(".number em").text().toIntOrNull() ?: Regex("\\d+").find(epRawName)?.value?.toIntOrNull()
                    val epPoster = episode.select("img").attr("src").ifEmpty { posterUrl }
                    
                    if (epRawName.contains("حلقة|الحلقة".toRegex()) || epNum != null) {
                        episodes.add(newEpisode(epLink) {
                            this.name = epName.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.season = 1
                        })
                    }
                }
            }

            // Layout 3: AJAX Loading (MasterDecode theme)
            if (episodes.isEmpty() || doc.select(".seasonslist").isNotEmpty()) {
                try {
                    val scripts = doc.select("script").joinToString("\n") { it.html() }
                    val ajaxtUrl = Regex("var AjaxtURL = \"(.*?)\";").find(scripts)?.groupValues?.get(1)
                        ?: "https://topcima.online/wp-content/themes/Master%20Decode-TopCinema-2-1/Ajaxt/"
                    val postId = doc.select("input#shortlink").attr("value").split("?p=").lastOrNull()
                        ?: Regex("post_id: '(\\d+)'").find(doc.html())?.groupValues?.get(1)
                        ?: Regex("\"post_id\",\"(\\d+)\"").find(doc.html())?.groupValues?.get(1)

                    if (postId != null) {
                        val seasonsList = doc.select(".seasonslist li").ifEmpty { listOf(null) }
                        seasonsList.forEachIndexed { index, seasonEl ->
                            val seasonNum = seasonEl?.attr("data-season")?.toIntOrNull() ?: (index + 1)
                            val response = app.post(
                                "${ajaxtUrl}Single/Episodes.php",
                                data = mapOf("season" to "$seasonNum", "post_id" to postId),
                                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                            ).text
                            
                            val seasonDoc = org.jsoup.Jsoup.parse(response)
                            seasonDoc.select(".allepcont .row a, .EpisodesList .row a, a").forEach { ep ->
                                val epLink = fixUrl(ep.attr("href"))
                                if (epLink.isEmpty() || epLink == mainUrl) return@forEach
                                
                                val epRawName = ep.select(".ep-info h2").text().ifEmpty { ep.text() }.trim()
                                if (epRawName.contains("المشاهدة الان|التحميل الان".toRegex())) return@forEach
                                
                                val epNum = ep.select(".epnum").text().toIntOrNull() 
                                    ?: Regex("\\d+").find(epRawName)?.value?.toIntOrNull()
                                
                                episodes.add(newEpisode(epLink) {
                                    this.name = epRawName.cleanTitle()
                                    this.episode = epNum
                                    this.season = seasonNum
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        android.util.Log.d("TopCinema", "loadLinks called: $data")

        val pageUrl = data.trimEnd('/')
        val watchUrl = "$pageUrl/watch/"

        // ── Step 1: fetch /watch/ page ──────────────────────────────────────
        val watchResponse = try {
            app.get(watchUrl, headers = headers)
        } catch (e: Exception) {
            android.util.Log.e("TopCinema", "Failed to fetch watch page: ${e.message}")
            null
        }

        if (watchResponse != null && watchResponse.code == 200) {
            val doc = watchResponse.document

            // Also immediately try the already-active iframe embed (first loaded server)
            val activeIframeUrl = doc.selectFirst("div.WatchIframe iframe, .player-embed iframe")?.attr("src") ?: ""
            if (activeIframeUrl.isNotEmpty() && !activeIframeUrl.contains("reviewrate.net")) {
                android.util.Log.d("TopCinema", "Active embed iframe: $activeIframeUrl")
                safeExtract(activeIframeUrl, watchUrl, subtitleCallback, callback)
            }

            // Enumerate all server buttons
            val servers = doc.select("ul#watch > li, .servers-list li, [data-watch]")
            android.util.Log.d("TopCinema", "Server count: ${servers.size}")

            servers.forEach { li ->
                // Prefer data-watch; fall back to noscript iframe src
                val rawUrl = li.attr("data-watch").trim().ifEmpty {
                    li.select("noscript iframe, iframe").attr("src").trim()
                }
                if (rawUrl.isEmpty() || rawUrl == pageUrl || rawUrl == watchUrl) return@forEach

                android.util.Log.d("TopCinema", "Server URL: $rawUrl")

                when {
                    // reviewrate.net is JS domain-locked to arabseed.show only – skip direct scrape.
                    // Try the functionally identical w5.gamehub.cam mirror instead.
                    rawUrl.contains("reviewrate.net") -> {
                        val mirrorUrl = rawUrl
                            .replace("m.reviewrate.net", "w5.gamehub.cam")
                            .replace("reviewrate.net", "w5.gamehub.cam")
                        android.util.Log.d("TopCinema", "reviewrate mirror: $mirrorUrl")
                        scrapeM3u8(mirrorUrl, watchUrl, "ReviewRate", callback)
                    }

                    rawUrl.contains("gamehub.cam") ->
                        scrapeM3u8(rawUrl, watchUrl, "GameHub", callback)

                    // filemoon needs the correct /e/ path (already correct from the HTML)
                    rawUrl.contains("filemoon") || rawUrl.contains("moonplayer") ->
                        safeExtract(rawUrl, watchUrl, subtitleCallback, callback)

                    // Standard extractors cover: vidmoly, savefiles, bigwarp, ups2up, doodstream, streamtape …
                    else ->
                        safeExtract(rawUrl, watchUrl, subtitleCallback, callback)
                }
            }
        }

        // ── Step 2: fallback – scrape the main movie page for any iframe ────
        if (watchResponse == null || watchResponse.code != 200) {
            try {
                val mainDoc = app.get(pageUrl, headers = headers).document
                mainDoc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotEmpty() && !src.contains("reviewrate.net")) {
                        safeExtract(src, pageUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TopCinema", "Fallback main page error: ${e.message}")
            }
        }

        return true
    }

    /** Wrap loadExtractor with per-server error isolation. */
    private suspend fun safeExtract(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            android.util.Log.e("TopCinema", "loadExtractor failed for $url : ${e.message}")
            // Last-resort: try to pluck an m3u8 URL directly
            scrapeM3u8(url, referer, "Direct", callback)
        }
    }

    /** Fetch a player page and scrape the first m3u8 / mp4 link found. */
    private suspend fun scrapeM3u8(
        url: String,
        referer: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val text = app.get(
                url,
                headers = headers + mapOf("Referer" to referer)
            ).text

            // Look for HLS
            val m3u8 = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
                .find(text)?.groupValues?.get(1)

            if (m3u8 != null) {
                android.util.Log.d("TopCinema", "Scraped m3u8 from $url : $m3u8")
                callback.invoke(
                    newExtractorLink(
                        sourceName, sourceName, m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Fallback look for JW-player "file" key
            val fileUrl = Regex("""(?:file|src)\s*[=:]\s*["']([^"']+)["']""")
                .findAll(text)
                .map { it.groupValues[1] }
                .firstOrNull { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4")) }

            if (fileUrl != null) {
                android.util.Log.d("TopCinema", "Scraped file from $url : $fileUrl")
                val type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(sourceName, sourceName, fileUrl, type = type) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("TopCinema", "scrapeM3u8 failed for $url : ${e.message}")
        }
    }
}