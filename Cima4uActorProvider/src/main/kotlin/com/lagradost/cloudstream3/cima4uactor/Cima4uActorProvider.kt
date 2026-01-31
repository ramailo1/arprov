@file:Suppress("DEPRECATION")
package com.lagradost.cloudstream3.cima4uactor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class Cima4uActorProvider : MainAPI() {
    override var mainUrl = "https://cima4u.forum"
    override var name = "Cima4uActor"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Headers to bypass bot protection
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "جديد سيما فور يو",
        "$mainUrl/movies/" to "أفلام جديدة",
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/series/" to "مسلسلات جديدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url, headers = headers).document
        val home = document.select("div#MainFiltar > a.GridItem, .GridItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong")?.text()?.trim() 
            ?: this.attr("title").trim()
            ?: return null
            
        val href = fixUrl(this.attr("href"))
        val posterUrl = extractPosterUrl(this)
        
        // Check for episode badge
        val isEpisode = this.selectFirst("span.episode, span:contains(حلقة), .Episode--number") != null
        
        return if (isEpisode) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

   private fun extractPosterUrl(element: Element): String? {
    val style = element.attr("style")
    
    // Method 1: Extract from --image CSS variable
    if (style.contains("--image")) {
        val imageUrl = style.substringAfter("--image:", "")
            .substringBefore(";", "")
            .replace("url(", "")
            .replace(")", "")
            .replace("\"", "")
            .replace("'", "")
            .trim()
        if (imageUrl.isNotBlank() && imageUrl.startsWith("http")) {
            return fixUrl(imageUrl)
        }
    }
    
    // Method 2: Regular url() pattern
    val cssUrlRegex = Regex("""url\(['"]?([^'")]+)['"]?\)""")
    cssUrlRegex.find(style)?.groupValues?.getOrNull(1)?.trim()?.let {
        if (it.isNotBlank()) return fixUrl(it)
    }
    
    // Method 3: Check child .BG--GridItem
    element.selectFirst(".BG--GridItem")?.attr("style")?.let { childStyle ->
        if (childStyle.contains("--image")) {
            val imageUrl = childStyle.substringAfter("--image:", "")
                .substringBefore(";")
                .replace("url(", "")
                .replace(")", "")
                .replace("\"", "")
                .replace("'", "")
                .trim()
            if (imageUrl.isNotBlank() && imageUrl.startsWith("http")) {
                return fixUrl(imageUrl)
            }
        }
        cssUrlRegex.find(childStyle)?.groupValues?.getOrNull(1)?.trim()?.let {
            if (it.isNotBlank()) return fixUrl(it)
        }
    }
    
    // Fallback to img tags
    element.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }?.let { return fixUrl(it) }
    element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { return fixUrl(it) }
    
    return null
}


    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
        return document.select("div#MainFiltar > a.GridItem, .GridItem").mapNotNull { it.toSearchResult() }
    }

    @Suppress("DEPRECATION")
    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        // Log.d("Cima4u", "Loading URL: $fixedUrl") 
        val document = app.get(fixedUrl, headers = headers).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        
        // Extract poster from style attribute
        val posterUrl = document.selectFirst("[style*='--image']")?.let { extractPosterUrl(it) }
            ?: document.selectFirst("img[data-src]")?.attr("data-src")?.let { fixUrl(it) }
            ?: document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        
        val year = document.selectFirst("a[href*=release-year]")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.story p, div:contains(قصة العرض) + div")?.text()?.trim()
        
        val genres = document.select("a[href*=/genre/]").map { it.text() }
        val actors = document.select("a[href*=/actor/], a[href*=/producer/]").map { 
            Actor(it.text(), "")
        }
        
        // Rating logic removed due to deprecation/build errors
        // val rating = document.selectFirst("div.rating span, span.imdb")?.text()?.toRatingInt()
        
        val duration = document.selectFirst("span:contains(دقيقة)")?.text()
            ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
        
        // Check if it's a series
        val isSeries = document.selectFirst("a[href*=/series/]") != null || 
                       document.selectFirst("div.seasons") != null ||
                       url.contains("مسلسل") ||
                       title.contains("مسلسل")
        
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Get episodes from current page
            document.select("div.episodes-list a, div.season-episodes a, a:has(span.episode)").forEach { ep ->
                val epHref = ep.attr("href")
                val epName = ep.selectFirst("strong")?.text() ?: ep.text().trim()
                val epNum = ep.selectFirst("span.episode, span:contains(حلقة)")?.text()
                    ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                
                if (epHref.isNotEmpty()) {
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.episode = epNum
                        }
                    )
                }
            }
            
            // If no episodes found, try season links
            if (episodes.isEmpty()) {
                val seasonLinks = document.select("a[href*=/season/], a:contains(الموسم)")
                seasonLinks.forEach { seasonLink ->
                    val seasonHref = seasonLink.attr("href")
                    if(seasonHref.isNotEmpty()) {
                        val seasonDoc = app.get(seasonHref, headers = headers).document
                        seasonDoc.select("a.GridItem, a:has(span.episode)").forEach { ep ->
                            val epHref = ep.attr("href")
                            val epName = ep.selectFirst("strong")?.text() ?: ep.text().trim()
                            val epNum = ep.selectFirst("span.episode")?.text()
                                ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                            
                            if (epHref.isNotEmpty()) {
                                episodes.add(
                                    newEpisode(epHref) {
                                        this.name = epName
                                        this.episode = epNum
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = genres
                this.duration = duration
                addActors(actors)
                // this.rating = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = genres
                this.duration = duration
                addActors(actors)
                // this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        val servers = mutableListOf<Pair<String, String>>()
        
        // Extract server links from buttons and links
        document.select("ul#watch li[data-watch], a[href*=filemoon], a[href*=streamhg], a[href*=earnvids], a[href*=mixdrop], a[href*=dood], a[href*=forafile]").forEach { link ->
            val href = link.attr("data-watch").ifEmpty { link.attr("href") }
            val name = link.text().trim().ifEmpty { "Server" }
            if (href.isNotEmpty() && (href.startsWith("http") || href.startsWith("//"))) {
                servers.add(name to fixUrl(href))
            }
        }
        
        // Extract iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && (src.startsWith("http") || src.startsWith("//"))) {
                servers.add("Iframe" to fixUrl(src))
            }
        }
        
        // Process all servers
        servers.forEach { (name, url) ->
            loadExtractor(url, data, subtitleCallback, callback)
        }
        
        return true
    }
}