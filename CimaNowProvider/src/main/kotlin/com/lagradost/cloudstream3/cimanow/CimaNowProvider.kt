package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class CimaNowProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.value?.toIntOrNull()
    }

    private fun String.cleanHtml(): String {
        return this.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Handle both direct anchor elements and article containers
        val anchor = selectFirst("picture a") ?: selectFirst("a") ?: this
        val url = fixUrlNull(anchor.attr("href")) ?: fixUrlNull(attr("href")) ?: run {
            println("DEBUG toSearchResponse: No URL found, anchor.href='${anchor.attr("href")}', this.href='${attr("href")}'")
            return null
        }
        
        val posterUrl = selectFirst("img")?.attr("data-src")
            ?.ifBlank { selectFirst("img")?.attr("src") } ?: ""
        
        var title = select("li[aria-label=\"title\"]").text().trim()
        if (title.isEmpty()) {
            title = selectFirst("img")?.attr("alt") ?: ""
        }
        if (title.isEmpty()) {
            title = text().cleanHtml().trim()
        }
        
        println("DEBUG toSearchResponse: url=$url, title='${title.take(30)}', poster=${posterUrl.take(50)}")
        
        val year = select("li[aria-label=\"year\"]").text().toIntOrNull() 
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            
        val isMovie = url.contains(Regex("فيلم|مسرحية|حفلات")) || 
                      select("li[aria-label=\"tab\"]").text().contains("افلام")
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        val qualityText = select("li[aria-label=\"ribbon\"]").firstOrNull()?.text()
        val quality = getQualityFromString(qualityText)
        
        return newMovieSearchResponse(
            title,
            url,
            tvType,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home/", headers = mapOf("user-agent" to "MONKE")).document
        
        // DEBUG: Log total sections found
        val allSections = doc.select("section")
        println("DEBUG CimaNow: Total sections found: ${allSections.size}")
        
        val pages = allSections
            .filter { 
                val text = it.text()
                val shouldFilter = text.contains(Regex("أختر وجهتك المفضلة|تم اضافته حديثاً"))
                if (shouldFilter) println("DEBUG CimaNow: Filtering section with text: ${text.take(50)}")
                !shouldFilter
            }
            .mapNotNull { section ->
                val rawNameElement = section.selectFirst("span") ?: section.selectFirst("h2, h3, .title") ?: run {
                    println("DEBUG CimaNow: No name element found in section")
                    return@mapNotNull null
                }
                val nameElement = rawNameElement.clone()
                nameElement.select("img, em, i, a, svg, picture").remove()
                var name = nameElement.text().trim().cleanHtml()
                if (name.isEmpty()) {
                    name = rawNameElement.ownText().trim().cleanHtml()
                }
                if (name.isEmpty()) {
                    println("DEBUG CimaNow: Empty name after cleaning")
                    return@mapNotNull null
                }
                
                println("DEBUG CimaNow: Processing section: $name")
                
                // Items are in Owl Carousel: .owl-item a or .owl-body a
                val itemSelector = ".owl-item a, .owl-body a, .item article, article[aria-label=\"post\"]"
                val items = section.select(itemSelector)
                println("DEBUG CimaNow: Found ${items.size} items with selector: $itemSelector")
                
                val list = items.mapNotNull { element -> 
                    val result = element.toSearchResponse()
                    if (result == null) {
                        println("DEBUG CimaNow: toSearchResponse returned null for element: ${element.tagName()}")
                    }
                    result
                }
                
                println("DEBUG CimaNow: Created ${list.size} SearchResponse objects for section: $name")
                
                if (list.isEmpty()) {
                    println("DEBUG CimaNow: Empty list for section: $name")
                    return@mapNotNull null
                }
                HomePageList(name, list)
            }
        
        println("DEBUG CimaNow: Total pages to return: ${pages.size}")
        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val result = arrayListOf<SearchResponse>()
        val doc = app.get("$mainUrl/page/1/?s=$query").document
        val paginationElement = doc.selectFirst("ul[aria-label=\"pagination\"]")
        
        doc.select(".owl-item a, .owl-body a, .item article, article[aria-label=\"post\"]").forEach {
            it.toSearchResponse()?.let { res -> result.add(res) }
        }
        
        if (paginationElement != null) {
            val max = paginationElement.select("li").not("li.active")
                .lastOrNull()?.text()?.toIntOrNull()
            if (max != null && max <= 5) {
                (2..max).forEach { pageNum ->
                    app.get("$mainUrl/page/$pageNum/?s=$query").document
                        .select(".owl-item a, .owl-body a, .item article, article[aria-label=\"post\"]").forEach { element ->
                            element.toSearchResponse()?.let { res -> result.add(res) }
                        }
                }
            }
        }
        return result.distinctBy { it.url }.sortedBy { it.name }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val posterUrl = doc.select("meta[property=\"og:image\"]").attr("content")
        val year = doc.select("article ul:nth-child(1) li a").lastOrNull()?.text()?.toIntOrNull()
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull() ?: ""
        val isMovie = !url.contains("/selary/") && title.contains(Regex("فيلم|حفلات|مسرحية"))
        val youtubeTrailer = doc.select("iframe[src*='youtube']").attr("src")

        val synopsis = doc.select("ul#details li:contains(لمحة) p").text()
        val tags = doc.selectFirst("article ul")?.select("li")?.map { it.text() }

        val recommendations = doc.select("ul#related li").mapNotNull { element ->
            val recUrl = fixUrlNull(element.select("a").attr("href")) ?: return@mapNotNull null
            val recPoster = element.select("img:nth-child(2)").attr("src")
            val recName = element.select("img:nth-child(2)").attr("alt")
            newMovieSearchResponse(recName, recUrl, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                "$url/watching/"
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                // if (youtubeTrailer.isNotEmpty()) addTrailer(youtubeTrailer)
            }
        } else {
            val episodes = doc.select("ul#eps li").mapNotNull { episode ->
                val epUrl = fixUrlNull(episode.select("a").attr("href")) ?: return@mapNotNull null
                val epName = episode.select("a img:nth-child(2)").attr("alt")
                val epNum = episode.select("a em").text().toIntOrNull()
                val epPoster = episode.select("a img:nth-child(2)").attr("src")
                
                newEpisode("$epUrl/watching/") {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, 
                episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations
                // if (youtubeTrailer.isNotEmpty()) addTrailer(youtubeTrailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
            
            // Extract streaming servers from #watch tab
            doc.select("ul#watch li[aria-label=\"embed\"] iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, subtitleCallback, callback)
                }
            }
            
            // Extract download links from #download tab
            doc.select("ul#download li[aria-label=\"quality\"].box").forEach { qualityBlock ->
                val serverName = qualityBlock.selectFirst("span")?.text()?.trim() ?: "Download"
                
                qualityBlock.select("a").forEach { link ->
                    val url = link.attr("href")
                    if (url.isBlank()) return@forEach
                    
                    val qualityText = link.selectFirst("i")?.nextSibling()?.toString()?.trim() ?: ""
                    val quality = qualityText.getIntFromText() ?: Qualities.Unknown.value
                    
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "$serverName - $qualityText",
                            url,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = quality
                        }
                    )
                }
            }
            
            // Extract additional download servers from li[aria-label="download"]
            doc.select("ul#download li[aria-label=\"download\"] a").forEach { link ->
                val url = link.attr("href")
                if (url.isBlank()) return@forEach
                
                val serverName = link.text().trim()
                
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "Download - $serverName",
                        url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
}