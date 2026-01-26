package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// ramailo
// Note: This provider requires Cloudflare to be solved via WebView
class AnimeBlkomProvider : MainAPI() {
    override var mainUrl = "https://animeblkom.net"
    override var name = "AnimeBlkom"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val usesWebView = true  // Critical for Cloudflare

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    // Browser-like headers
    private fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()

        // 1. Fetch Latest Episodes
        try {
            val doc = app.get(mainUrl, headers = getHeaders(), allowRedirects = true).document
            val episodesList = doc.select("div.recent-episode").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinct()
            if (episodesList.isNotEmpty()) {
                homeList.add(HomePageList("أخر الحلقات المضافة", episodesList, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fetch Most Rated
        try {
            val doc = app.get("$mainUrl/anime-list?sort_by=rate", headers = getHeaders(), allowRedirects = true).document
            val ratedList = doc.select("div.content").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinct()
            if (ratedList.isNotEmpty()) {
                homeList.add(HomePageList("الأعلى تقييماً", ratedList, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(homeList)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // For recent episodes (div.recent-episode)
        val titleFromText = selectFirst("div.text div.name")?.text()
        // For anime list (div.content)
        val titleFromInfo = selectFirst("div.info div.name a")?.text()
        val title = titleFromText ?: titleFromInfo ?: return null
        
        val href = selectFirst("a")?.attr("href") ?: return null
        
        val posterImg = selectFirst("img.lazy") ?: selectFirst("img")
        val posterUrl = posterImg?.let { img ->
            val dataOriginal = img.attr("data-original")
            val dataSrc = img.attr("data-src")
            val src = img.attr("src")
            when {
                dataOriginal.isNotEmpty() -> if (dataOriginal.startsWith("/")) "$mainUrl$dataOriginal" else dataOriginal
                dataSrc.isNotEmpty() -> if (dataSrc.startsWith("/")) "$mainUrl$dataSrc" else dataSrc
                else -> if (src.startsWith("/")) "$mainUrl$src" else src
            }
        }
        
        val epNum = selectFirst("div.episode-number")?.text()
            ?.replace("الحلقة :", "")?.replace("الحلقة", "")?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epNum != null) addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/search?query=$query", headers = getHeaders(), allowRedirects = true).document
            .select("div.content, div.recent-episode").mapNotNull {
                it.toSearchResponse()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = getHeaders(), allowRedirects = true).document
        val title = doc.selectFirst("h1")?.text()?.replace("(anime)", "")?.trim() ?: ""
        val poster = doc.selectFirst("div.poster img")?.let { img ->
            val dataOriginal = img.attr("data-original")
            if (dataOriginal.startsWith("/")) "$mainUrl$dataOriginal" 
            else if (dataOriginal.isNotEmpty()) dataOriginal
            else img.attr("src")
        }
        val description = doc.selectFirst(".story")?.text()
        val genre = doc.select(".genres a").map { it.text() }
        val statusText = doc.select(".info-table .info").find { 
            it.parent()?.selectFirst(".head")?.text()?.contains("حالة") == true 
        }?.text()
        
        val episodes = doc.select("ul.episodes-links li a").mapNotNull {
            val href = it.attr("href")
            val epNum = it.select("span").last()?.text()?.toIntOrNull()
            
            newEpisode(href) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genre
            this.showStatus = when (statusText) {
                "مستمر" -> ShowStatus.Ongoing
                "منتهي" -> ShowStatus.Completed
                else -> null
            }
            addEpisodes(DubStatus.Subbed, if (episodes.isEmpty()) emptyList() else episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = getHeaders(), allowRedirects = true).document
        
        // 1. Direct Downloads from Modal
        doc.select("#download .panel-body a.btn").forEach {
            val link = it.attr("href")
            val quality = it.text().filter { c -> c.isDigit() }.toIntOrNull()
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

        // 2. Stream Servers
        doc.select(".servers .server a, .servers a[data-src]").forEach {
            val link = it.attr("data-src")
            if (link.isNotBlank()) {
                 loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}