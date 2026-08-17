@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.alkawthar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class AlkawtharProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.alkawthartv.ir"
    override var name = "قناة الكوثر"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun String.cleanTitle(): String {
        var res = this.replace(
            Regex("""(?i)\| قناة الکوثر الفضائية|قناة الکوثر الفضائية|قناة الکوثر|الكوثر|الفيلم الايراني|الفيلم السينمائي|الفيلم الوثائقي|الفيلم الروائي|الفيلم|مسلسل|فيلم""")
            , ""
        ).trim()
        res = res.replace(Regex("""^[\s\-–—:،,\"']+|[\s\-–—:،,\"']+$"""), "").trim()
        return res
    }

    private fun parseEpisodeNumber(text: String, url: String? = null): Int? {
        val epMatch = Regex("""(?:الحلقة|حلقة|الحلقه|حلقه|قسمت)\s*[:\-]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(text)
        if (epMatch != null) return epMatch.groupValues[1].toIntOrNull()

        val urlMatch = (url ?: "").let {
            Regex("""[-_](\d{1,4})(?:$|/)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (urlMatch != null && urlMatch in 1..999) return urlMatch

        return Regex("""\b(\d{1,3})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = link.attr("abs:href").ifEmpty {
            val rel = link.attr("href")
            if (rel.startsWith("http")) rel else "$mainUrl$rel"
        }
        if (href.isEmpty() || (!href.contains("/news/") && !href.contains("/category/"))) return null

        val rawText = (link.selectFirst("h1, h2, h3, h4, .title, .text, strong")?.text() ?: link.text()).trim()
        if (rawText.length < 3) return null

        val title = rawText.cleanTitle().ifEmpty { rawText }
        val posterUrl = link.selectFirst("img")?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.startsWith("http")) src else if (src.isNotEmpty()) "$mainUrl$src" else ""
        } ?: ""

        val isCategory = href.contains("/category/")
        val isMovie = href.contains("/news/") && (rawText.contains("فيلم") || rawText.contains("الفيلم") || rawText.contains("وثائقي"))

        return when {
            isCategory -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
            isMovie -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
            else -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/629/" to "مسلسلات",
        "$mainUrl/category/628/" to "الافلام",
        "$mainUrl/category/634/" to "يوسف الصديق",
        "$mainUrl/category/671/" to "مسلسل مريم المقدسة",
        "$mainUrl/category/630/" to "مسلسل المتستر",
        "$mainUrl/category/626/" to "مسلسل جابر بن حيان",
        "$mainUrl/category/632/" to "هم الخالدون",
        "$mainUrl/category/627/" to "وثائقيات",
        "$mainUrl/category/625/" to "برامج"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = userAgents.random()

        val url = "${request.data}$page"
        val doc = app.get(url, headers = requestHeaders).document

        val items = doc.select("a[href*='/news/'], a[href*='/category/']").toList().mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = userAgents.random()

        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, headers = requestHeaders).document

        return doc.select("a[href*='/news/'], a[href*='/category/']").toList().mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = userAgents.random()

        val doc = app.get(url, headers = requestHeaders).document

        // Case 1: Category URL (Series with multi-page episode catalog)
        if (url.contains("/category/")) {
            val rawTitle = doc.title().substringBefore("|").trim()
            val title = rawTitle.cleanTitle().ifEmpty { rawTitle }

            val episodes = mutableListOf<Episode>()
            val baseUrlWithoutPage = url.replace(Regex("""/\d+$"""), "")
            var currentPage = 1
            val maxPages = 6

            while (currentPage <= maxPages) {
                val pageUrl = "$baseUrlWithoutPage/$currentPage"
                val pageDoc = if (currentPage == 1) doc else {
                    try {
                        app.get(pageUrl, headers = requestHeaders).document
                    } catch (e: Exception) {
                        null
                    }
                } ?: break

                val pageLinks = pageDoc.select("a[href*='/news/']").toList()
                if (pageLinks.isEmpty()) break

                var newAdded = 0
                pageLinks.forEach { link ->
                    val epUrl = link.attr("abs:href").ifEmpty { "$mainUrl${link.attr("href")}" }
                    val epText = link.text().trim()
                    if (epText.length >= 3 && !episodes.any { it.data == epUrl }) {
                        val epNum = parseEpisodeNumber(epText, epUrl) ?: (episodes.size + 1)
                        val epImg = link.selectFirst("img")?.attr("src")?.let {
                            if (it.startsWith("http")) it else "$mainUrl$it"
                        }

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epText.cleanTitle().ifEmpty { "الحلقة $epNum" }
                                this.episode = epNum
                                this.posterUrl = epImg
                            }
                        )
                        newAdded++
                    }
                }

                if (newAdded == 0) break
                currentPage++
            }

            val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.episode ?: 1 }))
            val posterUrl = sortedEpisodes.firstOrNull()?.posterUrl ?: ""

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                sortedEpisodes
            ) {
                this.posterUrl = posterUrl
            }
        }

        // Case 2: Article / Single Episode / Movie URL (/news/{id})
        val rawTitle = doc.selectFirst("h1, .title, .headTitle")?.text()?.trim()
            ?: doc.title().substringBefore("|").trim()
        val title = rawTitle.cleanTitle().ifEmpty { rawTitle }

        val posterUrl = doc.selectFirst("video")?.attr("poster")
            ?: doc.selectFirst(".image img, .news-image img, img[src*='alkawthartv.ir']")?.attr("src")
            ?: ""

        val plot = doc.selectFirst(".body, .content, .description, p")?.text()?.trim() ?: ""

        val isMovie = rawTitle.contains("فيلم") || rawTitle.contains("الفيلم") || rawTitle.contains("وثائقي")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = if (posterUrl.startsWith("http")) posterUrl else if (posterUrl.isNotEmpty()) "$mainUrl$posterUrl" else ""
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                        this.posterUrl = if (posterUrl.startsWith("http")) posterUrl else if (posterUrl.isNotEmpty()) "$mainUrl$posterUrl" else ""
                    }
                )
            ) {
                this.posterUrl = if (posterUrl.startsWith("http")) posterUrl else if (posterUrl.isNotEmpty()) "$mainUrl$posterUrl" else ""
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = userAgents.random()

        val doc = app.get(data, headers = requestHeaders).document
        val html = doc.html()

        // 1. Direct video or source tag
        val videoUrl = doc.selectFirst("video")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("source")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: Regex("""https?://[^\s"']+\.mp4[^\s"']*""", RegexOption.IGNORE_CASE).find(html)?.value

        if (!videoUrl.isNullOrEmpty()) {
            val fullVideoUrl = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl$videoUrl"
            callback(
                newExtractorLink(name, name, fullVideoUrl) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                }
            )
            return true
        }

        return false
    }
}
