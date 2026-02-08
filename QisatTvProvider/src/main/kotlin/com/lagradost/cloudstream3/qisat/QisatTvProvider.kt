package com.lagradost.cloudstream3.qisat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class QisatTvProvider : MainAPI() {
    override var mainUrl = "https://www.qisat.tv"
    override var name = "Qisat"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ---------- Main Page ----------
    override val mainPage = mainPageOf(
        "/" to "أحدث الحلقات",
        "/turkish-series/" to "المسلسلات التركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(fixUrl(request.data)).document
        
        val items = when {
            request.data == "/" -> {
                // Homepage shows episode links
                doc.select("a[href*=/episode/]").mapNotNull { a ->
                    val url = fixUrl(a.attr("href"))
                    val title = a.text().trim().ifEmpty { return@mapNotNull null }
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    }
                }
            }
            request.data.contains("/turkish-series/") -> {
                // Series listing page
                doc.select("a[href*=/series/]").mapNotNull { a ->
                    val url = fixUrl(a.attr("href"))
                    val title = a.text().trim().ifEmpty { return@mapNotNull null }
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        posterUrl = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    }
                }
            }
            else -> emptyList()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = false
        )
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("a[href*=/series/], a[href*=/episode/]").mapNotNull { a ->
            val url = fixUrl(a.attr("href"))
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            
            if (url.contains("/series/")) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
            }
        }.distinctBy { it.url }
    }

    // ---------- Load Series/Episode ----------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, h2")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim()
        val poster = doc.selectFirst("img[src*=qisat]")?.attr("src")?.let { fixUrl(it) }
        val plot = doc.selectFirst("p")?.text()?.trim()

        return when {
            url.contains("/series/") -> {
                // Series page - collect all episodes
                val episodes = doc.select("a[href*=/episode/]").mapNotNull { a ->
                    val epUrl = fixUrl(a.attr("href"))
                    val epName = a.text().trim()
                    val epNum = Regex("""الحلقة\s+(\d+)""").find(epName)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                }.distinctBy { it.data }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            url.contains("/episode/") -> {
                // Single episode page
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }
    }

    // ---------- Load Links (Video Sources) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Extract the player slug from the page
        val playerSlug = doc.selectFirst("a[href*=/albaplayer/]")?.attr("href")
            ?.substringAfter("/albaplayer/")?.substringBefore("?")
            ?: return false

        // Server mapping: button label -> server number
        val servers = listOf(
            "CDNPlus" to 1,
            "MP4Plus" to 2,
            "AnaFast" to 3,
            "Vidoba" to 4,
            "VidSpeed" to 5,
            "larhu" to 6,
            "VK" to 7,
            "OK" to 8
        )

        servers.forEach { (serverName, servNum) ->
            try {
                val playerUrl = "$mainUrl/albaplayer/$playerSlug/?serv=$servNum"
                val playerDoc = app.get(
                    playerUrl,
                    referer = data
                ).document

                // Extract iframe sources
                playerDoc.select("iframe[src]").forEach { iframe ->
                    val iframeUrl = fixUrl(iframe.attr("src"))
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }

                // Extract embed links
                playerDoc.select("a[href*=/embed-], a[href*=/videoembed/]").forEach { a ->
                    val embedUrl = fixUrl(a.attr("href"))
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }

            } catch (e: Exception) {
                // Server may not be available
            }
        }

        return true
    }
}
