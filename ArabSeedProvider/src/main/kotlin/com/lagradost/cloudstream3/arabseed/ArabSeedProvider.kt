package com.lagradost.cloudstream3.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLEncoder

class ArabSeedProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://arabseed.trade"
    override var name = "ArabSeed"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val cfKiller = CloudflareKiller()
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val adDomains = listOf(
        "adsterra", "clickadu", "adfox", "adcash", "shorten", "ads", "popads", "propeller",
        "ouo.io", "linkvertise", "adf.ly", "exe.io", "gplinks", "traffic", "banner",
        "sponsor", "syndication", "directadvert", "mgid", "exoclick", "juicyads",
        "doubleclick", "googleads", "googlesyndication", "adnxs", "smartadserver",
        "topcreativeformat", "alugha", "creativecdn", "adservice"
    )

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return adDomains.any { lower.contains(it) } || lower.contains("/ad/") || lower.contains("/ads/")
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a") ?: return null
        val title = a.attr("title").ifBlank { selectFirst("h3, h2, .title")?.text() ?: "" }.trim()
        if (title.isEmpty()) return null

        val posterUrl = selectFirst("img.images__loader, img")?.let {
            it.attr("data-src").ifEmpty {
                it.attr("src")
            }
        }

        val href = fixUrl(a.attr("href"))
        val tvType = when {
            href.contains("/series/") || href.contains("مسلسل") -> TvType.TvSeries
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
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "افلام اجنبي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "افلام عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات اجنبي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a/" to "مسلسلات عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "مسلسلات تركيه",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "افلام انمي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "مسلسلات انمي",
        "$mainUrl/category/%d8%b9%d8%b1%d9%88%d8%b6-%d9%85%d8%b5%d8%a7%d8%b1%d8%b9%d8%a9/" to "مصارعه",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document

        val home = document.select("a.movie__block, .Movie--Block, .Block--Item").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("a.movie__block, .Movie--Block, .Block--Item").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    private fun extractToken(html: String): String? {
        return Regex("csrf__token['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1)
    }

    private fun extractPostId(html: String): String? {
        return Regex("post_id['\"]?\\s*:\\s*['\"]?(\\d+)").find(html)?.groupValues?.get(1)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document
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
                            headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
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
                        val href = fixUrl(a.attr("href"))
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
        val doc = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        val watchUrl = doc.select(".watch__btn, a.watch__btn, a[href*='/watch/']").attr("href")
        if (watchUrl.isEmpty()) return false

        val fullWatchUrl = fixUrl(watchUrl)
        val watchDoc = app.get(fullWatchUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).document
        val watchHtml = watchDoc.toString()

        val postId = extractPostId(watchHtml)
        val csrfToken = extractToken(watchHtml)

        val qualities = listOf("480", "720", "1080")

        qualities.forEach { quality ->
            val serversHtml = if (quality == "480") {
                watchDoc.selectFirst(".servers__list ul, .servers-list ul")?.html()
                    ?: (if (postId != null && csrfToken != null) fetchQualityServers(postId, quality, csrfToken, fullWatchUrl) else "")
            } else if (postId != null && csrfToken != null) {
                fetchQualityServers(postId, quality, csrfToken, fullWatchUrl)
            } else {
                ""
            }

            if (serversHtml.isNotEmpty()) {
                val serverDoc = Jsoup.parse(serversHtml)
                for (li in serverDoc.select("li[data-src], li[data-url]")) {
                    val src = li.attr("data-src").ifBlank { li.attr("data-url") }
                    processServerLink(src, fullWatchUrl, subtitleCallback, callback)
                }
            }
        }

        // Direct iframes in watch page
        watchDoc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            processServerLink(src, fullWatchUrl, subtitleCallback, callback)
        }

        val downloadUrl = doc.select(".download__btn, a.download__btn, a[href*='/download/']").attr("href")
        if (downloadUrl.isNotEmpty()) {
            try {
                val fullDownloadUrl = fixUrl(downloadUrl)
                val downloadDoc = app.get(fullDownloadUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).document
                downloadDoc.select(".downloads__links__list li a").forEach { a ->
                    var link = a.attr("href")
                    if (link.contains("/download/?download_url=")) {
                        link = link.substringAfter("download_url=").substringBefore("&")
                    }
                    if (link.contains("%3A%2F%2F") || link.contains("%2F")) {
                        link = runCatching { java.net.URLDecoder.decode(link, "UTF-8") }.getOrDefault(link)
                    }
                    if (link.isNotEmpty() && !isAdUrl(link)) {
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
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
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
        if (finalUrl.contains("%3A%2F%2F") || finalUrl.contains("%2F")) {
            finalUrl = runCatching { java.net.URLDecoder.decode(finalUrl, "UTF-8") }.getOrDefault(finalUrl)
        }
        if (finalUrl.startsWith("//")) {
            finalUrl = "https:$finalUrl"
        } else if (finalUrl.startsWith("/")) {
            finalUrl = mainUrl + finalUrl
        }
        if (finalUrl.isNotEmpty() && !isAdUrl(finalUrl)) {
            loadExtractor(finalUrl, referer, subtitleCallback, callback)
        }
    }
}
