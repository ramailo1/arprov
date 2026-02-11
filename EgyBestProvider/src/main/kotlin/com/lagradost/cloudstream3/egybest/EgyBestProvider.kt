package com.lagradost.cloudstream3.egybest

import java.net.URLDecoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

import android.annotation.TargetApi
import android.os.Build
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class EgyBestProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egibest.net"
    override var name = "EgyBest (In Progress)"

    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val paginationCache = mutableMapOf<String, PaginationType>()

    private enum class PaginationType { PATH, QUERY }

    // ======= UTILITIES =======
    private fun decode(url: String): String = try { URLDecoder.decode(url, "UTF-8") } catch (e: Exception) { url }

    private fun String.cleanName(): String = this
        .replace(Regex("(?i)مشاهدة|فيلم|مسلسل|اون لاين|مترجم|مترجمة|مدبلج|مدبلجة|اونلاين|بجودة|تحميل|كامل|HD|BRRip|WEB-DL|BluRay|720p|1080p|480p|Series|Movie|Full"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun isMovie(url: String): Boolean {
        val decoded = decode(url)
        return (decoded.contains("فيلم") || decoded.contains("movie") || decoded.contains("masrahiya") || decoded.contains("مسرحية")) 
            && !decoded.contains("الحلقة") && !decoded.contains("episode")
    }

    private fun String.toWesternDigits(): String {
        val arabicDigits = mapOf(
            '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
            '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
        )
        return this.map { arabicDigits[it] ?: it }.joinToString("")
    }

    private fun String.getYearFromTitle(): Int? =
        Regex("""\((\d{4})\)""").find(this)?.groupValues?.get(1)?.toIntOrNull()

    private fun Element?.extractPoster(doc: Element? = null): String? {
        if (this == null) return null
        val img = when {
            this.tagName() == "img" -> this
            else -> this.selectFirst("img")
        }

        val poster = img?.attr("data-img")
            ?.ifBlank { img.attr("data-src") }
            ?.ifBlank { img.attr("src") }
            ?.takeIf { it.isNotBlank() }

        return poster?.let { fixUrl(it) }
            ?: doc?.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc?.selectFirst("meta[name=twitter:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Get URL
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val decodedUrl = decode(href)

        // Extract poster
        val posterUrl = this.extractPoster(this.ownerDocument())

        // Extract title
        var title = select(".title").text().ifEmpty { this.attr("title") }.cleanName()

        // Extract year
        val year = title.getYearFromTitle()

        // Determine type
        val tvType = if (isMovie(decodedUrl)) TvType.Movie else TvType.TvSeries

        // Remove year from title if present
        title = year?.let { title.replace("($it)", "").trim() } ?: title

        // Extract quality
        val qualityString = select("span.ribbon span").text().replace("-", "")
        val quality = getQualityFromString(qualityString)

        return newMovieSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }

    }

    private fun org.jsoup.nodes.Document.extractRecommendations(): List<SearchResponse> {
        return this.select(".movies_small .postBlock, .movies_small .postBlockCol, .related .postBlock")
            .mapNotNull { it.toSearchResponse() }
    }

    // ======= PAGINATION HELPER =======
    private suspend fun paginatedFetch(
        baseUrl: String,
        maxPages: Int = 3,
        fetchDoc: suspend (String) -> org.jsoup.nodes.Document,
        extractItems: (org.jsoup.nodes.Document) -> List<SearchResponse>
    ): List<SearchResponse> {
        val result = mutableListOf<SearchResponse>()
        var page = 1
        var paginationType: PaginationType? = paginationCache[baseUrl]

        while (page <= maxPages) {
            val url = when {
                page == 1 -> baseUrl
                paginationType == PaginationType.PATH -> "$baseUrl/page/$page/"
                paginationType == PaginationType.QUERY -> if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
                else -> baseUrl
            }

            val doc = fetchDoc(url)

            // Detect pagination type if not cached
            if (paginationType == null) {
                val nextPageLink = doc.select("a.page-numbers, a.next, li a").firstOrNull()?.attr("href") ?: ""
                paginationType = when {
                    nextPageLink.contains("/page/") -> PaginationType.PATH
                    nextPageLink.contains("?page=") -> PaginationType.QUERY
                    else -> PaginationType.PATH
                }
                paginationCache[baseUrl] = paginationType
            }

            val items = extractItems(doc)
            if (items.isEmpty()) break

            result.addAll(items)

            // Stop if no next page link
            val nextLink = doc.select("a.page-numbers, a.next, li a")
                .firstOrNull { it.text().matches(Regex("\\d+|التالي|Next")) }
                ?.attr("href")

            if (nextLink.isNullOrBlank()) break

            page++
        }

        return result.distinct().sortedBy { it.name }
    }

    // ======= MAIN PAGE =======
    override val mainPage = mainPageOf(
        "$mainUrl/recent" to "أحدث الاضافات",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/category/anime/" to "انمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = paginatedFetch(
            baseUrl = request.data,
            maxPages = 1,
            fetchDoc = { app.get(it).document },
            extractItems = { doc ->
                doc.select(".postBlock, .postBlockCol")
                    .filter { element -> element.parents().none { it.id() == "postSlider" } }
                    .mapNotNull { it.toSearchResponse() }
            }
        )
        return newHomePageResponse(request.name, items)
    }

    // ======= SEARCH =======
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/explore/?q=$query"
        return paginatedFetch(
            baseUrl = url,
            maxPages = 3,
            fetchDoc = { app.get(it).document },
            extractItems = { doc -> doc.select(".postBlock").mapNotNull { it.toSearchResponse() } }
        )
    }


    // ======= LOAD =======
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        return if (isMovie(url)) {
            loadMovie(doc, url)
        } else {
            loadTvSeries(doc, url)
        }
    }

    // ======= MOVIE LOADER =======
    private suspend fun loadMovie(doc: org.jsoup.nodes.Document, url: String): LoadResponse {
        val posterUrl = doc.selectFirst(".postImg, .postCover, .postBlockColImg, .poster").extractPoster(doc)
        val title = doc.select(".postTitle h1, h1.title, h1").text()
        val table = doc.select("table.postTable, table.full, table.table")

        val year = table.select("tr").firstOrNull { it.text().contains("سنة الإنتاج") }
            ?.select("td")?.lastOrNull()?.text()?.toIntOrNull() ?: title.getYearFromTitle()

        val tags = table.select("tr").firstOrNull { it.text().contains("النوع") }
            ?.select("a")?.map { it.text() }

        val synopsis = doc.select("p.description, .postStory, .story").text()

        val actors = doc.select("div.cast_list .cast_item, .story div a").mapNotNull { element ->
            val imgTag = element.selectFirst("img")
            val name = imgTag?.attr("alt") ?: element.text()
            val image = imgTag?.extractPoster()
            val roleString = element.selectFirst("span")?.text() ?: ""
            ActorData(actor = Actor(name, image), roleString = roleString)
        }

        // === Recommendations using toSearchResponse ===
        val recommendations = doc.extractRecommendations()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = synopsis
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    // ======= TV SERIES LOADER =======
    private suspend fun loadTvSeries(doc: org.jsoup.nodes.Document, url: String): LoadResponse {
        val posterUrl = doc.selectFirst(".postImg, .postCover, .postBlockColImg, .poster").extractPoster(doc)
        val title = doc.select(".postTitle h1, h1.title, h1").text()
        val table = doc.select("table.postTable, table.full, table.table")

        val year = table.select("tr").firstOrNull { it.text().contains("سنة الإنتاج") }
            ?.select("td")?.lastOrNull()?.text()?.toIntOrNull() ?: title.getYearFromTitle()

        val tags = table.select("tr").firstOrNull { it.text().contains("النوع") }
            ?.select("a")?.map { it.text() }

        val synopsis = doc.select("p.description, .postStory, .story").text()

        val actors = doc.select("div.cast_list .cast_item, .story div a").mapNotNull {
            val imgTag = it.selectFirst("img")
            val name = imgTag?.attr("alt") ?: it.text()
            val image = imgTag?.extractPoster()
            val roleString = it.selectFirst("span")?.text() ?: ""
            ActorData(actor = Actor(name, image), roleString = roleString)
        }
        
        // === FIXED: DETECT EPISODE PAGES AND FOLLOW SEASON NAVIGATION ===
        val episodes = ArrayList<Episode>()
        
        // Check if this is an EPISODE PAGE (has .all-episodes container with links)
        val isEpisodePage = doc.select(".all-episodes a").isNotEmpty()
        
        if (isEpisodePage) {
            // We're on an episode page - look for season navigation to find ALL seasons
            val seasonLinks = doc.select("a:contains(الموسم), a:contains(Season), .season-tab a, [href*=season], [href*=الموسم]")
                .map { fixUrl(it.attr("href")) }
                .filter { !it.contains("الحلقة") && !it.contains("episode") }
                .distinct()
            
            if (seasonLinks.isNotEmpty()) {
                // Follow each season link to get episodes from all seasons
                seasonLinks.forEach { seasonUrl ->
                    val seasonDoc = try { app.get(seasonUrl).document } catch(e: Exception) { null }
                    if (seasonDoc != null) {
                        extractEpisodesFromSeriesPage(seasonUrl, seasonDoc, posterUrl, episodes)
                    }
                }
            } else {
                // No season links found - just extract from current page (single season)
                extractEpisodesFromSeriesPage(url, doc, posterUrl, episodes)
            }
        } else {
            // Regular series/season page - extract directly
            extractEpisodesFromSeriesPage(url, doc, posterUrl, episodes)
        }

        val recommendations = doc.select(".related .postBlock, .movies_small .postBlock")
            .filter { element -> !element.text().contains("الحلقة") } // Exclude episode-like content
            .mapNotNull { element -> element.toSearchResponse() }

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode ?: Int.MAX_VALUE }))
        ) {
            this.posterUrl = posterUrl
            this.tags = tags
            this.year = year
            this.plot = synopsis
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    // === UPDATED: STRICT SERIES-PAGE EXTRACTION WITH PAGINATION & FILTER ===
    private suspend fun extractEpisodesFromSeriesPage(
        seriesUrl: String,
        doc: org.jsoup.nodes.Document, 
        defaultPoster: String?, 
        episodes: ArrayList<Episode>
    ) {
        // Extract keywords/slug from h1 for filtering
        val h1Title = doc.selectFirst(".postTitle h1, h1.title, h1")?.text()?.cleanName()?.lowercase() ?: ""
        
        // Remove season/episode numbers and Arabic ordinals to get pure series name
        val cleanedTitle = h1Title
            .replace(Regex("(?i)(الموسم|season)\\s*\\d+"), "") // Remove "Season 2", "الموسم 2"
            .replace(Regex("(?i)(الحلقة|episode|ep)\\s*\\d+"), "") // Remove "Episode 13", "الحلقة 13"
            .replace(Regex("(?i)(الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)"), "") // Remove Arabic ordinals
            .replace(Regex("\\s+"), " ").trim()
        
        val keywords = cleanedTitle.split(Regex("\\W+"))
            .filter { it.length >= 3 }
            .distinct()

        // 1. Paginated extraction (max 3 pages)
        var page = 1
        val paginationType = detectPaginationType(doc)
        while (page <= 3) {
            val pageUrl = if (page == 1) seriesUrl else buildPageUrl(seriesUrl, page, paginationType)
            val pageDoc = if (page == 1) doc else try { app.get(pageUrl).document } catch(e: Exception) { null } ?: break

            // Per-page episodes
            val pageEpisodes = pageDoc.select(".all-episodes a").ifEmpty {
                pageDoc.select(".movies_small .postBlock a, .episodes a, .season-episodes a").filter { element -> 
                    val href = element.attr("href")
                    val epText = element.text()
                    (epText.contains("الحلقة", true) || href.contains("الحلقة") || 
                     epText.contains("episode", true) || href.contains("episode")) &&
                    element.parents().none { p -> 
                        p.hasClass("related") || p.id() == "postSlider" || 
                        p.hasClass("recommendations") || p.hasClass("sidebar") || 
                        p.hasClass("recent") || p.text().contains("الجديد")
                    } &&
                    // Slug/keyword match on href & text
                    keywords.any { kw ->
                        decode(href).lowercase().contains(kw) || epText.lowercase().contains(kw)
                    }
                }
            }
            processEpisodeLinks(Elements(pageEpisodes), getSeasonNum(seriesUrl), defaultPoster, episodes, keywords)

            // Break if no next
            if (!hasNextPage(pageDoc)) break
            page++
        }
    }

    private fun detectPaginationType(doc: org.jsoup.nodes.Document): PaginationType {
        val nextLink = doc.select("a.page-numbers.next, a.next").firstOrNull()?.attr("href") ?: ""
        return when {
            nextLink.contains("/page/") -> PaginationType.PATH
            nextLink.contains("?page=") || nextLink.contains("&page=") -> PaginationType.QUERY
            else -> paginationCache.values.firstOrNull() ?: PaginationType.PATH
        }
    }

    private fun buildPageUrl(base: String, page: Int, type: PaginationType): String {
        val cleanBase = base.removeSuffix("/")
        return when (type) {
            PaginationType.PATH -> "$cleanBase/page/$page/"
            PaginationType.QUERY -> if (cleanBase.contains("?")) "$cleanBase&page=$page" else "$cleanBase?page=$page"
        }
    }

    private fun hasNextPage(doc: org.jsoup.nodes.Document): Boolean =
        doc.select("a.page-numbers.next, a.next, li.current + li a").isNotEmpty()

    private fun getSeasonNum(url: String): Int? =
        Regex("(?:الموسم|season)[ ._-]*(\\d+)", RegexOption.IGNORE_CASE)
            .find(decode(url))?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun processEpisodeLinks(
        episodeLinks: Elements, 
        seasonNum: Int?, 
        defaultPoster: String?, 
        episodes: ArrayList<Episode>,
        keywords: List<String>
    ) {
        val thumbMap = episodeLinks.associate { it.attr("href") to (it.selectFirst("img")?.extractPoster(doc = it.ownerDocument()) ?: defaultPoster) }

        episodeLinks.forEachIndexed { index, epLink ->
            val rawHref = epLink.attr("href")
            val hrefDecodedLower = decode(rawHref).lowercase()
            val epTextLower = epLink.text().cleanName().lowercase()

            // Enhanced: Require keyword match (extra safety)
            if (keywords.isNotEmpty() && !keywords.any { it in hrefDecodedLower || it in epTextLower }) return@forEachIndexed

            val epNumber = Regex("""(?:الحلقة|ep|episode)[ ._-]*(\\d+)""", RegexOption.IGNORE_CASE)
                .find(hrefDecodedLower)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?:الحلقة|ep|episode)[ ._-]*(\\d+)""", RegexOption.IGNORE_CASE)
                    .find(epTextLower)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            episodes.add(newEpisode(fixUrl(rawHref)) {
                name = "الحلقة $epNumber"
                season = seasonNum ?: 1
                episode = epNumber
                posterUrl = thumbMap[rawHref]
            })
        }
    }



    // ======= LOAD LINKS =======
    @TargetApi(Build.VERSION_CODES.O)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Collect extractor URLs from multiple sources
        val extractorUrls = mutableListOf<String>()

        // 1. Server list items
        doc.select("ul#watch-servers-list li, .servList li").forEach { li ->
            Regex("loadIframe\\(this, '(.*?)'\\)").find(li.attr("onclick"))?.groupValues?.getOrNull(1)?.let { extractorUrls.add(it) }
        }

        // 2. Direct iframe
        doc.select("iframe#videoPlayer").mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .forEach { extractorUrls.add(it) }

        // 3. Flatten and load all extractors
        extractorUrls.distinct().forEach { url ->
            loadExtractor(fixUrl(url), subtitleCallback, callback)
        }

        return true
    }
}
