package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class CimaNowProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)



    // Cache loaded pages per section
    private val sectionPageCache = mutableMapOf<String, MutableMap<Int, List<SearchResponse>>>()

    // Track last page per section (auto stop)
    private val sectionLastPage = mutableMapOf<String, Int>()

    private val sectionPaginationMap = mutableMapOf<String, String>()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.value?.toIntOrNull()
    }

    private fun String.cleanHtml(): String {
        return this.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // FIXED: When element is already an <a> tag (from .owl-item a selector), use it directly
        val isAnchor = this.tagName().equals("a", ignoreCase = true)
        val url = if (isAnchor) {
            fixUrlNull(attr("href"))
        } else {
            // Element is a container (article, div), find anchor inside
            fixUrlNull(selectFirst("a")?.attr("href"))
        } ?: run {
            return null
        }
        
        // Skip news articles
        if (url.contains("/news/")) {
            return null
        }

        // Skip coming soon & placeholder pages
        if (
            url.contains("coming-soon", true) ||
            url.contains("قريبا", true)
        ) {
            return null
        }
        
        // Get all images in order
        val allImages = select("img")
        
        // For poster and title: Many sections have TWO images:
        // - First image is often "logo" overlay
        // - Second image is the actual poster with title in alt
        // We need to find the correct image that's NOT the logo
        var posterUrl = ""
        var altTitle = ""
        
        for (img in allImages) {
            val alt = img.attr("alt")?.trim() ?: ""
            // Skip logo images
            if (alt.equals("logo", ignoreCase = true) || alt.isEmpty()) {
                continue
            }
            // Found a non-logo image with an alt text
            // Check data-src first (lazy loading), then src
            posterUrl = img.attr("data-src")?.ifBlank { null } ?: img.attr("src") ?: ""
            altTitle = alt
            break
        }
        
        // Fallback: if no good image found, use first image anyway
        if (posterUrl.isEmpty() && allImages.isNotEmpty()) {
            val firstImg = allImages.first()
            posterUrl = firstImg?.attr("data-src")?.ifBlank { null } ?: firstImg?.attr("src") ?: ""
        }
        
        // Title extraction with multiple fallbacks
        var title = select("li[aria-label=\"title\"]").text().trim()
        if (title.isEmpty()) {
            title = altTitle
        }
        if (title.isEmpty()) {
            // Last resort: first non-logo image's alt
            title = allImages.firstOrNull()?.attr("alt")?.takeIf { !it.equals("logo", ignoreCase = true) } ?: ""
        }
        if (title.isEmpty()) {
            title = text().cleanHtml().trim()
        }
        
        // Skip items with no meaningful title
        if (title.isEmpty() || title.equals("logo", ignoreCase = true)) {
            return null
        }
        
        val year = select("li[aria-label=\"year\"]").text().toIntOrNull() 
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            
        val isMovie = url.contains(Regex("فيلم|مسرحية|حفلات")) || 
                      select("li[aria-label=\"tab\"]").text().contains("افلام")
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        val qualityText = select("li[aria-label=\"ribbon\"]").firstOrNull()?.text()
        
        // Block posts that don't contain watching or eps check by badge text
        val statusText = select("li[aria-label=\"status\"]").text()
        if (statusText.contains("قريبا")) return null
        
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

    private suspend fun loadSectionPage(
        sectionName: String,
        baseUrl: String,
        page: Int
    ): List<SearchResponse> {
        // Stop if we already know this is beyond last page
        val last = sectionLastPage[sectionName]
        if (last != null && page > last) return emptyList()

        // Return cached page if exists
        sectionPageCache[sectionName]?.get(page)?.let {
            return it
        }

        val url = "$baseUrl/page/$page/"
        val doc = app.get(url, headers = mapOf("user-agent" to "MONKE")).document

        val items = doc.select(
            ".owl-item a, .owl-body a, .item article, article[aria-label='post']"
        ).mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        // If empty → this is the LAST page
        if (items.isEmpty()) {
            sectionLastPage[sectionName] = page - 1
            return emptyList()
        }

        // Cache result
        val sectionCache =
            sectionPageCache.getOrPut(sectionName) { mutableMapOf() }
        sectionCache[page] = items

        return items
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Page 1 Logic
        if (page == 1) {
            val doc = app.get("$mainUrl/home/", headers = mapOf("user-agent" to "MONKE")).document
            sectionPaginationMap.clear()
            sectionPageCache.clear()
            sectionLastPage.clear()

            val allSections = doc.select("section")
            
            val homePageLists = allSections.mapNotNull { section ->
                // Extract section title
                val sectionName = section.selectFirst("div.title h1, h2, h3, h4, span.title, .section-title, .title, h1")
                    ?.text()?.trim() ?: return@mapNotNull null
                
                // News Filter
                if (sectionName.contains("إقرا الخبر") || 
                    sectionName.contains("الأخبار") || 
                    section.select("a[href*='/news/']").isNotEmpty()) {
                    return@mapNotNull null
                }
                
                // Pagination Base Extraction
                val paginationBase = section
                    .selectFirst("a[href*='/page/'], a[href*='/category/'], a[href*='/الاحدث/']")
                    ?.attr("href")
                    ?.substringBefore("/page/")
                    ?.let { fixUrl(it) }

                if (paginationBase != null) {
                    sectionPaginationMap[sectionName] = paginationBase
                }

                val items = section.select(".owl-item a, .owl-body a, .item article, article[aria-label='post']")
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (items.isEmpty()) return@mapNotNull null

                // Cache page 1
                val sectionCache = sectionPageCache.getOrPut(sectionName) { mutableMapOf() }
                sectionCache[1] = items

                HomePageList(
                    sectionName,
                    items
                )
            }.toList()

            return newHomePageResponse(homePageLists)
        }
        
        // Page > 1 Logic
        val base = sectionPaginationMap[request.name]
            ?: return newHomePageResponse(emptyList())

        val items = loadSectionPage(
            sectionName = request.name,
            baseUrl = base,
            page = page
        )

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items
                )
            )
        )
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

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("DEBUG loadLinks: Loading URL: $data")
            
            // Remove /watching/ suffix to get the main page URL
            val mainPageUrl = data.replace("/watching/", "/").replace("//", "/")
                .replace("https:/", "https://")
            println("DEBUG loadLinks: Main page URL: $mainPageUrl")
            
            // Fetch the main page to get the post ID from shortlink
            val mainDoc = app.get(mainPageUrl, headers = mapOf("Referer" to mainUrl)).document
            
            // Extract post ID from shortlink: <link rel='shortlink' href='https://cimanow.cc/?p=123456' />
            var postId = mainDoc.selectFirst("link[rel='shortlink']")?.attr("href")
                ?.substringAfter("?p=")?.takeIf { it.isNotBlank() }
            
            // Fallback: try to find postid from body class like "postid-123456"
            if (postId == null) {
                postId = mainDoc.selectFirst("body")?.className()
                    ?.split(" ")?.find { it.startsWith("postid-") }
                    ?.substringAfter("postid-")
            }
            
            println("DEBUG loadLinks: Found post ID: $postId")
            
            var foundLinks = 0
            
            if (postId != null) {
                // Server indices to try via AJAX
                val serverIndices = listOf(
                    "00", // CimaNow main (first priority)
                    "33",
                    "34",
                    "35",
                    "31", // Vidguard
                    "32", // Filemoon
                    "7",  // OK
                    "12"  // Uqload (last)
                )
                
                for (index in serverIndices) {
                    try {
                        val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                        println("DEBUG loadLinks: Calling AJAX: $ajaxUrl")
                        
                        val response = app.get(
                            ajaxUrl,
                            headers = mapOf(
                                "Referer" to mainPageUrl,
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).text
                        
                        // Parse iframe src from response
                        val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                        
                        if (iframeSrc.isNotEmpty()) {
                            println("DEBUG loadLinks: Found iframe from AJAX (index $index): $iframeSrc")
                            
                            var fullSrc = iframeSrc
                            if (fullSrc.startsWith("//")) {
                                fullSrc = "https:$iframeSrc"
                            }
                            
                            // Priority extraction for CimaNowTV
                            if (fullSrc.contains("cimanowtv.com")) {
                                val tempLinks = mutableListOf<ExtractorLink>()
                                loadExtractor(
                                    fullSrc,
                                    subtitleCallback
                                ) { link ->
                                    tempLinks.add(link)
                                }
                                
                                tempLinks.forEach { link ->
                                    callback.invoke(
                                        newExtractorLink(
                                            "CimaNow (Main)",
                                            "CimaNow (Main)",
                                            link.url,
                                            if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = link.referer
                                            this.quality = link.quality
                                        }
                                    )
                                }
                                foundLinks++
                                continue
                            }

                            loadExtractor(fullSrc, subtitleCallback, callback)
                            foundLinks++
                        } else {
                            // Fallback: Extract CPM links directly (no iframe) for CimaNowTV
                            val cpmMatch = Regex("""https://[^"']+cimanowtv\.com/e/[^"']+""")
                                .find(response)?.value

                            if (cpmMatch != null) {
                                println("DEBUG loadLinks: Found CPM match from AJAX (index $index): $cpmMatch")
                                val tempLinks = mutableListOf<ExtractorLink>()
                                loadExtractor(
                                    cpmMatch,
                                    subtitleCallback
                                ) { link ->
                                    tempLinks.add(link)
                                }
                                
                                tempLinks.forEach { link ->
                                    callback.invoke(
                                        newExtractorLink(
                                            "CimaNow (Main)",
                                            "CimaNow (Main)",
                                            link.url,
                                            if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = link.referer
                                            this.quality = link.quality
                                        }
                                    )
                                }
                                foundLinks++
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG loadLinks: AJAX error for index $index: ${e.message}")
                    }
                }
            }
            
            println("DEBUG loadLinks: Found $foundLinks streaming links via AJAX")
            
            // If AJAX failed, try the original iframe scraping approach as fallback
            if (foundLinks == 0) {
                println("DEBUG loadLinks: AJAX failed, trying fallback iframe scraping...")
                val doc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
                
                val iframeSelectors = listOf(
                    "ul#watch li[aria-label=\"embed\"] iframe",
                    "ul#watch li iframe", 
                    "ul#watch iframe",
                    "#watch iframe",
                    "iframe[src*='embed']",
                    "iframe[data-src*='embed']",
                    "iframe[src*='player']",
                    "iframe[data-src*='player']"
                )
                
                for (selector in iframeSelectors) {
                    val iframes = doc.select(selector)
                    iframes.forEach { iframe ->
                        val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                        if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                            val fullSrc = if (src.startsWith("//")) "https:$src" else src
                            loadExtractor(fullSrc, subtitleCallback, callback)
                            foundLinks++
                        }
                    }
                    if (foundLinks > 0) break
                }
            }
            
            // Extract download links from the watching page
            val doc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
            val downloadBlocks = doc.select("ul#download li[aria-label=\"quality\"].box")
            downloadBlocks.forEach { qualityBlock ->
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
            
            return foundLinks > 0
        } catch (e: Exception) {
            println("DEBUG loadLinks: Exception: ${e.message}")
            return false
        }
    }
}