package com.lagradost.cloudstream3.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLEncoder

class ArabSeedProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://arabseed.store"
    override var name = "ArabSeed"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = attr("title").ifEmpty { select("h3").text() }
        if (title.isEmpty()) return null

        val posterUrl = select("img.images__loader").let {
            it.attr("data-src").ifEmpty {
                it.attr("src")
            }
        }

        val href = attr("href")
        val tvType = when {
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(
            title,
            href,
            tvType,
        ) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recently/" to "المضاف حديثا",
        "$mainUrl/category/الافلام/افلام-اجنبي/" to "افلام اجنبي",
        "$mainUrl/category/الافلام/افلام-عربي/" to "افلام عربي",
        "$mainUrl/category/الافلام/افلام-netflix/" to "افلام Netfilx",
        "$mainUrl/category/المسلسلات/مسلسلات-اجنبي/" to "مسلسلات اجنبي",
        "$mainUrl/category/المسلسلات/مسلسلات-عربي/" to "مسلسلات عربي",
        "$mainUrl/category/المسلسلات/مسلسلات-تركيه/" to "مسلسلات تركيه",
        "$mainUrl/category/المسلسلات/مسلسلات-netflix/" to "مسلسلات Netfilx",
        "$mainUrl/category/wwe-shows/" to "مصارعه",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, timeout = 120).document

        val home = document.select("a.movie__block").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("a.movie__block").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun extractToken(html: String): String? {
        return Regex("csrf__token['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1)
    }

    private fun extractPostId(html: String): String? {
        return Regex("post_id['\"]?\\s*:\\s*['\"]?(\\d+)").find(html)?.groupValues?.get(1)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val html = doc.toString()
        val title = doc.selectFirst(".post__name")?.text()?.cleanTitle() ?: doc.select("h1").text()

        val seasonTabs = doc.select("#seasons__list li[data-season]")
        val isMovie = seasonTabs.isEmpty()

        val posterUrl = doc.selectFirst(".images__loader img")?.attr("data-src")
            ?: doc.selectFirst(".poster__single img")?.attr("src")
            ?: doc.selectFirst(".poster__single img")?.attr("data-src")
            ?: doc.selectFirst(".poster img")?.attr("data-src")
            ?: doc.selectFirst(".single__poster img")?.attr("data-src")
            ?: doc.selectFirst("img[data-src]")?.attr("data-src")
            ?: doc.selectFirst(".images__loader img")?.attr("src")

        val synopsis = doc.select(".single__contents").text()
            .replace("قصة العرض :", "")
            .replace("قصة العرض", "")
            .trim()

        val year = doc.select("a[href*='/release-year/']").text().getIntFromText()
        val tags = doc.select("a[href*='/genre/']").map { it.text() }

        val actors = doc.select("a[href*='/actor/']").mapNotNull {
            val name = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val image = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")
            val roleString = it.selectFirst("span")?.text()
            val mainActor = Actor(name, image)
            ActorData(actor = mainActor, roleString = roleString)
        }

        val recommendations = doc.select("a.movie__block").mapNotNull {
            it.toSearchResponse()
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
                this.year = year
            }
        } else {
            val csrfToken = extractToken(html)
            val seriesId = seasonTabs.firstOrNull()?.attr("data-series")
                ?: doc.selectFirst("#seasons__list li")?.attr("data-series")

            val episodes = arrayListOf<Episode>()
            val defaultHtml = doc.selectFirst(".episodes__list")?.html() ?: ""

            seasonTabs.forEachIndexed { index, tab ->
                val seasonId = tab.attr("data-season")
                val seasonNumber = index + 1
                val isSelected = tab.hasClass("selected")

                val episodesHtml = if (isSelected && defaultHtml.isNotEmpty()) {
                    defaultHtml
                } else if (seriesId != null && csrfToken != null) {
                    try {
                        val resp = app.post(
                            "$mainUrl/season__episodes/",
                            data = mapOf(
                                "series_id" to seriesId,
                                "season_id" to seasonId,
                                "csrf_token" to csrfToken
                            ),
                            headers = mapOf("Referer" to url)
                        )
                        JSONObject(resp.text).optString("html", "")
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }

                if (episodesHtml.isNotEmpty()) {
                    val episodeDoc = Jsoup.parse(episodesHtml)
                    episodeDoc.select("a[href]").forEach { a ->
                        val href = a.attr("href")
                        if (href.contains("الحلقة", ignoreCase = true)
                            || href.contains("%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9", ignoreCase = true)
                        ) {
                            val episodeName = a.text()
                            val episodeNumber = a.selectFirst("b")?.text()?.toIntOrNull()
                                ?: episodeName.getIntFromText()
                            episodes.add(
                                newEpisode(href) {
                                    this.name = episodeName
                                    this.episode = episodeNumber
                                    this.season = seasonNumber
                                }
                            )
                        }
                    }
                }
            }

            val sortedEpisodes = episodes.distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.actors = actors
                this.recommendations = recommendations
                this.year = year
            }
        }
    }

    private fun String.cleanTitle(): String {
        return this.replace(")", "")
            .replace("(", "")
            .replace("مشاهدة", "")
            .replace("تحميل", "")
            .replace("فيلم", "")
            .replace("مسلسل", "")
            .replace("مترجم", "")
            .replace("اون لاين", "")
            .trim()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val watchUrl = doc.select(".watch__btn").attr("href")
        if (watchUrl.isEmpty()) return false

        val watchDoc = app.get(watchUrl, headers = mapOf("Referer" to data)).document
        val watchHtml = watchDoc.toString()

        val postId = extractPostId(watchHtml)
        val csrfToken = extractToken(watchHtml)

        val qualities = listOf("480", "720", "1080")

        qualities.forEach { quality ->
            val serversHtml = if (quality == "480") {
                watchDoc.selectFirst(".servers__list ul")?.html()
                    ?: (if (postId != null && csrfToken != null) fetchQualityServers(postId, quality, csrfToken, watchUrl) else "")
            } else if (postId != null && csrfToken != null) {
                fetchQualityServers(postId, quality, csrfToken, watchUrl)
            } else {
                ""
            }

            if (serversHtml.isNotEmpty()) {
                val serverDoc = Jsoup.parse(serversHtml)
                for (li in serverDoc.select("li[data-src]")) {
                    val src = li.attr("data-src")
                    processServerLink(src, data, subtitleCallback, callback)
                }
            }
        }

        val downloadUrl = doc.select(".download__btn").attr("href")
        if (downloadUrl.isNotEmpty()) {
            try {
                val downloadDoc = app.get(downloadUrl, headers = mapOf("Referer" to data)).document
                downloadDoc.select(".downloads__links__list li a").forEach { a ->
                    var link = a.attr("href")
                    if (link.contains("/download/?download_url=")) {
                        link = link.substringAfter("download_url=").substringBefore("&")
                    }
                    if (link.isNotEmpty()) {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // ignore download errors
            }
        }

        return true
    }

    private suspend fun fetchQualityServers(
        postId: String,
        quality: String,
        csrfToken: String,
        referer: String
    ): String {
        return try {
            val resp = app.post(
                "$mainUrl/get__quality__servers/",
                data = mapOf(
                    "post_id" to postId,
                    "quality" to quality,
                    "csrf_token" to csrfToken
                ),
                headers = mapOf("Referer" to referer)
            )
            JSONObject(resp.text).optString("html", "")
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun processServerLink(
        src: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (src.isEmpty()) return
        var finalUrl = src
        if (finalUrl.contains("/watch/?url=")) {
            finalUrl = finalUrl.substringAfter("url=")
        }
        if (finalUrl.startsWith("//")) {
            finalUrl = "https:$finalUrl"
        } else if (finalUrl.startsWith("/")) {
            finalUrl = mainUrl + finalUrl
        }
        if (finalUrl.isNotEmpty()) {
            loadExtractor(finalUrl, referer, subtitleCallback, callback)
        }
    }
}
