package com.lagradost.cloudstream3.tuk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.amap
import org.jsoup.nodes.Element
import android.util.Base64

class TukProvider : MainAPI() {
    override var mainUrl = "https://tuk.cam"
    override var name = "Tuk (Tuk Tuk Cinema)"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val cfKiller = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/recent/" to "المضاف حديثاً",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات",
        "$mainUrl/category/anime/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val doc = app.get(url).document
        
        val items = doc.select(".Block--Item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        
        return doc.select(".Block--Item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = this.selectFirst(".Block--Info h3")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst(".Poster--Block img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val type = if (href.contains("episode") || href.contains("series")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.post-title")?.text()?.trim() ?: return null
        val posterUrl = doc.selectFirst(".MainSingle .image img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        val plot = doc.selectFirst(".story p")?.text()?.trim()
        val year = doc.selectFirst(".RightTaxContent a[href*='release-year']")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val isSeries = (doc.selectFirst(".Episodes--Box, .Episodes--List, a[href*='/series/']") != null || url.contains("series")) && !doc.selectFirst(".catssection a[href*='/movies/']").let { it != null }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Try to find full list on the page
            doc.select(".allepcont .row a").forEach { ep ->
                val href = ep.attr("href")
                if (href.isNotBlank()) {
                    val epTitle = ep.selectFirst(".ep-info h2")?.text()?.trim() ?: ""
                    val epNum = ep.selectFirst(".epnum")?.ownText()?.filter { it.isDigit() }?.toIntOrNull() 
                        ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                    val epThumb = ep.selectFirst(".image img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }
                    }
                    
                    episodes.add(newEpisode(href) {
                        this.name = if (epNum != null) "Episode $epNum" else epTitle
                        this.episode = epNum
                        this.posterUrl = epThumb
                    })
                }
            }
            
            // If we are on an episode page and have few episodes, try to find the "Main Series" link in breadcrumbs to get full list
            if (episodes.size <= 1 && url.contains("episode")) {
                val seriesLink = doc.select(".breadcrumb a[href*='/series/']").attr("href")
                if (seriesLink.isNotBlank()) {
                    val seriesDoc = app.get(seriesLink).document
                    seriesDoc.select(".allepcont .row a").forEach { ep ->
                        val href = ep.attr("href")
                        if (href.isNotBlank()) {
                            val epTitle = ep.selectFirst(".ep-info h2")?.text()?.trim() ?: ""
                            val epNum = ep.selectFirst(".epnum")?.ownText()?.filter { it.isDigit() }?.toIntOrNull() 
                                ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                            val epThumb = ep.selectFirst(".image img")?.let { img ->
                                img.attr("data-src").ifBlank { img.attr("src") }
                            }
                            
                            episodes.add(newEpisode(href) {
                                this.name = if (epNum != null) "Episode $epNum" else epTitle
                                this.episode = epNum
                                this.posterUrl = epThumb
                            })
                        }
                    }
                }
            }
            
            // If still empty, add itself
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.episode = title.filter { it.isDigit() }.toIntOrNull()
                    this.posterUrl = posterUrl
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
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
        val watchUrl = if (data.endsWith("/watch/")) data else "${data.removeSuffix("/")}/watch/"
        val doc = try {
            app.get(watchUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        } catch (e: Exception) {
            try {
                app.get(data, headers = mapOf("Referer" to "$mainUrl/")).document
            } catch (e2: Exception) {
                return false
            }
        }

        doc.select(".watch--servers--list .server--item, [data-link], [data-linkbase64]").amap { li ->
            val b64 = li.attr("data-linkbase64")
            val rawLink = if (b64.isNotBlank()) {
                runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrDefault("")
            } else {
                li.attr("data-link")
            }

            val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
            if (link.isNotBlank() && !link.startsWith("javascript")) {
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
        }

        // Fallback: check all iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
            val link = if (src.startsWith("//")) "https:$src" else src
            if (link.isNotBlank() && !link.contains("ads") && !link.contains("youtube") && !link.startsWith("javascript")) {
                loadExtractor(link, watchUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
