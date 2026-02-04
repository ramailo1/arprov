package com.lagradost.cloudstream3.cima4ushop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Cima4uShopProvider : MainAPI() {
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
        "$mainUrl/page/" to "الرئيسية",
        "$mainUrl/category/movies/page/" to "أفلام",
        "$mainUrl/category/series/page/" to "مسلسلات",
        "$mainUrl/category/anime/page/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.replace("/page/", "/") else request.data + page
        val doc = app.get(url).document
        
        val items = doc.select("li.MovieBlock").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        
        return doc.select("li.MovieBlock").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        
        // Title extraction: Try .BoxTitle first, or fallback to generic title attributes
        // The site often puts the title in .BoxTitle but it might have extra text
        var title = this.selectFirst(".BoxTitle, .Title")?.text()?.trim() 
            ?: aTag.attr("title").trim()
            
        if (title.isBlank()) return null
        
        // Clean title if needed (often clean enough on this site)
        
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val isSeries = href.contains("مسلسل") || href.contains("الحلقة") || title.contains("مسلسل")

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
        val doc = app.get(url).document
        
        // Site title often has "مشاهدة فيلم X مترجم" etc.
        val pageTitle = doc.selectFirst("h1")?.text()?.trim() ?: ""
        
        // Clean extraction of the actual content title
        val title = pageTitle
            .replace("مشاهدة", "")
            .replace("فيلم", "")
            .replace("مسلسل", "")
            .replace("انمي", "")
            .replace("مترجم", "")
            .replace("اون لاين", "")
            .replace("تحميل", "")
            .trim()
            .ifBlank { pageTitle }

        val posterUrl = doc.selectFirst(".Thumb img, .Poster img, figure img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        
        val description = doc.selectFirst(".Story, .story, .plot")?.text()?.trim()
        
        val year = Regex("(19|20)\\d{2}").find(doc.html())?.value?.toIntOrNull()
        
        // Check for episodes
        val episodeElements = doc.select(".EpisodesList a, a[href*=\"الحلقة\"]")
        val isSeries = episodeElements.isNotEmpty() || url.contains("مسلسل")
        
        return if (isSeries) {
            // If episodes list is present
            val episodes = episodeElements.mapNotNull { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                if (epUrl.isBlank() || epUrl == url) return@mapNotNull null
                
                val epTitle = ep.text().trim()
                val epNum = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()
                
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.distinctBy { it.data }.reversed()
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
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
        // Navigate to the watch page
        val watchUrl = if (data.contains("/watch/")) data else 
                       if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
                       
        val doc = app.get(watchUrl).document
        
        // Extract streaming servers from iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, watchUrl, subtitleCallback, callback)
            }
        }
        
        // Extract download links
        doc.select("a[href*=\".com/d/\"], a.DownloadLink").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.startsWith("http")) {
                loadExtractor(href, watchUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }
}