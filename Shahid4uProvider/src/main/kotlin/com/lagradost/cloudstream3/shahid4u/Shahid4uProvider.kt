package com.lagradost.cloudstream3.shahid4u

import com.lagradost.cloudstream3.SubtitleFile

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class Shahid4uProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://shahhid4u.boats"
    override var name = "Shahid4u"
    override val usesWebView = false
    override val hasMainPage = true
	private  val cfKiller = CloudflareKiller()
    override val supportedTypes =
        setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)
	
	private fun String.getDomainFromUrl(): String? {
        return Regex("""^(?:https?:\/\/)?(?:[^@\n]+@)?(?:www\.)?([^:\/\n\?\=]+)""").find(this)?.groupValues?.firstOrNull()
    }
    
    private fun String.getImageURL(): String? {
        return this.replace(".*url\\s*\\([\"']?|[\"']?\\);?".toRegex(), "")
    }

    private fun Element.extractPoster(): String? {
        return this.selectFirst(".postImgBg")?.attr("style")?.getImageURL()
            ?: this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("data-image") }.ifEmpty { img.attr("src") }
            }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val urlElement = selectFirst("a.fullClick, a.ellipsis, .caption h3 a") ?: return null
        val posterUrl = extractPoster()
        val quality = select("span.quality").text().replace("1080p |-".toRegex(), "")
        val type = if (select(".category").text().contains("افلام")) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(
            urlElement.attr("title").ifEmpty { urlElement.text() }
                .replace("برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(), ""),
            urlElement.attr("href") ?: return null,
            type,
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
            "$mainUrl/home1" to "جديد الموقع",
            "$mainUrl/movies.php?&page=" to " أحدث الأفلام",
            "$mainUrl/all-series.php?&page=" to " أحدث المسلسلات",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var doc = app.get(request.data + page).document
		if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(request.data + page, interceptor = cfKiller, timeout = 120).document
        }
        val list = doc.select("li.col-xs-6, div.content-box")
            .mapNotNull { element ->
                element.toSearchResponse()
            }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val finalResult = arrayListOf<SearchResponse>()
        for (url in listOf(
            "$mainUrl/?s=$query&category=&type=movie",
            "$mainUrl/?s=$query&type=series"
        )) {
            var doc = app.get(url).document
			if(doc.select("title").text() == "Just a moment...") {
				doc = app.get(url, interceptor = cfKiller, timeout = 120).document
			}
			for (it in doc.select("li.col-xs-6, div.content-box")) {
                finalResult.add(it.toSearchResponse() ?: continue)
            }
        }
        return finalResult
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }
        val isMovie = doc.selectFirst("ul.breadcrumbNav")?.text()?.contains("افلام") == true
            || doc.select("dl.dl-horizontal dd a").any { it.text().contains("افلام") }

        val posterUrl = doc.selectFirst(".video-bibplayer-poster")?.attr("style")?.getImageURL()
            ?: doc.selectFirst(".poster-image")?.attr("style")?.getImageURL()
            ?: doc.selectFirst(".poster img")?.attr("src")

        val title = doc.select("h1.post-title, h1").text().trim()
        
        val year = Regex("(\\d{4})").find(title)?.value?.toIntOrNull()
            ?: doc.select("ul.half-tags:contains(السنة) li:nth-child(2)").text().toIntOrNull()

        val cleanTitle = title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(), "").trim()

        val tags = doc.select("dl.dl-horizontal dd a").map { it.text() }
        
        val recommendations = doc.select("div.MediaGrid").first()?.select("div.content-box")?.mapNotNull { it.toSearchResponse() }
        
        val synopsis = doc.select("div.description p, div[itemprop='description'], div.post-story p").text().trim()

        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            val seasonTabs = doc.select(".Tab .tablinks")
            if (seasonTabs.isNotEmpty()) {
                 seasonTabs.forEach { tab ->
                     val seasonName = tab.text()
                     val seasonNum = seasonName.filter { it.isDigit() }.toIntOrNull() ?: 1
                     val tabId = tab.attr("onclick").substringAfter("'").substringBefore("'")
                     
                     doc.select("#$tabId a").forEach { epLink ->
                         val href = epLink.attr("href")
                         if (href.isNotBlank()) {
                             episodes.add(newEpisode(href) {
                                 this.name = epLink.attr("title").ifEmpty { epLink.text() }
                                 this.season = seasonNum
                                 this.episode = this.name?.filter { it.isDigit() }?.toIntOrNull()
                             })
                         }
                     }
                 }
            } else {
                val allEpisodesLink = doc.select("div.btns:contains(جميع الحلقات) a").attr("href")
                if (allEpisodesLink.isNotEmpty()) {
                    val episodesDoc = app.get(allEpisodesLink).document
                    episodesDoc.select("div.row > div, .content-box").forEachIndexed { index, element ->
                       val epUrl = element.select("a.fullClick").attr("href")
                       if(epUrl.isNotBlank()) {
                           episodes.add(newEpisode(epUrl) {
                               this.name = element.select("a.fullClick").attr("title")
                               this.episode = index + 1
                           })
                       }
                    }
                } else {
                    val seasonElements = doc.select("div.MediaGrid")
                     val seasonGrid = if (seasonElements.size > 1) seasonElements[1] else seasonElements.firstOrNull()
                     
                     seasonGrid?.select("div.content-box")?.forEach { seasonBox ->
                         val seasonNum = seasonBox.select("div.number em").text().toIntOrNull() ?: 1
                         val seasonUrl = seasonBox.select("a.fullClick").attr("href")
                         
                         if (seasonUrl.isNotBlank()) {
                            val seasonDoc = app.get(seasonUrl).document
                            seasonDoc.select(".episode-block, .content-box").forEach { ep ->
                                val epLink = ep.select("a").attr("href")
                                 if(epLink.isNotBlank()) {
                                     episodes.add(newEpisode(epLink) {
                                         this.name = ep.select("div.title").text()
                                         this.season = seasonNum
                                         this.episode = ep.select("div.number em").text().toIntOrNull()
                                         this.posterUrl = ep.select("div.poster img").attr("data-image")
                                     })
                                 }
                            }
                         }
                     }
                }
            }
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
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
        var doc = app.get(data).document
        if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(data, interceptor = cfKiller, timeout = 60).document
        }

        // 1. Prioritize a direct "Watch" or "Server List" button on the page
        // The btnDowns usually leads to the actual server list on downloads.php
        val serverListUrl = doc.select("a.btnDowns[href*='downloads.php']").attr("href")
        
        if (serverListUrl.isNotBlank()) {
             val serverDoc = app.get(serverListUrl, referer = data).document
             extractServers(serverDoc, serverListUrl, subtitleCallback, callback)
        }

        // 2. Fallback: Try other "Watch Now" buttons only if they point to different pages
        val genericWatchUrl = doc.select("a.xtgo, a:contains(سيرفرات المشاهدة)").attr("href")
        
        if (genericWatchUrl.isNotBlank() && 
            !genericWatchUrl.contains("topvideos.php") && 
            genericWatchUrl != serverListUrl) {
             val watchDoc = app.get(genericWatchUrl, referer = data).document
             extractServers(watchDoc, genericWatchUrl, subtitleCallback, callback)
        }

        // 3. Always check the initial page itself
        extractServers(doc, data, subtitleCallback, callback)

        return true
    }

    private suspend fun extractServers(
        doc: Element,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Broad selector to catch various server list formats
        // .btnDowns in this context usually means download mirros which we want to be CAREFUL with
        // ul.downloadlist is common on the downloads.php page
        val elements = doc.select("ul.list_servers li, ul.list_embedded li, ul.downloadlist li, .download-sec a")
        
        // Use parallel mapping for speed
        elements.map { el ->
             val a = if (el.tagName() == "a") el else el.selectFirst("a")
             val embedData = el.attr("data-embed").ifBlank { a?.attr("data-embed") ?: "" }
             var href = el.attr("href").ifBlank { a?.attr("href") ?: "" }
             
             // Normalize relative URLs
             if (href.startsWith("//")) href = "https:$href"
             if (href.startsWith("/")) href = mainUrl + href

             if (embedData.isNotBlank()) {
                 val iframeSrc = Regex("src=['\"](.*?)['\"]").find(embedData)?.groupValues?.get(1)
                 if (!iframeSrc.isNullOrBlank()) {
                     loadExtractor(iframeSrc, referer, subtitleCallback, callback)
                 }
             } else if (href.isNotBlank() && !href.contains("javascript") && href.startsWith("http")) {
                 // Filter: Only load if it looks like a streaming host or if it's explicitly a mirror
                 // Avoid raw download sites that don't support streaming well in Cloudstream extractors
                 val blockedDomains = listOf("google.com", "mediafire.com", "mega.nz", "uptobox.com", "4shared.com")
                 if (blockedDomains.none { href.contains(it) }) {
                     loadExtractor(href, referer, subtitleCallback, callback)
                 }
             }
        }
        
        // Also check iframes directly on page
        doc.select("iframe").forEach { iframe ->
             val src = iframe.attr("src")
             if (src.isNotBlank() && !src.contains("ads") && !src.contains("facebook")) {
                 loadExtractor(src, referer, subtitleCallback, callback)
             }
        }
    }
}
