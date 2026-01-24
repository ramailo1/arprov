package com.shed4u

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class Shed4uProvider : MainAPI() {
    override var mainUrl = "https://ristoanime.org"
    override var name = "RistoAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl" to "الرئيسية",
        "$mainUrl/animes-list" to "قائمة الأنمي",
        "$mainUrl/movies-list" to "قائمة الأفلام"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        
        // RistoAnime uses a grid/slider layout. Identifiable items are links with specific paths.
        val home = doc.select("a[href*='/انمي-'], a[href*='/فيلم-'], .SlideItem").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h4")?.text()?.trim() ?: this.attr("title").ifEmpty { null } ?: return null
        val href = fixUrl(this.attr("href") ?: "")
        // Poster is often in style background-image
        val posterStyle = this.selectFirst(".img, i")?.attr("style") ?: this.attr("style")
        val posterUrl = Regex("url\\((.*?)\\)").find(posterStyle)?.groupValues?.get(1)?.replace("'", "")?.replace("\"", "")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val doc = app.get(url).document
        
        return doc.select("a[href*='/انمي-'], a[href*='/فيلم-']").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .Title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".Poster img")?.attr("src") 
            ?: Regex("url\\((.*?)\\)").find(doc.selectFirst(".Poster")?.attr("style") ?: "")?.groupValues?.get(1)

        val description = doc.selectFirst(".Story, .Description")?.text()?.trim()
        val year = doc.selectFirst(".Date")?.text()?.toIntOrNull()
        
        val episodes = mutableListOf<Episode>()
        // Episodes listing logic for RistoAnime
        doc.select(".Episodes a, .EpisodeList a").forEach { ep ->
              val epTitle = ep.text()
              val epUrl = ep.attr("href")
              val epNum = epTitle.filter { it.isDigit() }.toIntOrNull()
              episodes.add(newEpisode(epUrl) {
                  this.name = epTitle
                  this.season = 1
                  this.episode = epNum
              })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes.reversed()) // Usually newest first
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        
        // Check for servers
        doc.select(".Servers li, .WatchServers li").forEach { server ->
             val serverUrl = server.attr("data-url") // Or similar attribute
             // Standard extraction logic would go here. 
             // Since I don't have exact server logic from inspection, I'll attempt a generic extraction or just iframe search.
             if (serverUrl.isNotEmpty()) {
                 loadExtractor(serverUrl, data, subtitleCallback, callback)
             }
        }
        
        // Also check iframes directly
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            loadExtractor(src, data, subtitleCallback, callback)
        }
        
        return true
    }
}