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
        "$mainUrl/" to "الرئيسية",
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
        
        val items = doc.select("li.MovieBlock").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, items, hasNext = !request.data.contains("#new-cinema"))
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
        
        // Title extraction: Use ownText() to ignore child div text (.BoxTitleInfo)
        // which contains views and category labels.
        var title = this.selectFirst(".BoxTitle, .Title")?.ownText()?.trim()
            ?: this.selectFirst(".BoxTitle, .Title")?.text()?.trim()
            ?: aTag.attr("title").trim()
            
        if (title.isBlank()) return null
        
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-image").ifBlank { 
                img.attr("data-src").ifBlank { img.attr("src") }
            }
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
            .replace("وتحميل", "")
            .replace("مترجم", "")
            .replace("مدبلج", "")
            .replace("كامل", "")
            .replace("بجودة", "")
            .replace("عالية", "")
            .replace("اون لاين", "")
            .replace("مباشر", "")
            .replace("تحميل", "")
            .trim()
            .ifBlank { pageTitle }

        val posterUrl = doc.selectFirst(".Thumb img, .Poster img, figure img")?.let { img ->
            img.attr("data-image").ifBlank {
                img.attr("data-src").ifBlank { img.attr("src") }
            }
        }
        
        val description = doc.selectFirst(".Story, .story, .plot, div[class*=\"story\"], p")?.text()?.trim()
        
        val year = Regex("(19|20)\\d{2}").find(doc.html())?.value?.toIntOrNull()
        
        // Check for episodes
        val episodeElements = doc.select(".EpisodesList a, a[href*=\"الحلقة\"], li.MovieBlock a")
        val isSeries = episodeElements.isNotEmpty() || url.contains("مسلسل")
        
        return if (isSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                if (epUrl.isBlank() || epUrl == url) return@mapNotNull null
                
                val epTitle = ep.selectFirst(".BoxTitle")?.text()?.trim() ?: ep.text().trim()
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

        // Extract streaming servers from list items (often used for AJAX loading)
        doc.select(".serversWatchSide li, .serversWatchSide ul li").forEach { li ->
            val url = li.attr("data-url").ifBlank { li.attr("url") }.ifBlank { li.attr("data-src") }
            if (url.isNotBlank() && url.startsWith("http")) {
                loadExtractor(url, watchUrl, subtitleCallback, callback)
            }
        }
        
        // Extract download links
        doc.select(".DownloadServers a, a[href*=\".com/d/\"], a.DownloadLink").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.startsWith("http") && !href.contains("midgerelativelyhoax")) {
                loadExtractor(href, watchUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }
}