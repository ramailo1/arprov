@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import kotlinx.coroutines.delay

class EgyDeadProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egydead.rip"
    override var name = "EgyDead"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("جميع مواسم مسلسل|مترجم كامل|مشاهدة فيلم|مشاهدة عرض|مترجم|انمي|الموسم.*|مترجمة كاملة|مسلسل|كاملة|برنامج".toRegex(), "").trim()
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a")
        if (link == null) return null
        
        val href = link.attr("href").takeIf { it.isNotEmpty() && it.contains("egydead") } ?: return null
        val title = (link.selectFirst("h1, h2, h3, .BottomTitle")?.text() ?: link.attr("title")).cleanTitle()
        if (title.isEmpty()) return null
        
        val posterUrl = link.selectFirst("img")?.attr("src") ?: ""
        
        // Determine type based on URL or category
        val tvType = when {
            href.contains("/serie/") || href.contains("/season/") -> TvType.TvSeries
            href.contains("/episode/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(
            title,
            href,
            tvType,
        ) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/movies/?page=" to "احدث الافلام",
        "$mainUrl/episode/?page=" to "احدث الحلقات",
        "$mainUrl/season/?page=" to "احدث المواسم",
        "$mainUrl/serie/?page=" to "احدث المسلسلات",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1000..2000).random().toLong())
        
        val url = if (request.data.contains("/page/movies/")) {
            if (page == 1) {
                "$mainUrl/page/movies/"
            } else {
                "$mainUrl/page/movies/page/$page/"
            }
        } else {
            request.data + page
        }

        val document = app.get(url, headers = requestHeaders).document
        
        // Select all potential containers and direct links for universal support
        val home = document.select("li.movieItem, div.BlockItem, a[href*='egydead']").toList().filter {
            val href = it.attr("href")
            // Filter out common non-content links if it's a direct <a> tag
            if (it.tagName() == "a") {
                href.contains("202") || href.contains("/%") || 
                href.contains("/episode/") || href.contains("/season/") || href.contains("/serie/")
            } else true
        }.mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1000..2000).random().toLong())
        
        val doc = app.get("$mainUrl/?s=$query", headers = requestHeaders).document
        return doc.select("li.movieItem, div.BlockItem, a[href*='egydead']").toList().filter {
            val href = it.attr("href")
            if (it.tagName() == "a") {
                href.contains("202") || href.contains("/%") || 
                href.contains("/episode/") || href.contains("/season/") || href.contains("/serie/")
            } else true
        }.mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1500..2500).random().toLong())
        
        val doc = app.get(url, headers = requestHeaders).document

        // Redirect individual episode pages to their main series/season page for Netflix layout
        if (url.contains("/episode/")) {
            
            // Refined breadcrumb logic: Find the first link that is specifically a series or season, avoiding categories
            val breadcrumbLinks = doc.select(".breadcrumbs-single a")
            val seriesUrl = breadcrumbLinks.find { 
                val href = it.attr("abs:href")
                (href.contains("/serie/") || href.contains("/season/")) && !href.contains("/series-category/") 
            }?.attr("abs:href")
            
            val fallbackLink = doc.selectFirst("a[href*='/serie/']:not([href*='/series-category/']):not([href$='/serie/']), a[href*='/season/']:not([href$='/season/'])")?.attr("abs:href")
            
            val redirectUrl = seriesUrl ?: fallbackLink
            
            if (!redirectUrl.isNullOrEmpty() && redirectUrl != url && !redirectUrl.endsWith("/episode/")) {
                return load(redirectUrl)
            }
        }

        val title = (doc.selectFirst("div.singleTitle em") ?: doc.selectFirst("h1.singleTitle") ?: doc.selectFirst(".breadcrumbs-single li:last-child") ?: doc.selectFirst("h1"))?.text()?.cleanTitle() ?: ""
        val isMovie = !url.contains("/serie/") && !url.contains("/season/") && !url.contains("/episode/")

        val posterUrl = doc.selectFirst("div.single-thumbnail img, div.Poster img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        } ?: ""
        
        val synopsis = doc.select("div.extra-content").find { it.text().contains("القصه") || it.text().contains("القصة") }?.selectFirst("p")?.text() 
            ?: doc.selectFirst("div.Story p")?.text() ?: ""
        val year = doc.select("ul > li:contains(السنه) > a, li:contains(السنة) a").text().getIntFromText()
        val tags = doc.select("ul > li:contains(النوع) > a, li:contains(النوع) a").map { it.text() }
        val recommendations = doc.select("div.related-posts > ul > li, div.BlockItem").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            val seasonLinks = doc.select("div.seasons-list a")
            
            val episodes = if (seasonLinks.isNotEmpty()) {
                seasonLinks.mapNotNull {
                    val sUrl = it.attr("abs:href")
                    val sNum = it.text().getIntFromText() ?: 1
                    val sDoc = app.get(sUrl, headers = requestHeaders).document
                    val episodeElements = sDoc.select("div.episodes-list a")
                    episodeElements.mapNotNull { ep ->
                        val epUrl = ep.attr("abs:href")
                        val epNum = ep.text().getIntFromText() ?: 1
                        newEpisode(epUrl) {
                            this.name = "Episode $epNum"
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = posterUrl
                            this.description = synopsis
                        }
                    }
                }.flatten()
            } else {
                val episodeElements = doc.select("div.episodes-list a")
                val seasonNum = url.substringAfter("-s", "").substringBefore("/").toIntOrNull() ?: 1
                episodeElements.mapNotNull { ep ->
                    val epUrl = ep.attr("abs:href")
                    val epNum = ep.text().getIntFromText() ?: 1
                    newEpisode(epUrl) {
                        this.name = "Episode $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = posterUrl
                        this.description = synopsis
                    }
                }
            }

            val distinctEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                distinctEpisodes
            ) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.year = year
                this.recommendations = recommendations
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1500..2500).random().toLong())
        
        val doc = app.post(data, data = mapOf("View" to "1"), headers = requestHeaders).document
        doc.select(".donwload-servers-list > li, ul.download a").forEach { element ->
            val url = element.select("a").attr("href")
            loadExtractor(url, data, subtitleCallback, callback)
        }
        doc.select("ul.serversList > li, div.ServersList li").forEach { li ->
            val iframeUrl = li.attr("data-link")
            if(iframeUrl.isNotEmpty()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
