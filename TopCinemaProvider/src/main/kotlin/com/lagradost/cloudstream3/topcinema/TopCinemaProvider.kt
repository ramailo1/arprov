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
        "1207" to "أفلام أجنبية",
        "20349" to "أفلام عربية",
        "56286" to "أفلام Netflix",
        "64332" to "أفلام تركية",
        "61015" to "أفلام هندية",
        "1895" to "أفلام أنمي",
        "70137" to "مسلسلات رمضان 2026",
        "4" to "مسلسلات أجنبية",
        "17979" to "مسلسلات عربية",
        "53256" to "مسلسلات تركية",
        "56428" to "مسلسلات Netflix",
        "38" to "مسلسلات أنمي",
        "59186" to "مسلسلات كورية"
    )




    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = ArrayList<HomePageList>()

        mainPage.forEach { (name, data) ->
            val url = if (data.isEmpty()) {
                "$mainUrl/wp-json/wp/v2/posts?per_page=10&_embed"
            } else {
                "$mainUrl/wp-json/wp/v2/posts?categories=$data&per_page=10&_embed"
            }

            try {
                val responseText = app.get(url).text
                val response = mapper.readValue(responseText, object : com.fasterxml.jackson.core.type.TypeReference<List<WpPost>>() {})
                val searchResponses = response.mapNotNull { it.toSearchResponse() }

                if (searchResponses.isNotEmpty()) {
                    items.add(HomePageList(name, searchResponses))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return newHomePageResponse(items)
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
                "$url/watch/" // Direct connection to watch page logic
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
                // Ensure episode link goes to /watch/ directly if possible? 
                // No, usually episode links go to episode page, then we click watch.
                // We'll let loadLinks handle the /watch/ appending or detection.
                
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
        // Optimized: Directly append /watch/ if not present, bypassing the main page load if possible.
        // However, some URLs might already be watch URLs or distinct.
        // Standard Structure: https://topcima.online/movie-name/ -> https://topcima.online/movie-name/watch/
        
        var watchUrl = data
        if (!data.endsWith("/watch/") && !data.endsWith("/watch")) {
             watchUrl = if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
        }
        
        var doc = app.get(watchUrl, headers = headers).document
        
        // Debugging for "No link found"
        if (doc.select("ul#watch li").isEmpty()) {
            android.util.Log.d("TopCinema", "No servers found in ul#watch at: $watchUrl")
            // android.util.Log.d("TopCinema", "Doc HTML: ${doc.html()}") // Can be large
            
            // Fallback: Check for standard iframes if ul#watch is missing
             doc.select("iframe").forEach { iframe ->
                 val src = iframe.attr("src")
                 if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("twitter")) {
                     loadExtractor(src, data, subtitleCallback, callback)
                 }
            }
        }

        // Fallback: If 404 or redirect to main, it might mean /watch/ pattern doesn't apply (rare but possible)
        // Or if the page doesn't contain servers, maybe we need to find the link manually.
        if (doc.select("ul#watch").isEmpty() && doc.select("iframe").isEmpty()) {
             // Try fetching original data url and finding the watch link
             doc = app.get(data, headers = headers).document
             val manualWatchLink = doc.select("a.watch").attr("href")
             if (manualWatchLink.isNotEmpty()) {
                 watchUrl = manualWatchLink
                 doc = app.get(watchUrl, headers = headers).document
             }
        }
        
        // Extract servers from ul#watch li
        // Structure: <li data-watch="URL">Server Name</li>
        doc.select("ul#watch li").forEach { element ->
            val serverUrl = element.attr("data-watch").ifEmpty { 
                element.select("iframe").attr("src") 
            }
            if (serverUrl.isNotEmpty()) {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}