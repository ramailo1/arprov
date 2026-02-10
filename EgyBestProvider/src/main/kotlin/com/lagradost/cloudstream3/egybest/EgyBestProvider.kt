package com.lagradost.cloudstream3.egybest

import java.net.URLDecoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

import android.annotation.TargetApi
import android.os.Build
import org.jsoup.nodes.Element

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
        val episodes = ArrayList<Episode>()

        // === Process season and episode links ===
        // Get all season links
        val seasonLinks = doc.select(".h_scroll a, a:contains(الموسم)").toList().filter {
            it.parents().none { p -> p.hasClass("related") || p.hasClass("movies_small") }
        }.map { it.attr("href") }.distinct()

        val processEpisodeLinks: (List<Element>, Int?, String?) -> Unit = { episodeLinks, seasonNum, defaultPoster ->
            val thumbMap = episodeLinks.associate { ep ->
                ep.attr("href") to (ep.selectFirst("img")?.extractPoster() ?: defaultPoster)
            }

            episodeLinks.forEachIndexed { index, epLink ->
                val href = epLink.attr("href")
                val decodedHref = decode(href)
                val epText = epLink.text().cleanName()
                val hrefDigits = decodedHref.toWesternDigits()
                val textDigits = epText.toWesternDigits()

                val epNumber = Regex("""(?:ep|الحلقة|episode)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(hrefDigits)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?:ep|الحلقة|episode)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(textDigits)?.groupValues?.get(1)?.toIntOrNull()

                val episodeNum = epNumber ?: (index + 1)

                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = epNumber?.let { "الحلقة $it" } ?: epText
                    this.season = seasonNum ?: 1
                    this.episode = episodeNum
                    this.posterUrl = thumbMap[href]
                })
            }
        }

        if (seasonLinks.isNotEmpty()) {
            seasonLinks.forEach { seasonUrl ->
                val d = app.get(fixUrl(seasonUrl)).document
                val normalizedSeasonUrl = seasonUrl.toWesternDigits()
                val seasonNum = Regex("""(?:season|الموسم)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(normalizedSeasonUrl)?.groupValues?.get(1)?.toIntOrNull()

                val episodeLinks = d.select(".all-episodes a").ifEmpty {
                    d.select("a:contains(الحلقة)").toList().filter { el ->
                        el.parents().none { p ->
                            p.hasClass("slider") || p.hasClass("owl-carousel") || p.hasClass("related") || p.hasClass("movies_small")
                        }
                    }
                }

                processEpisodeLinks(episodeLinks, seasonNum, posterUrl)
            }
        } else {
            // Single season / no season links
            val episodeLinks = doc.select(".all-episodes a").ifEmpty {
                doc.select("a:contains(الحلقة)").toList().filter { el ->
                    el.parents().none { p ->
                        p.hasClass("slider") || p.hasClass("owl-carousel") || p.hasClass("related") || p.hasClass("movies_small")
                    }
                }
            }
            processEpisodeLinks(episodeLinks, 1, posterUrl)
        }

        val recommendations = doc.extractRecommendations()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
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
