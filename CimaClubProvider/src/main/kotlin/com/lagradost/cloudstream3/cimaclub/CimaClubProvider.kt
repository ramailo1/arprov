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
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "ar,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات",
    )

    // =======================
    // HELPERS
    // =======================

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("الحلقة\\s*\\d+"), "")
            .replace(Regex("الموسم\\s*\\d+"), "")
            .replace(Regex("الوسم\\s*\\d+"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun detectTvType(category: String, url: String, title: String = ""): TvType {
        return when {
            category.contains("انمي") || title.contains("انمي") || url.contains("anime") -> TvType.Anime
            url.contains("/series/") || title.contains("مسلسل") || url.contains("الحلقة") -> TvType.TvSeries
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

    private fun extractLastNumber(text: String): Int? {
        return Regex("(\\d+)")
            .findAll(text)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()
    }

    // =======================
    // MAIN PAGE
    // =======================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data

        val url = when {
            baseUrl == mainUrl -> if (page == 1) baseUrl else "$mainUrl/page/$page/"
            baseUrl.contains("/movies") -> if (page == 1) baseUrl else "$baseUrl/page/$page/"
            baseUrl.contains("/series") || baseUrl.contains("/anime") -> if (page == 1) baseUrl else "$baseUrl?offset=$page"
            else -> baseUrl
        }

        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = doc.select("#MainFiltar .Small--Box")
        val home = items.mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val link = fixUrl(a.attr("href"))

        val rawTitle = selectFirst("inner--title h2")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val category = selectFirst(".category")?.text() ?: ""
        val type = detectTvType(category, link, rawTitle)

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
            TvType.Anime -> newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
            else -> newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        }
    }

    // =======================
    // SEARCH
    // =======================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = headers, timeout = 30).document
        return doc.select(".Small--Box").mapNotNull { it.toSearchResponse() }
    }

    // =======================
    // LOAD PAGE
    // =======================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers, timeout = 30).document

        val rawTitle = doc.selectFirst("h1")?.text() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".Poster img, .image img")?.attr("src")
        val description = doc.selectFirst(".Story, .StoryArea")?.text()?.trim()
        val category = doc.selectFirst(".category")?.text() ?: ""

        val episodeElements = doc.select(".allepcont a, .Episodes-List .Small--Box, .BlocksHolder .Small--Box")

        val tvType = detectTvType(category, url, rawTitle)
        val isSeries = tvType == TvType.TvSeries || tvType == TvType.Anime || episodeElements.isNotEmpty()

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val a = ep.selectFirst("a") ?: ep
                val epUrl = fixUrl(a.attr("href"))

                val name = if (ep.tagName() == "a") {
                    ep.text().ifBlank { ep.attr("title") }
                } else {
                    ep.selectFirst("h2")?.text() ?: a.attr("title")
                }

                val number = extractEpisodeNumber(name) ?: extractLastNumber(name)

                newEpisode(epUrl) {
                    this.name = name
                    this.episode = number
                }
            }.distinctBy { it.data }

            // 🔹 Key Fix: Play clicked episode if available
            val clickedEpisode = episodes.find { it.data == url } ?: episodes.firstOrNull()
            val playUrl = clickedEpisode?.data ?: url

            return newTvSeriesLoadResponse(
                title,
                url,
                tvType,
                episodes.sortedBy { it.episode ?: 0 }
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

    // =======================
    // LINK EXTRACTOR
    // =======================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl = if (data.contains("/watch")) data else "${data.trimEnd('/')}/watch/"

        val doc = try {
            app.get(watchUrl, headers = headers, timeout = 30).document
        } catch (_: Exception) {
            app.get(data, headers = headers, timeout = 30).document
        }

        val loaded = HashSet<String>()

        suspend fun safeLoad(url: String) {
            if (url.isBlank()) return
            if (!loaded.add(url)) return
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }

        // iframe servers
        for (iframe in doc.select("iframe")) {
            safeLoad(fixUrl(iframe.attr("src")))
        }

        // JS / data servers
        for (element in doc.select("[data-embed],[data-url]")) {
            safeLoad(fixUrl(element.attr("data-embed").ifBlank { element.attr("data-url") }))
        }

        // anchor servers
        for (a in doc.select(".Servers-List a")) {
            safeLoad(fixUrl(a.attr("href")))
        }

        // download links → direct video
        for (a in doc.select(".Download-Links a")) {
            val href = fixUrl(a.attr("href"))
            val quality = a.text()

            callback(
                newExtractorLink(
                    name,
                    "CimaClub Direct",
                    href,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQualityFromName(quality)
                }
            )
        }

        return true
    }

    private fun getQualityFromName(text: String): Int {
        return when {
            text.contains("1080") -> 1080
            text.contains("720") -> 720
            text.contains("480") -> 480
            else -> Qualities.Unknown.value
        }
    }
}