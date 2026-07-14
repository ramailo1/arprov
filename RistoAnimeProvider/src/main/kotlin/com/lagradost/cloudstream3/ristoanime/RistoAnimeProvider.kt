package com.lagradost.cloudstream3.ristoanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class RistoAnimeProvider : MainAPI() {

    override var mainUrl = "https://ristoanime.me"

    private fun String.replaceDomain(): String {
        return this.replace("https://ristoanime.org", mainUrl)
            .replace("https://ristoanime.me", mainUrl)
            .replace("http://ristoanime.org", mainUrl)
            .replace("http://ristoanime.me", mainUrl)
    }

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
        val url = buildPagedUrl(request.data.replaceDomain(), page)
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
        val cleanUrl = url.replaceDomain().removeSuffix("/watch").removeSuffix("/download")
        val doc = app.get(cleanUrl, headers = headers()).document
        politeDelay()

        val title = doc.selectFirst("h1.PostTitle, .PostTitle, h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(doc.extractPoster())
        val description = doc.selectFirst(".StoryArea p, .StoryArea, .description, .plot, .summary")?.text()?.trim()
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        // Determine if this is a series or movie
        val hasEpisodeList = doc.select(".EpisodesList").isNotEmpty()
        val isSeriesUrl = cleanUrl.contains("/series/")
        val isSeries = isSeriesUrl || hasEpisodeList
        val type = if (isSeries) TvType.Anime else TvType.AnimeMovie

        return if (isSeries) {
            val allEpisodes = mutableListOf<Episode>()

            // On /series/ pages, check if there are multiple seasons (SeasonsList with links)
            // Each season link points to a /series/ sub-page with its own EpisodesList
            val seasonLinks = doc.select(".SeasonsList a[href], .SeasonsList > ul li a[href]")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.contains("/series/") }
                .distinct()

            if (seasonLinks.isNotEmpty()) {
                // Multi-season: fetch each season page and collect its episodes
                seasonLinks.forEachIndexed { idx, seasonUrl ->
                    val seasonNum = idx + 1
                    try {
                        val seasonDoc = app.get(seasonUrl, headers = headers()).document
                        politeDelay()
                        val eps = parseEpisodeList(seasonDoc, seasonNum)
                        allEpisodes.addAll(eps)
                    } catch (_: Exception) {}
                }
            }

            // Also parse episodes from the current page (covers single-season series and episode pages)
            if (allEpisodes.isEmpty()) {
                val seasonNum = extractSeasonNum(cleanUrl)
                allEpisodes.addAll(parseEpisodeList(doc, seasonNum))
            }

            // Sort ascending by season then episode number
            val sorted = allEpisodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

            newAnimeLoadResponse(title, cleanUrl, type) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                addEpisodes(DubStatus.Subbed, sorted.ifEmpty {
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

    /** Extracts the season number from a URL slug (e.g. الموسم-3 → 3). */
    private fun extractSeasonNum(url: String): Int {
        // Try Arabic season pattern in URL: %d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85 = الموسم
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        return Regex("""الموسم[- _]*(\d+)""").find(decoded)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""season[- _]*(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
    }

    /**
     * Parses the .EpisodesList from a Jsoup document.
     * Episodes are listed newest-first on the site, so we reverse to get ascending order (ep 1 → N).
     * Structure: <div class="EpisodesList"><a href="...">حلقة<em>3</em></a>...
     */
    private fun parseEpisodeList(doc: org.jsoup.nodes.Document, seasonNum: Int): List<Episode> {
        val episodeList = doc.selectFirst(".EpisodesList") ?: return emptyList()

        // Episodes are <a> tags directly inside .EpisodesList
        val links = episodeList.select("a[href]")
        if (links.isEmpty()) return emptyList()

        val episodes = links.mapNotNull { a ->
            val epUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            // Episode number is in <em> tag inside the <a>
            val epNumStr = a.selectFirst("em")?.text()?.trim()
            val epNum = epNumStr?.toIntOrNull()
                ?: Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
            val cleanTitle = if (epNum != null) "الحلقة $epNum" else a.text().trim().ifBlank { "الحلقة" }

            newEpisode(epUrl) {
                this.name = cleanTitle
                this.episode = epNum
                this.season = seasonNum
            }
        }

        // Site lists newest-first; reverse so ep 1 comes first
        return episodes.reversed()
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postUrl = data.replaceDomain().removeSuffix("/watch").removeSuffix("/download")
        val postDoc = app.get(postUrl, headers = headers()).document
        politeDelay(300)

        // Extract the post_id from inline JS (used in Rate.php, Trailer.php etc.)
        val pageHtml = postDoc.html()
        val postId = Regex("""'id'\s*,\s*'(\d+)'""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""id:\s*'(\d+)'""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""post_id:\s*'(\d+)'""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex(""""id","(\d+)"""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""AjaxtURL\+'Rate\.php'.*?id=(\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""'id=\d+&id=(\d+)'""").find(pageHtml)?.groupValues?.get(1)

        // Extract the AjaxtURL from inline JS
        val ajaxUrl = Regex("""var AjaxtURL\s*=\s*"(.*?)"""").find(pageHtml)?.groupValues?.get(1)?.replaceDomain()
            ?: "$mainUrl/wp-content/themes/TopAnime/Ajaxt/"

        if (postId != null) {
            try {
                // Call the Watch.php AJAX endpoint to get server list
                val watchResponse = app.post(
                    "${ajaxUrl}Single/Watch.php",
                    data = mapOf("id" to postId),
                    headers = headers() + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to postUrl
                    )
                ).text
                politeDelay(200)

                val watchDoc = Jsoup.parse(watchResponse)

                // Each <li data-watch="EMBED_URL"> contains an embed link
                watchDoc.select("ul#watch li[data-watch]").forEach { li ->
                    val embedUrl = li.attr("data-watch").trim()
                    if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                        try {
                            loadExtractor(embedUrl, postUrl, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }

                // Download section: <div class="ServersList Download"> <ul> <li> <a href="...">
                watchDoc.select(".ServersList.Download ul li a[href]").forEach { a ->
                    val link = fixUrlNull(a.attr("href")) ?: return@forEach
                    if (link.startsWith("http") && !link.contains(mainUrl)) {
                        try {
                            loadExtractor(link, postUrl, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }

            } catch (_: Exception) {}
        }

        return true
    }
}
