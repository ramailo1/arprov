package com.lagradost.cloudstream3.ristoanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

class RistoAnimeProvider : MainAPI() {
    override var mainUrl = "https://ristoanime.org"
    override var name = "RistoAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
    )

    private val baseHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
    )

    private fun headers() = baseHeaders + mapOf("User-Agent" to userAgents.random())
    private suspend fun politeDelay(extraMs: Long = 0) = delay((1200L..2600L).random() + extraMs)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "المضاف حديثاََ",
        "$mainUrl/movies/" to "افلام انمي",
        "$mainUrl/time/" to "مواعيد الحلقات"
    )

    private fun buildPagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return when {
            base == "$mainUrl/" -> "$mainUrl/?page=$page/"
            base.contains("/movies/") -> "${base}page/$page/"
            else -> "$base?page=$page"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val doc = app.get(url, headers = headers()).document
        politeDelay()

        val items = doc.select(".MovieItem, article, .item, .video-item, .film-item")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.extractPoster(): String? {
        val posterElement = this.selectFirst(".poster")
        return posterElement?.attr("data-style")?.let { style ->
            Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)
        } ?: posterElement?.attr("style")?.let { style ->
            Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)
        } ?: this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val ignorePaths = listOf("/privacy", "/dmca", "/contactus", "/genre/", "/category/",
            "/quality/", "/release-year/", "/country/", "/language/", "/time", "/search")
        if (ignorePaths.any { href.contains(it, ignoreCase = true) }) return null

        val title = link.selectFirst("h4, .title p")?.text()?.trim() ?: link.ownText().trim()
        if (title.length < 3) return null

        val poster = fixUrlNull(this.extractPoster())

        val isMovie = href.contains("فيلم", ignoreCase = true) || href.contains("%d9%81%d9%8a%d9%84%d9%85", ignoreCase = true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.trim().replace(" ", "+")}"
        val doc = app.get(url, headers = headers()).document
        politeDelay()

        return doc.select(".MovieItem, article").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.removeSuffix("/watch").removeSuffix("/download")
        val doc = app.get(cleanUrl, headers = headers()).document
        politeDelay()

        val title = doc.selectFirst("h1,.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.extractPoster())
        val description = doc.selectFirst(".description,.plot,.summary,.content")?.text()?.trim()
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        val isSeries = cleanUrl.contains("/series/") || doc.select(".EpisodesList").isNotEmpty()
        val type = if (isSeries) TvType.Anime else TvType.AnimeMovie

        return if (isSeries) {
            val episodes = doc.select(".EpisodesList a")
                .mapNotNull { a ->
                    val epUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val epNum = a.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                    newEpisode(epUrl) {
                        this.name = if (epNum != null) "الحلقة $epNum" else a.text()
                        this.episode = epNum
                    }
                }
                .distinctBy { it.data }

            newAnimeLoadResponse(title, cleanUrl, type) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes.ifEmpty { 
                    listOf(newEpisode(cleanUrl) { this.name = title; this.episode = 1 }) 
                })
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, type, cleanUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postUrl = data.removeSuffix("/watch").removeSuffix("/download")
        val postDoc = app.get(postUrl, headers = headers()).document
        politeDelay(400)

        val watchUrl = fixUrlNull(postDoc.selectFirst("a:contains(المشاهدة الان)")?.attr("href"))
        val downloadUrl = fixUrlNull(postDoc.selectFirst("a:contains(التحميل الان)")?.attr("href"))

        listOfNotNull(watchUrl, downloadUrl).distinct().forEach { actionUrl ->
            try {
                val actionDoc = app.get(actionUrl, headers = headers(), referer = postUrl).document
                politeDelay(250)

                // Extract iframes and direct links from data attributes
                actionDoc.select("li[data-watch], button[data-watch], a[data-watch], li[data-link], [data-src]").forEach { el ->
                    val url = fixUrlNull(el.attr("data-watch").ifBlank { el.attr("data-link") }.ifBlank { el.attr("data-src") })
                    if (!url.isNullOrBlank()) loadExtractor(url, actionUrl, subtitleCallback, callback)
                }

                actionDoc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) loadExtractor(src, actionUrl, subtitleCallback, callback)
                }

                // Extract direct video links from tags
                actionDoc.select("a[href], source[src], video source").forEach { el ->
                    val link = fixUrlNull(el.attr("href").ifBlank { el.attr("src") }) ?: return@forEach
                    val isM3u8 = link.contains(".m3u8", ignoreCase = true)
                    if (isM3u8 || link.contains(".mp4", ignoreCase = true) || link.contains(".mkv", ignoreCase = true)) {
                        callback(
                            newExtractorLink(
                                source = "RistoAnime",
                                name = "RistoAnime - ${if (isM3u8) "HLS" else "Direct"}",
                                url = link,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = actionUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }

                // Extract download service buttons (ODOJO, CUTVID, etc.)
                if (actionUrl == downloadUrl) {
                    actionDoc.select("a[href]:not([href*='$mainUrl'])").forEach { a ->
                        val link = fixUrlNull(a.attr("href")) ?: return@forEach
                        if (link.startsWith("http") && a.text().trim().isNotEmpty()) {
                            try {
                                loadExtractor(link, actionUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                // Ignore extractor errors
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue with next link
            }
        }

        return true
    }
}
