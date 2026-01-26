package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

// ramailo
class AnimeBlkomProvider : MainAPI() {
    override var mainUrl = "https://animeblkom.net"
    override var name = "AnimeBlkom"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    
    private val cfInterceptor = CloudflareKiller()

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        mainUrl to "Recently Added", 
        "$mainUrl/anime-list?sort_by=rate&page=" to "Most rated",
        "$mainUrl/anime-list?sort_by=created_at&page=" to "Recently added List",
        "$mainUrl/anime-list?states=finished&page=" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == mainUrl) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/recently-added?page=$page"
        } else {
            "${request.data}$page"
        }
        
        val doc = app.get(url, interceptor = cfInterceptor).document
        val list = doc.select("div.recent-episode, div.anime-card-container, div.item.episode").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst(".name")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-original").ifEmpty { img.attr("data-src").ifEmpty { img.attr("src") } }
        }
        val epNum = selectFirst(".episode-number")?.text()
            ?.replace("الحلقة :", "")?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/search?query=$query", interceptor = cfInterceptor).document.select(".anime-card-container, .content .item, .recent-episode").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfInterceptor).document
        val title = doc.selectFirst("h1")?.text()?.replace("(anime)", "")?.trim() ?: ""
        val poster = doc.selectFirst(".poster img")?.let { img ->
            img.attr("data-original").ifEmpty { img.attr("src") }
        }
        val description = doc.selectFirst(".story")?.text()
        val genre = doc.select(".genres a").map { it.text() }
        val statusText = doc.select(".info-table .info").find { it.parent()?.selectFirst(".head")?.text()?.contains("حالة") == true }?.text()
        
        val episodes = doc.select("ul.episodes-links li a").mapNotNull {
            val href = it.attr("href")
            val epNum = it.select("span").dropLast(1).lastOrNull()?.text()?.toIntOrNull() ?: it.select("span").last()?.text()?.toIntOrNull()
            
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
        val doc = app.get(data, interceptor = cfInterceptor).document
        
        // 1. Direct Downloads from Modal
        doc.select("#download .panel-body a.btn").forEach {
            val link = it.attr("href")
            val qualityText = it.text() // e.g., "360p 45.74 MiB"
            val quality = qualityText.replace("p.*".toRegex(), "").trim().toIntOrNull() ?: Qualities.Unknown.value
            if (link.isNotBlank()) {
                 callback(
                   newExtractorLink(
                       this.name,
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
        doc.select(".servers .server a").forEach {
            val link = it.attr("data-src")
            val name = it.text()
            if (link.isNotBlank()) {
                 loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}