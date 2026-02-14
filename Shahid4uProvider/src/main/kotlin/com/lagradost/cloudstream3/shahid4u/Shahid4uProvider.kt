package com.lagradost.cloudstream3.shahid4u

import com.lagradost.cloudstream3.SubtitleFile

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.amap
import org.jsoup.nodes.Element

class Shahid4uProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://shahhid4u.boats"
    override var name = "Shahid4u"
    override val usesWebView = false
    override val hasMainPage = true
	private  val cfKiller = CloudflareKiller()
    private fun updateMainUrl(url: String) {
        val domain = Regex("""^(?:https?:\/\/)?(?:[^@\n]+@)?(?:www\.)?([^:\/\n\?\=]+)""").find(url)?.groupValues?.get(1)
        if (domain != null && !mainUrl.contains(domain)) {
            mainUrl = if (url.startsWith("https")) "https://$domain" else "http://$domain"
        }
    }
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
        val response = app.get(request.data + page)
        updateMainUrl(response.url)
        var doc = response.document
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
        val response = app.get(url)
        updateMainUrl(response.url)
        var doc = response.document
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
                  seasonTabs.amap { tab ->
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
        val allUrls = mutableSetOf<String>()
        val allEmbeds = mutableSetOf<String>()

        var doc = app.get(data).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(data, interceptor = cfKiller, timeout = 60).document
        }

        // 1. Collect from current page
        collectLinks(doc, allUrls, allEmbeds)

        // 2. Discover sub-pages
        val subPages = mutableListOf<String>()
        doc.select("a[href*='play.php']").attr("href").takeIf { it.isNotBlank() }?.let { subPages.add(fixUrl(it)) }
        doc.select("a.btnDowns[href*='downloads.php']").attr("href").takeIf { it.isNotBlank() }?.let { subPages.add(fixUrl(it)) }
        doc.select("a.xtgo, a:contains(سيرفرات المشاهدة), a:contains(مشاهدة)").attr("href").takeIf { it.isNotBlank() && !it.contains("topvideos.php") }?.let { 
            val fUrl = fixUrl(it)
            if (!subPages.contains(fUrl)) subPages.add(fUrl) 
        }

        // 3. Fetch sub-pages in parallel
        subPages.amap { url ->
            val subDoc = app.get(url, referer = data).document
            collectLinks(subDoc, allUrls, allEmbeds)
        }

        // 4. Resolve embeds
        allEmbeds.forEach { embed ->
            Regex("src=['\"](.*?)['\"]").find(embed)?.groupValues?.get(1)?.let { allUrls.add(it) }
        }

        // 5. Filter and load extractors in parallel
        val blockedDomains = listOf(
            "google.com", "mediafire.com", "mega.nz", "uptobox.com", "4shared.com", 
            "facebook.com", "ads", "userscloud.com", "nitroflare.com", "rapidgator.net", 
            "uploaded.net", "turbobit.net", "uploadgi.com"
        )
        
        allUrls.filter { url ->
            url.startsWith("http") && !url.contains("javascript") && blockedDomains.none { url.contains(it) }
        }.distinct().amap { url ->
            loadExtractor(url, data, subtitleCallback, callback)
        }

        return true
    }

    private fun collectLinks(doc: Element, urls: MutableSet<String>, embeds: MutableSet<String>) {
        // Broad selector to catch various server list formats
        doc.select("ul.list_servers li, ul.list_embedded li, ul.downloadlist li, .download-sec a, .btnDowns, li[id^='server_'], a[data-embed], a[data-url], a[data-link]").forEach { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a")
            val embedData = el.attr("data-embed").ifBlank { a?.attr("data-embed") ?: "" }
            val dataUrl = el.attr("data-url").ifBlank { a?.attr("data-url") ?: "" }
            val dataLink = el.attr("data-link").ifBlank { a?.attr("data-link") ?: "" }
            var href = el.attr("href").ifBlank { a?.attr("href") ?: "" }
            
            if (href.startsWith("//")) href = "https:$href"
            if (href.startsWith("/") && href.length > 1) href = mainUrl + href

            if (embedData.isNotBlank()) embeds.add(embedData)
            if (dataUrl.isNotBlank()) urls.add(fixUrl(dataUrl))
            if (dataLink.isNotBlank()) urls.add(fixUrl(dataLink))
            if (href.isNotBlank() && href.startsWith("http")) urls.add(href)
        }
        
        // Also check iframes directly on page
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("ads") && !src.contains("facebook")) {
                urls.add(fixUrl(src))
            }
        }
    }
}
