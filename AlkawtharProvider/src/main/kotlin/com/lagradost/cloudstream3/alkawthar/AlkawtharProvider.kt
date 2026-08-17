@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.alkawthar

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AlkawtharProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.alkawthartv.ir"
    override var name = "Alkawthar (قناة الكوثر)"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        const val TAG = "AlkawtharProvider"
    }

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
        res = res.replace(Regex("""^[\s\-–—:،,"']+|[\s\-–—:،,"']+$"""), "").trim()
        return res
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainUrl$url"
            url.isNotEmpty() -> "$mainUrl/$url"
            else -> ""
        }
    }

    private fun extractPoster(doc: Document): String {
        // Priority 1: og:image meta tag
        doc.selectFirst("meta[property=og:image], meta[property=og:image:secure_url]")?.attr("content")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // Priority 2: twitter:image
        doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // Priority 3: JSON-LD thumbnailUrl
        Regex(""""thumbnailUrl"\s*:\s*"([^"]+)""").find(doc.html())?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // Priority 4: link[rel=image_src]
        doc.selectFirst("link[rel=image_src]")?.attr("href")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        // Priority 5: video poster
        doc.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.isNotEmpty() }
            ?.let { return fixUrl(it) }
        return ""
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

    /** Crawl all episodes from a category URL (supports multiple pages). */
    private suspend fun crawlCategoryEpisodes(
        baseCatUrl: String,
        requestHeaders: Map<String, String>
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        var currentPage = 1
        val maxPages = 10

        Log.d(TAG, "crawlCategoryEpisodes: starting for $baseCatUrl")

        while (currentPage <= maxPages) {
            val pageUrl = "$baseCatUrl/$currentPage"
            Log.d(TAG, "crawlCategoryEpisodes: fetching page $currentPage -> $pageUrl")
            val pageDoc = try {
                app.get(pageUrl, headers = requestHeaders).document
            } catch (e: Exception) {
                Log.e(TAG, "crawlCategoryEpisodes: error fetching $pageUrl", e)
                break
            }

            val pageLinks = pageDoc.select("a[href*='/news/']").toList()
            Log.d(TAG, "crawlCategoryEpisodes: page $currentPage found ${pageLinks.size} links")
            if (pageLinks.isEmpty()) break

            var newAdded = 0
            pageLinks.forEach { link ->
                val epUrl = link.attr("abs:href").ifEmpty { fixUrl(link.attr("href")) }
                if (epUrl.isEmpty() || episodes.any { it.data == epUrl }) return@forEach

                val epText = (link.selectFirst("h2, h3, h4, .news-title, .title")?.text() ?: link.text()).trim()
                if (epText.length < 2) return@forEach

                val epImg = link.selectFirst("img")?.let {
                    val src = it.attr("src").ifEmpty { it.attr("data-src") }
                    fixUrl(src).takeIf { s -> s.startsWith("http") } ?: ""
                }

                val epNum = parseEpisodeNumber(epText, epUrl) ?: (episodes.size + 1)
                Log.d(TAG, "crawlCategoryEpisodes: adding ep $epNum - $epText -> $epUrl")
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epText
                        this.episode = epNum
                        if (!epImg.isNullOrEmpty()) this.posterUrl = epImg
                    }
                )
                newAdded++
            }

            Log.d(TAG, "crawlCategoryEpisodes: page $currentPage added $newAdded new episodes (total: ${episodes.size})")
            if (newAdded == 0) break
            currentPage++
        }

        Log.d(TAG, "crawlCategoryEpisodes: done, total episodes = ${episodes.size}")
        return episodes
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = link.attr("abs:href").ifEmpty {
            val rel = link.attr("href")
            if (rel.startsWith("http")) rel else "$mainUrl$rel"
        }
        if (!href.matches(Regex(""".*/(?:news|category)/\d+.*"""))) return null

        val rawText = (link.selectFirst("h1, h2, h3, h4, .title, .news-title, .text, strong")?.text() ?: link.text()).trim()
        if (rawText.length < 3) return null

        val title = rawText.cleanTitle().ifEmpty { rawText }
        val posterUrl = link.selectFirst("img")?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            fixUrl(src).takeIf { s -> s.startsWith("http") } ?: ""
        } ?: ""

        val isCategory = href.contains("/category/")
        val isMovie = href.contains("/news/") && (rawText.contains("فيلم") || rawText.contains("الفيلم") || rawText.contains("وثائقي"))

        return when {
            isCategory -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            isMovie -> newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
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

        // === Case 1: Category URL (series/episode catalog page) ===
        if (url.contains("/category/")) {
            Log.d(TAG, "load: Case 1 - category URL: $url")
            val rawTitle = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.title().substringBefore("|").trim()
            val title = rawTitle.cleanTitle().ifEmpty { rawTitle }

            val posterUrl = extractPoster(doc)
            val baseUrlWithoutPage = url.replace(Regex("""/\d+$"""), "")
            val episodes = crawlCategoryEpisodes(baseUrlWithoutPage, requestHeaders)
            val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.episode ?: 1 }))
            val finalPoster = posterUrl.ifEmpty { sortedEpisodes.firstOrNull()?.posterUrl ?: "" }

            Log.d(TAG, "load: Case 1 - returning ${sortedEpisodes.size} episodes for '$title'")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = finalPoster
            }
        }

        // === Case 2: Article / Series overview URL (/news/{id}) ===
        val rawTitle = doc.selectFirst("h1, .news-title, .headTitle")?.text()?.trim()
            ?: doc.title().substringBefore("|").trim()
        val title = rawTitle.cleanTitle().ifEmpty { rawTitle }
        val posterUrl = extractPoster(doc)
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst(".body, .content, .description, p")?.text()?.trim() ?: ""

        Log.d(TAG, "load: Case 2 - news/epi URL: $url")
        Log.d(TAG, "load: rawTitle = '$rawTitle'")

        // Universal sub-category detection: find any category link in doc that isn't the main top categories
        val excludedCats = setOf("625", "627", "628", "629")
        val subCategoryId = Regex("""/category/(\d+)""").findAll(doc.html())
            .mapNotNull { it.groupValues[1] }
            .firstOrNull { it !in excludedCats }

        Log.d(TAG, "load: subCategoryId found = $subCategoryId")

        if (subCategoryId != null) {
            val episodes = crawlCategoryEpisodes("$mainUrl/category/$subCategoryId", requestHeaders)
            if (episodes.isNotEmpty()) {
                val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.episode ?: 1 }))
                val finalPoster = posterUrl.ifEmpty { sortedEpisodes.firstOrNull()?.posterUrl ?: "" }

                Log.d(TAG, "load: Case 2 via sub-category $subCategoryId - returning ${sortedEpisodes.size} episodes")
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                    this.posterUrl = finalPoster
                    this.plot = plot
                }
            }
        }


        // === Case 3: Single episode or movie ===
        Log.d(TAG, "load: Case 3 - single episode/movie. isMovie check on '$rawTitle'")
        val isMovie = rawTitle.contains("فيلم") || rawTitle.contains("الفيلم") || rawTitle.contains("وثائقي")
        return if (isMovie) {
            Log.d(TAG, "load: returning as Movie")
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            val epNum = parseEpisodeNumber(rawTitle) ?: 1
            Log.d(TAG, "load: returning as single TvSeries episode, epNum=$epNum")
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                listOf(newEpisode(url) {
                    this.name = rawTitle
                    this.episode = epNum
                    this.posterUrl = posterUrl
                })
            ) {
                this.posterUrl = posterUrl
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

        // 1. JSON-LD contentUrl (most reliable — server-set direct mp4)
        val jsonLdUrl = Regex(""""contentUrl"\s*:\s*"([^"]+)""").find(html)?.groupValues?.get(1)
        if (!jsonLdUrl.isNullOrEmpty() && jsonLdUrl.startsWith("http")) {
            callback(newExtractorLink(name, name, jsonLdUrl) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P720.value
            })
            return true
        }

        // 2. og:video meta tag
        val ogVideo = doc.selectFirst("meta[property=og:video], meta[property=og:video:secure_url]")?.attr("content")
        if (!ogVideo.isNullOrEmpty() && ogVideo.startsWith("http")) {
            callback(newExtractorLink(name, name, ogVideo) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P720.value
            })
            return true
        }

        // 3. Direct video/source tag or mp4 pattern in HTML
        val videoUrl = doc.selectFirst("video[src]")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("source[src]")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: Regex("""https?://[^\s"']+\.mp4[^\s"']*""", RegexOption.IGNORE_CASE).find(html)?.value

        if (!videoUrl.isNullOrEmpty()) {
            val fullVideoUrl = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl$videoUrl"
            callback(newExtractorLink(name, name, fullVideoUrl) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P720.value
            })
            return true
        }

        return false
    }
}
