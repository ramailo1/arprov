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
        val isSeries = href.contains("مسلسل") || 
                      href.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84") ||
                      href.contains("الحلقة") || 
                      href.contains("%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9") ||
                      title.contains("مسلسل") ||
                      title.contains("الحلقة")

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
        
        // Target the actual content h1 inside SingleContent to avoid the site logo h1
        val pageTitle = doc.selectFirst(".SingleContent h1, h1.Title, .PageTitle h1")?.ownText()?.trim()
            ?: doc.selectFirst("h1")?.ownText()?.trim() ?: ""
        
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
            .replace("Cima4u", "", ignoreCase = true)
            .trim()
            .ifBlank { pageTitle.ifBlank { throw ErrorLoadingException("No title found") } }

        val posterUrl = doc.selectFirst(".SinglePoster img, .Thumb img, .Poster img, figure img")?.let { img ->
            img.attr("data-image").ifBlank {
                img.attr("data-src").ifBlank { img.attr("src") }
            }
        }
        
        val description = doc.selectFirst(".Story, .story, .plot, div[class*=\"story\"], p")?.text()?.trim()
        
        val year = Regex("(19|20)\\d{2}").find(doc.html())?.value?.toIntOrNull()
        
        // Check for episodes (both Arabic and URL-encoded patterns)
        val episodeElements = doc.select(".EpisodesList a, a[href*=\"الحلقة\"], a[href*=\"%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9\"], li.MovieBlock a")
        val isSeries = episodeElements.isNotEmpty() || 
                      url.contains("مسلسل") || 
                      url.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84")
        
        return if (isSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                if (epUrl.isBlank() || epUrl == url) return@mapNotNull null
                
                // Use ownText() or Title class for clean extraction
                val epTitle = ep.selectFirst(".BoxTitle")?.text()?.trim() ?: ep.ownText().trim().ifBlank { ep.text().trim() }
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
