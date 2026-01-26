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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/", interceptor = cfInterceptor).document
        val list = doc.select("div.recent-episode").mapNotNull {
            val title = it.selectFirst(".name")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst(".image img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }
            val epNum = it.selectFirst(".episode-number")?.text()
                ?.replace("الحلقة :", "")?.trim()?.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addSub(epNum)
            }
        }
        return newHomePageResponse(listOf(HomePageList("Latest Added", list)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/search?query=$query", interceptor = cfInterceptor).document.select(".anime-card-container, .content .item, .recent-episode").mapNotNull {
            val title = it.selectFirst(".name")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.let { img ->
                img.attr("data-original").ifEmpty { img.attr("data-src").ifEmpty { img.attr("src") } }
            }
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
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
            // Structure: <span>الحلقة</span> <span class="separator">:</span> <span>1</span>
            val epNum = it.select("span").dropLast(1).lastOrNull()?.text()?.toIntOrNull() ?: it.select("span").last()?.text()?.toIntOrNull()
            // Adjusted logic: usually the last span is the number. 
            // Previous logic: `name = it.select("span").dropLast(1).lastOrNull()?.text()`. 
            // In the HTML: <span>الحلقة</span> <span class="separator">:</span> <span>1</span>
            // last() is <span>1</span>. dropLast(1).last() is separator. dropLast(2).last() is title.
            // Actually, `it.select("span").last()?.text()` gives "1".
            
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