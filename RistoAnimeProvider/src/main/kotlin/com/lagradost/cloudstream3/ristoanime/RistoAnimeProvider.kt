package com.lagradost.cloudstream3.ristoanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

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

        // Handle Redirection for episodes to series (Netflix UI)
        if (!cleanUrl.contains("/series/")) {
            val seriesUrl = fixUrlNull(
                doc.select("a").find { it.text().contains("لمشاهدة جميع الحلقات") }?.attr("href")
                ?: doc.select(".PostTitle a[href*='/series/']").attr("href")
                ?: doc.select(".EasyScrap-breadcrumbs a[href*='/series/']").lastOrNull()?.attr("href")
                ?: doc.select(".SingleContent a[href*='/series/']").firstOrNull()?.attr("href")
                ?: doc.select("a[href*='/series/']").find { it.text().contains("انمي") || it.text().contains("مسلسل") }?.attr("href")
            )
            if (seriesUrl != null && seriesUrl != cleanUrl && seriesUrl.contains("/series/")) {
                return load(seriesUrl)
            }
        }

        val title = doc.selectFirst("h1.PostTitle")?.text()?.trim()
            ?: doc.selectFirst(".PostTitle")?.text()?.trim()
            ?: doc.select("h1").lastOrNull()?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(doc.extractPoster())
        val description = doc.selectFirst(".StoryArea p")?.text()?.trim()
            ?: doc.selectFirst(".StoryArea")?.text()?.trim()
            ?: doc.selectFirst(".description, .plot, .summary")?.text()?.trim()
        
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        val isSeries = cleanUrl.contains("/series/") || doc.select(".EpisodesList").isNotEmpty()
        val type = if (isSeries) TvType.Anime else TvType.AnimeMovie

        return if (isSeries) {
            val dbEpisodes = mutableListOf<Episode>()
            val seasons = doc.select("[data-season]") // Robust selector
            
            if (seasons.isNotEmpty()) {
                val scripts = doc.select("script").joinToString("\n") { it.html() } // Scan all scripts
                val ajaxUrl = Regex("var AjaxtURL = \"(.*?)\";").find(scripts)?.groupValues?.get(1) 
                    ?: "https://ristoanime.org/wp-content/themes/TopAnime/Ajax/"
                val postId = Regex("post_id: '(\\d+)'").find(doc.html())?.groupValues?.get(1) 
                    ?: Regex("\"post_id\",\"(\\d+)\"").find(doc.html())?.groupValues?.get(1)

                if (postId != null) {
                    seasons.forEach { season ->
                        val seasonNum = season.attr("data-season").toIntOrNull() ?: 1
                        try {
                            val response = app.post(
                                "${ajaxUrl}Single/Episodes.php",
                                data = mapOf("season" to "$seasonNum", "post_id" to postId),
                                headers = headers() + mapOf("X-Requested-With" to "XMLHttpRequest")
                            ).text
                            
                            val seasonDoc = Jsoup.parse(response)
                            // Primary selector: article, Fallback: a
                            val episodeElements = seasonDoc.select(".EpisodesList article").ifEmpty { seasonDoc.select(".EpisodesList a") }

                            episodeElements.forEach { element ->
                                val a = (if (element.tagName() == "a") element else element.selectFirst("a")) ?: return@forEach
                                val epUrl = fixUrlNull(a.attr("href")) ?: return@forEach
                                
                                val rawText = (element.selectFirst("h3, h4, .title")?.text() ?: element.text()).trim()
                                if (rawText.contains("المشاهدة الان") || rawText.contains("التحميل الان")) return@forEach

                                val epNum = Regex("""\d+""").find(rawText)?.value?.toIntOrNull()
                                val cleanTitle = if (epNum != null) "الحلقة $epNum" else rawText

                                val thumbnail = fixUrlNull(
                                    element.selectFirst("img")?.attr("data-src")
                                        ?.ifBlank { element.selectFirst("img")?.attr("src") }
                                        ?: element.selectFirst("[style*='background-image']")?.attr("style")?.let { style ->
                                            Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
                                        }
                                )

                                dbEpisodes.add(newEpisode(epUrl) {
                                    this.name = cleanTitle
                                    this.episode = epNum
                                    this.season = seasonNum
                                    this.posterUrl = thumbnail
                                })
                            }
                        } catch (e: Exception) {
                            // Ignore error
                        }
                    }
                }
            } else {
                // Primary selector: article, Fallback: a
                val episodeElements = doc.select(".EpisodesList article").ifEmpty { doc.select(".EpisodesList a") }
                val episodes = episodeElements.mapNotNull { element ->
                        val a = (if (element.tagName() == "a") element else element.selectFirst("a")) ?: return@mapNotNull null
                        val epUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                        
                        val rawText = (element.selectFirst("h3, h4, .title")?.text() ?: element.text()).trim()
                        if (rawText.contains("المشاهدة الان") || rawText.contains("التحميل الان")) return@mapNotNull null

                        val epNum = Regex("""\d+""").find(rawText)?.value?.toIntOrNull()
                        val cleanTitle = if (epNum != null) "الحلقة $epNum" else rawText

                        val thumbnail = fixUrlNull(
                            element.selectFirst("img")?.attr("data-src")
                                ?.ifBlank { element.selectFirst("img")?.attr("src") }
                                ?: element.selectFirst("[style*='background-image']")?.attr("style")?.let { style ->
                                    Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
                                }
                        )

                        newEpisode(epUrl) {
                            this.name = cleanTitle
                            this.episode = epNum
                            this.season = 1 // Force season 1 for flat lists to trigger UI
                            this.posterUrl = thumbnail
                        }
                    }
                dbEpisodes.addAll(episodes)
            }

            // Ensure all episodes have a season to trigger Netflix UI
            dbEpisodes.forEach { if (it.season == null) it.season = 1 }

            newAnimeLoadResponse(title, cleanUrl, type) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                addEpisodes(DubStatus.Subbed, dbEpisodes.ifEmpty { 
                     // Fallback episode with season 1
                    listOf(newEpisode(cleanUrl) { 
                        this.name = title
                        this.episode = 1
                        this.season = 1 
                    }) 
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
