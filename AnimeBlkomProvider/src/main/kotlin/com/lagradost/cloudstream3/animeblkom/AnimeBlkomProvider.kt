package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeBlkomProvider : MainAPI() {

    // ========= BASIC INFO =========
    override var name = "AnimeBlkom"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val usesWebView = true  // Critical: signals CloudStream that WebView may be needed

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // ========= DOMAINS (with fallback) =========
    private val domains = listOf(
        "https://animeblkom.net",
        "https://animeblkom.com",
        "https://animeblkom.site"
    )

    override var mainUrl = domains.first()

    // ========= MAIN PAGE =========
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val home = mutableListOf<HomePageList>()

        // Graceful failure: if Cloudflare blocks, return empty
        val doc = CloudflareHelper.getDocOrNull(mainUrl, mainUrl)
            ?: return newHomePageResponse(emptyList())

        val latest = doc.select("div.recent-episode")
            .mapNotNull { it.toSearch() }

        if (latest.isNotEmpty()) {
            home.add(
                HomePageList(
                    "آخر الحلقات المضافة",
                    latest,
                    isHorizontalImages = true
                )
            )
        }

        // Try to get rated list
        runCatching {
            val ratedDoc = CloudflareHelper.getDocOrNull("$mainUrl/anime-list?sort_by=rate", mainUrl)
            if (ratedDoc != null) {
                val rated = ratedDoc.select("div.content")
                    .mapNotNull { it.toSearch() }

                if (rated.isNotEmpty()) {
                    home.add(HomePageList("الأعلى تقييماً", rated))
                }
            }
        }

        return newHomePageResponse(home)
    }

    // ========= SEARCH =========
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = CloudflareHelper.getDocOrNull("$mainUrl/search?query=$query", mainUrl)
            ?: return emptyList()
        
        return doc.select("div.content, div.recent-episode")
            .mapNotNull { it.toSearch() }
    }

    // ========= LOAD ANIME =========
    override suspend fun load(url: String): LoadResponse {
        val doc = CloudflareHelper.getDocOrNull(url, mainUrl)
            ?: throw ErrorLoadingException("Cloudflare protection - please open in WebView")

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("div.poster img")?.absUrl("data-original")
            ?.ifEmpty { doc.selectFirst("div.poster img")?.absUrl("src") }

        val plot = doc.selectFirst(".story")?.text()
        val tags = doc.select(".genres a").map { it.text() }

        val status = doc.select(".info-table tr").firstNotNullOfOrNull {
            if (it.selectFirst(".head")?.text()?.contains("حالة") == true)
                it.selectFirst(".info")?.text()
            else null
        }

        val episodes = doc.select("ul.episodes-links a").mapNotNull {
            val epNum = it.selectFirst("span")?.text()?.toIntOrNull()
            newEpisode(it.attr("href")) {
                episode = epNum
                name = "Episode $epNum"
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            showStatus = when (status) {
                "مستمر" -> ShowStatus.Ongoing
                "منتهي" -> ShowStatus.Completed
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ========= LINKS =========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = CloudflareHelper.getDocOrNull(data, mainUrl)
            ?: return false  // Returns false to trigger WebView

        // Direct downloads
        doc.select("#download a.btn").forEach {
            val link = it.absUrl("href")
            val quality = it.text().filter(Char::isDigit).toIntOrNull()
                ?: Qualities.Unknown.value

            if (link.isNotBlank()) {
                callback(
                    newExtractorLink(
                        name,
                        "Download ${quality}p",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    }
                )
            }
        }

        // Streaming servers
        doc.select(".servers a[data-src]").forEach {
            val link = it.attr("data-src")
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ========= PARSER =========
    private fun Element.toSearch(): SearchResponse? {
        val title = selectFirst(".name")?.text() ?: return null
        val link = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.absUrl("data-original")
            ?.ifEmpty { selectFirst("img")?.absUrl("src") }

        return newAnimeSearchResponse(title, link, TvType.Anime) {
            posterUrl = poster
        }
    }
}