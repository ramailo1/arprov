package com.lagradost.cloudstream3.cima4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Cima4UProvider : MainAPI() {
    override var mainUrl = "https://cfu.cam"
    override var name = "Cima4U"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/#new-cinema" to "جديد السينما",
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبي",
        "$mainUrl/category/افلام-اسيوي/" to "أفلام أسيوي",
        "$mainUrl/category/افلام-انمي/" to "أفلام أنمي",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات أنمي",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبي",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات أسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("#new-cinema") -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                mainUrl
            }
            page == 1 -> request.data
            request.data.endsWith("/") -> "${request.data}page/$page/"
            else -> "${request.data}/page/$page/"
        }
        
        val doc = app.get(url).document
        
        val items = doc.select("li.MovieBlock, a[href*=\"مشاهدة-\"], a[href*=\"%d9%85%d8%b4%d8%a7%d9%87%d8%a9\"]")
            .mapNotNull { element ->
                element.toSearchResponse()
            }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, hasNext = !request.data.contains("#new-cinema"))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        
        return doc.select("li.MovieBlock, a[href*=\"مشاهدة-\"], a[href*=\"%d9%85%d8%b4%d8%a7%d9%87%d8%a9\"]")
            .mapNotNull { element ->
                element.toSearchResponse()
            }.distinctBy { it.url }
    }

    private val seriesCache = LinkedHashMap<String, List<Episode>>(20, 0.75f, true)

    private fun cacheEpisodes(url: String, episodes: List<Episode>) {
        if (seriesCache.size > 30) {
            seriesCache.remove(seriesCache.keys.first())
        }
        seriesCache[url] = episodes
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"

    private fun normalizeArabicNumbers(text: String): String {
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return text.map {
            val index = arabic.indexOf(it)
            if (index >= 0) index.toString()[0] else it
        }.joinToString("")
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex(
            "(?:الحلقة|episode|ep)[^\\d]*(\\d{1,3})",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSeasonNumber(text: String): Int? {
        return Regex(
            "(?:الموسم|season|s)[^\\d]*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun normalizeSeriesUrl(url: String): String {
        return url.replace(
            Regex("(الحلقة|%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9)[^/\\d]*\\d+.*"),
            ""
        ).ensureTrailingSlash()
    }

    private fun parseEpisodes(
        doc: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Episode> {
        val elements = doc.select(
            "ul.insert_ep li a, " +
            "ul.Episodes li a, " +
            "div.Episodes a, " +
            "a[href*=\"-الحلقة-\"], " +
            "a[href*=\"%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-\"]"
        )

        return elements.mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = fixUrl(href)

            val text = normalizeArabicNumbers(el.text())

            val episode = extractEpisodeNumber(text) ?: extractEpisodeNumber(url)
            val season = extractSeasonNumber(text) ?: extractSeasonNumber(url)

            newEpisode(url) {
                name = "الحلقة ${episode ?: ""}".trim()
                this.episode = episode
                this.season = season ?: 1
            }
        }.distinctBy { "${it.season}-${it.episode}" }
            .sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        
        if (href == mainUrl || href.isBlank()) return null
        
        // Title extraction: Favor .BoxTitle's ownText(). 
        // If it's "Cima4u" or blank, fallback to other elements.
        var title = this.selectFirst(".BoxTitle, .Title")?.ownText()?.trim()
            ?: this.ownText().trim()
            ?: aTag.attr("title").trim()
            
        if (title.equals("Cima4u", ignoreCase = true) || title.isBlank() || title.matches(Regex("^\\d+$"))) {
            title = aTag.attr("title").trim().ifBlank { 
                this.selectFirst(".BoxTitle, .Title")?.text()?.replace("Cima4u", "", ignoreCase = true)?.trim() ?: ""
            }
        }
        
        if (title.isBlank() || title.equals("Cima4u", ignoreCase = true)) return null
        
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-image").ifBlank { 
                img.attr("data-src").ifBlank { img.attr("src") }
            }
        }

        // ... Detect series logic
        val isSeries =
            href.contains("-الحلقة-") ||
            href.contains("%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-") ||
            href.contains("مسلسل")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = normalizeSeriesUrl(url)

        // Use cache if available
        val cachedEpisodes = seriesCache[seriesUrl]

        val doc = app.get(seriesUrl).document

        val rawTitle = doc.selectFirst(
            ".SingleContent h1, h1.Title, .PageTitle h1, h1"
        )?.ownText()?.trim()
            ?: throw ErrorLoadingException("No title")

        val title = rawTitle
            .replace(Regex("مشاهدة|تحميل|فيلم|مسلسل|انمي|مترجم|مدبلج|كامل|اون لاين", RegexOption.IGNORE_CASE), "")
            .replace("Cima4u", "", ignoreCase = true)
            .trim()

        val posterUrl = doc.selectFirst(
            ".SinglePoster img, .Thumb img, figure img"
        )?.let {
            it.attr("data-image").ifBlank {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
        }

        val plot = doc.selectFirst(
            ".Story, .story, div[class*=story], p"
        )?.text()?.trim()

        val year = Regex("(19|20)\\d{2}")
            .find(doc.text())
            ?.value
            ?.toIntOrNull()

        val episodes = cachedEpisodes ?: parseEpisodes(doc, seriesUrl).also {
            if (it.isNotEmpty()) cacheEpisodes(seriesUrl, it)
        }

        val isSeries = episodes.isNotEmpty() ||
                doc.selectFirst("ul.insert_ep, ul.Episodes, div.Episodes") != null ||
                seriesUrl.contains("مسلسل")

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title,
                seriesUrl,
                TvType.TvSeries,
                episodes.ifEmpty {
                    listOf(
                        newEpisode(url) {
                            name = "الحلقة الحالية"
                            episode = extractEpisodeNumber(url)
                            season = extractSeasonNumber(url) ?: 1
                        }
                    )
                }
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ensure proper watch URL with trailing slash
        val watchUrl = when {
            data.contains("/watch") -> if (data.endsWith("/")) data else "$data/"
            data.endsWith("/") -> "${data}watch/"
            else -> "$data/watch/"
        }
                       
        val doc = app.get(watchUrl).document
        
        // Extract streaming servers from iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, watchUrl, subtitleCallback, callback)
            }
        }

        // Extract streaming servers from list items (AJAX loading)
        doc.select(".serversWatchSide li, .serversWatchSide ul li").forEach { li ->
            val url = li.attr("data-url").ifBlank { li.attr("url") }.ifBlank { li.attr("data-src") }
            if (url.isNotBlank() && url.startsWith("http")) {
                loadExtractor(url, watchUrl, subtitleCallback, callback)
            }
        }
        
        // Extract download links - comprehensive selector
        doc.select(
            ".DownloadServers a, " +
            "a[href*=\".com/d/\"], " +
            "a.DownloadLink, " +
            "a[href*=\"doodstream\"], " +
            "a[href*=\"cybervynx\"], " +
            "a[href*=\"lulustream\"], " +
            "a[href*=\"filemoon\"], " +
            "a[href*=\"streamtape\"]"
        ).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && 
                href.startsWith("http") && 
                !href.contains("midgerelativelyhoax")) {
                loadExtractor(href, watchUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
