package com.lagradost.cloudstream3.egybest

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
        val url = this.attr("href") ?: return null
        val posterUrl = this.extractPoster(this.ownerDocument())
        var title = select(".title").text().ifEmpty { this.attr("title") }
        val year = title.getYearFromTitle()
        val isMovie = Regex(".*/(movie|masrahiya)/").containsMatchIn(url)
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries
        title = if (year != null) title else title.split(" (")[0].trim()
        val quality = select("span.ribbon span").text().replace("-", "")
        return newMovieSearchResponse(title, fixUrl(url), tvType) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
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
        var continuePaging = true
        var paginationType: PaginationType? = paginationCache[baseUrl]

        while (continuePaging && page <= maxPages) {
            val url = if (page == 1) baseUrl else {
                when (paginationType) {
                    PaginationType.PATH -> "$baseUrl/page/$page/"
                    PaginationType.QUERY -> if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
                    null -> baseUrl
                }
            }

            val doc = fetchDoc(url)

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

            val nextLink = doc.select("a.page-numbers, a.next, li a")
                .firstOrNull { it.text().matches(Regex("\\d+|التالي|Next")) }
                ?.attr("href")

            continuePaging = !nextLink.isNullOrBlank()
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        newHomePageResponse(
            request.name,
            paginatedFetch(
                baseUrl = request.data,
                maxPages = 1,
                fetchDoc = { app.get(it).document },
                extractItems = { doc ->
                    doc.select(".postBlock, .postBlockCol")
                        .filter { element -> element.parents().none { it.id() == "postSlider" } }
                        .mapNotNull { it.toSearchResponse() }
                }
            )
        )

    // ======= SEARCH =======
    override suspend fun search(query: String): List<SearchResponse> =
        paginatedFetch(
            baseUrl = "$mainUrl/explore/?q=$query",
            maxPages = 3,
            fetchDoc = { app.get(it).document },
            extractItems = { doc -> doc.select(".postBlock").mapNotNull { it.toSearchResponse() } }
        )

    // ======= LOAD =======
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        return if (Regex(".*/(movie|masrahiya)/").containsMatchIn(url)) {
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

        val actors = doc.select("div.cast_list .cast_item, .story div a").mapNotNull {
            val imgTag = it.selectFirst("img")
            val name = imgTag?.attr("alt") ?: it.text()
            val image = imgTag?.extractPoster()
            val roleString = it.selectFirst("span")?.text() ?: ""
            ActorData(actor = Actor(name, image), roleString = roleString)
        }

        val recommendations = doc.select(".movies_small .postBlock, .movies_small .postBlockCol, .related .postBlock")
            .mapNotNull { it.toSearchResponse() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.year = year
            this.recommendations = recommendations
            this.plot = synopsis
            this.tags = tags
            this.actors = actors
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
        val seasonLinks = doc.select(".h_scroll a, a:contains(الموسم)").toList().filter {
            it.parents().none { p -> p.hasClass("related") || p.hasClass("movies_small") }
        }.map { it.attr("href") }.distinct()

        val processEpisodeLinks: (List<Element>, Int?, String?) -> Unit = { episodeLinks, season, defaultPoster ->
            val thumbMap = episodeLinks.associate { ep -> ep.attr("href") to (ep.selectFirst("img")?.extractPoster() ?: defaultPoster) }
            episodeLinks.forEach { epLink ->
                val href = epLink.attr("href")
                val ep = Regex("""(ep|الحلقة)[^\d]*(\d+)""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = ep?.let { "الحلقة $it" } ?: epLink.text()
                    this.season = season ?: 1
                    this.episode = ep
                    this.posterUrl = thumbMap[href]
                })
            }
        }

        if (seasonLinks.isNotEmpty()) {
            seasonLinks.forEach { seasonUrl ->
                val d = app.get(fixUrl(seasonUrl)).document
                val seasonNum = Regex("""(season|الموسم)[^\d]*(\d+)""").find(seasonUrl)?.groupValues?.get(2)?.toIntOrNull()
                val episodeLinks = d.select(".all-episodes a").ifEmpty {
                    d.select("a:contains(الحلقة)").toList().filter { el ->
                        el.parents().none { p -> p.hasClass("slider") || p.hasClass("owl-carousel") || p.hasClass("related") || p.hasClass("movies_small") }
                    }
                }
                processEpisodeLinks(episodeLinks, seasonNum, posterUrl)
            }
        } else {
            val episodeLinks = doc.select(".all-episodes a").ifEmpty {
                doc.select("a:contains(الحلقة)").toList().filter { el ->
                    el.parents().none { p -> p.hasClass("slider") || p.hasClass("owl-carousel") || p.hasClass("related") || p.hasClass("movies_small") }
                }
            }
            processEpisodeLinks(episodeLinks, 1, posterUrl)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))) {
            this.posterUrl = posterUrl
            this.tags = tags
            this.year = year
            this.plot = synopsis
            this.actors = actors
        }
    }

    // ======= LOAD LINKS =======
    @TargetApi(Build.VERSION_CODES.O)
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        doc.select("ul#watch-servers-list li, .servList li").mapNotNull {
            Regex("loadIframe\\(this, '(.*?)'\\)").find(it.attr("onclick"))?.groupValues?.getOrNull(1)
        }.forEach { loadExtractor(fixUrl(it), subtitleCallback, callback) }

        doc.select("iframe#videoPlayer").attr("src").takeIf { it.isNotBlank() }?.let { loadExtractor(fixUrl(it), subtitleCallback, callback) }
        return true
    }
}
