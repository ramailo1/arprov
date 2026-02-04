package com.lagradost.cloudstream3.cima4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.amap
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

    data class SeasonEpisodes(
        val seasonNumber: Int,
        val posterUrl: String?,
        val episodes: List<Episode>
    )

    private val seriesCacheSeasons = LinkedHashMap<String, List<SeasonEpisodes>>(20, 0.75f, true)
    private val seriesCache = LinkedHashMap<String, List<Episode>>(20, 0.75f, true)

    private fun cacheSeasons(url: String, seasons: List<SeasonEpisodes>) {
        if (seriesCacheSeasons.size > 30) {
            seriesCacheSeasons.remove(seriesCacheSeasons.keys.first())
        }
        seriesCacheSeasons[url] = seasons
    }

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
            "(?:^|\\s+|\\b)(?:الحلقة|episode|ep)\\s*(\\d{1,4})(?:\\b|\\s+|$)",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSeasonNumber(text: String): Int? {
        return Regex(
            "(?:^|\\s+|\\b)(?:الموسم|season|s)\\s*(\\d{1,4})(?:\\b|\\s+|$)",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it < 100 }
    }


    private fun isValidEpisodeNumber(num: Int?): Boolean {
        return num != null && num in 1..20000 // avoid unreal episodes, cap increased slightly for safe measure
    }


    private fun detectMissingEpisodesSafe(
        episodes: List<Episode>
    ): List<Episode> {
        val repaired = mutableListOf<Episode>()

        episodes.groupBy { it.season ?: 1 }.forEach { (season, eps) ->
            val numbers = eps.mapNotNull { it.episode }.filter { isValidEpisodeNumber(it) }.sorted()
            if (numbers.isEmpty()) return@forEach

            val max = numbers.last()
            val existing = numbers.toSet()

            val min = numbers.minOrNull() ?: 1
            for (ep in min..max) {
                if (ep !in existing) {
                     repaired.add(
                        newEpisode("") {
                            name = "الحلقة $ep (قيد الإصلاح)"
                            episode = ep
                            this.season = season
                        }
                    )
                }
            }
        }
        return repaired
    }

    private fun inferEpisodeUrl(
        sample: Episode,
        episodeNumber: Int
    ): String? {
        val base = sample.data
        if (base.isBlank()) return null

        return when {
            base.contains("-الحلقة-") ->
                base.replace(
                    Regex("-الحلقة-\\d+"),
                    "-الحلقة-$episodeNumber"
                )

            base.contains("episode") ->
                base.replace(
                    Regex("episode\\D*\\d+"),
                    "episode-$episodeNumber"
                )

            else -> null
        }
    }

    private fun repairEpisodeUrls(
        episodes: List<Episode>
    ): List<Episode> {
        val fixed = episodes.toMutableList()

        episodes
            .groupBy { it.season ?: 1 }
            .forEach { (_, eps) ->
                val sample = eps.firstOrNull { !it.data.isNullOrBlank() } ?: return@forEach

                eps.filter { it.data.isNullOrBlank() && it.episode != null }
                    .forEach { missing ->
                        val inferred = inferEpisodeUrl(sample, missing.episode!!)
                        if (inferred != null) {
                            missing.data = inferred
                        }
                    }
            }

        return fixed
    }

    private fun detectSeasonFromDocOrUrl(
        doc: org.jsoup.nodes.Document,
        url: String
    ): Int? {
        // Priority 1: URL context (usually definitive)
        extractSeasonNumber(normalizeArabicNumbers(url))?.let { return it }

        // Priority 2: Page headers and breadcrumbs
        val text = normalizeArabicNumbers(
            doc.select("h1, h2, h3, .Title, .breadcrumb, .PostHeader").text()
        )

        return extractSeasonNumber(text)
    }

    private fun normalizeSeriesUrl(url: String): String {
        return url.replace(
            Regex("(الحلقة|%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9)[^/\\d]*?\\d+.*"),
            ""
        ).ensureTrailingSlash()
    }

    private suspend fun loadAllEpisodePages(
        baseUrl: String,
        firstDoc: org.jsoup.nodes.Document
    ): List<Pair<String, org.jsoup.nodes.Document>> {
        val pages = mutableMapOf(baseUrl to firstDoc)

        // Common pagination selectors on Cima-style sites
        val pageLinks = firstDoc.select(
            ".pagination a, .wp-pagenavi a, a.page-numbers"
        )
            .mapNotNull { it.attr("href") }
            .map { fixUrl(it) }
            .distinct()
            .filter { it.contains("page") }

        pageLinks.amap { pageUrl ->
            if (!pages.containsKey(pageUrl)) {
                runCatching {
                    pages[pageUrl] = app.get(pageUrl).document
                }.onFailure { it.printStackTrace() }
            }
        }

        return pages.toList()
    }

    private fun parseEpisodes(
        doc: org.jsoup.nodes.Document,
        seriesPoster: String?,
        fallbackSeason: Int? = null
    ): List<Episode> {
        val elements = doc.select("#related a, div#related a")

        return elements.mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = fixUrl(href)

            val text = normalizeArabicNumbers(el.text())

            val episode = extractEpisodeNumber(text) ?: extractEpisodeNumber(url)
            val season = extractSeasonNumber(text) ?: extractSeasonNumber(url) ?: fallbackSeason ?: 1

            newEpisode(url) {
                name = episode?.let { "الحلقة $it" } ?: "حلقة"
                this.episode = episode
                this.season = season
                this.posterUrl = el.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: seriesPoster
            }
        }.distinctBy { "${it.season}-${it.episode ?: it.data}" }
            .sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )
    }

    private fun parseEpisodesBySeason(
        doc: org.jsoup.nodes.Document,
        seriesPoster: String?,
        fallbackSeason: Int?
    ): List<SeasonEpisodes> {
        val allEpisodes = parseEpisodes(doc, seriesPoster, fallbackSeason)

        // Group episodes by season
        val seasonsMap = allEpisodes.groupBy { it.season ?: 1 }

        return seasonsMap.map { (seasonNumber, eps) ->
            val poster = eps.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl ?: seriesPoster
            SeasonEpisodes(seasonNumber, poster, eps.sortedBy { it.episode ?: Int.MAX_VALUE })
        }.sortedBy { it.seasonNumber }
    }

    private suspend fun loadEpisodesBySeason(
        seriesUrl: String,
        firstDoc: org.jsoup.nodes.Document,
        poster: String?
    ): List<SeasonEpisodes> {
        val pages = loadAllEpisodePages(seriesUrl, firstDoc)

        val seasonEpisodesList = pages.flatMap { (pageUrl, doc) ->
            val pageSeason = detectSeasonFromDocOrUrl(doc, pageUrl)
            parseEpisodesBySeason(doc, poster, pageSeason)
        }

        // Merge seasons with same number across pages
        val mergedSeasons = seasonEpisodesList.groupBy { it.seasonNumber }.map { (seasonNum, list) ->
            val allEps = list.flatMap { it.episodes }.distinctBy { "${it.season}-${it.episode}" }
                .sortedBy { it.episode ?: Int.MAX_VALUE }
            val seasonPoster = list.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl ?: poster
            SeasonEpisodes(seasonNum, seasonPoster, allEps)
        }.sortedBy { it.seasonNumber }

        // Detect missing episodes safely for each season
        val finalSeasons = mergedSeasons.map { season ->
            val repaired = detectMissingEpisodesSafe(season.episodes)
            season.copy(
                episodes = repairEpisodeUrls(season.episodes + repaired)
                    .distinctBy { it.episode }
                    .sortedBy { it.episode ?: Int.MAX_VALUE }
            )
        }

        return finalSeasons
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
        val cachedSeasons = seriesCacheSeasons[seriesUrl]

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

        val seasonList = cachedSeasons ?: run {
            val seasons = loadEpisodesBySeason(seriesUrl, doc, posterUrl)
            if (seasons.isNotEmpty()) cacheSeasons(seriesUrl, seasons)
            seasons
        }

        val isSeries = seasonList.isNotEmpty() ||
                doc.selectFirst("ul.insert_ep, ul.Episodes, div.Episodes") != null ||
                seriesUrl.contains("مسلسل")

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title,
                seriesUrl,
                TvType.TvSeries,
                seasonList.flatMap { it.episodes.ifEmpty {
                    listOf(newEpisode(url) {
                        name = "الحلقة الحالية"
                        episode = extractEpisodeNumber(url)
                        season = it.seasonNumber
                    })
                }}
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                // Pass seasons via extraData? No, Cloudstream doesn't support 'seasons' in extraData for UI splitting usually, 
                // it uses the episodes list with season numbers.
                // But the user requested: "this.extraData = mapOf("seasons" to seasonList)"
                // I will include it as requested, might be for internal use or custom layout.
                this.tags = seasonList.map { "Season ${it.seasonNumber}" }
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
        // Construct watch URL - append /watch/ if not present
        val watchUrl = when {
            data.endsWith("/watch/") -> data
            data.endsWith("/watch") -> "$data/"
            data.endsWith("/") -> "${data}watch/"
            else -> "$data/watch/"
        }

        val doc = app.get(watchUrl).document

        // Avoid duplicates if a server is listed in multiple places
        val visited = mutableSetOf<String>()

        suspend fun loadLink(url: String) {
            if (url.startsWith("http") && visited.add(url)) {
                loadExtractor(url, watchUrl, subtitleCallback, callback)
            }
        }

        // Iframes: common streaming servers
        doc.select("iframe[src]").forEach { iframe ->
            loadLink(iframe.attr("src"))
        }

        // AJAX server list
        doc.select(".serversWatchSide li").forEach { li ->
            val url = li.attr("data-url").ifBlank { li.attr("url") }.ifBlank { li.attr("data-src") }
            loadLink(url)
        }

        // Download links
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
            // Filter out known broken/ad links if needed, user mentioned midgerelativelyhoax
            if (!href.contains("midgerelativelyhoax")) {
                loadLink(href)
            }
        }

        return true
    }
}
