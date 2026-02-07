package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaNowProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    /** --- Modular Section Config --- */
    private val homeSections = mapOf(
        "أحدث الإضافات" to "$mainUrl/الاحدث",
        "أحدث المسلسلات العربية" to "$mainUrl/category/مسلسلات-عربية",
        "أحدث المسلسلات الاجنبية" to "$mainUrl/category/مسلسلات-اجنبية",
        "أحدث المسلسلات التركية" to "$mainUrl/category/مسلسلات-تركية",
        "أحدث الافلام العربية" to "$mainUrl/category/افلام-عربية",
        "أحدث الافلام الاجنبية" to "$mainUrl/category/افلام-اجنبية",
        "أحدث الافلام التركية" to "$mainUrl/category/افلام-تركية",
        "أحدث الافلام الهندية" to "$mainUrl/category/افلام-هندية",
        "أحدث الافلام الانيميشن" to "$mainUrl/category/افلام-انيميشن",
        "أحدث البرامج التلفزيونية" to "$mainUrl/category/البرامج-التلفزيونية",
        "أحدث المسرحيات" to "$mainUrl/category/مسرحيات",
        "أحدث الحفلات" to "$mainUrl/category/حفلات"
    )

    /** --- Cache for Pagination --- */
    private val sectionPageCache = mutableMapOf<String, MutableMap<Int, List<SearchResponse>>>()
    private val sectionLastPage = mutableMapOf<String, Int>()
    private val sectionPaginationMap = mutableMapOf<String, String>()

    /** --- Utils --- */
    private fun String.getIntFromText(): Int? = Regex("""\d+""").find(this)?.value?.toIntOrNull()
    private fun String.cleanHtml(): String = this.replace(Regex("<[^>]*>"), "").trim()

    /** --- Convert HTML element to SearchResponse --- */
    private fun Element.toSearchResponse(): SearchResponse? {
        val isAnchor = tagName().equals("a", ignoreCase = true)
        val url = if (isAnchor) fixUrlNull(attr("href")) else fixUrlNull(selectFirst("a")?.attr("href"))
        
        if (url == null || url.contains("/news/") || url.contains("coming-soon", true) || url.contains("قريبا")) return null

        val images = select("img")
        // Find first image that isn't "logo"
        val posterImg = images.firstOrNull { 
            val alt = it.attr("alt")
            alt.isNotBlank() && !alt.equals("logo", true) 
        }
        
        // Priority: data-src -> src
        val posterUrl = posterImg?.attr("data-src")?.ifBlank { null } 
            ?: posterImg?.attr("src")?.ifBlank { null }
            ?: images.firstOrNull()?.attr("src") 
            ?: ""
            
        // Title: Aria Label -> Alt -> Text
        val title = select("li[aria-label=\"title\"]").text().takeIf { it.isNotBlank() } 
            ?: posterImg?.attr("alt") 
            ?: text().cleanHtml()

        if (title.isBlank() || title.equals("logo", true)) return null

        val year = select("li[aria-label=\"year\"]").text().toIntOrNull() 
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        // Type Detection Logic
        val isSeries = title.contains("مسلسل") || title.contains("برنامج") ||
                url.contains("مسلسل") || url.contains("برنامج") ||
                select("li[aria-label=\"tab\"]").text().let { t -> t.contains("مسلسلات") || t.contains("برامج") }
        
        val isEpisodes = title.contains("الحلقة") || select("li[aria-label=\"episode\"]").isNotEmpty()
        
        val finalType = if (isEpisodes) {
            TvType.TvSeries
        } else if (isSeries) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        val quality = getQualityFromString(select("li[aria-label=\"ribbon\"]").text())

        return newMovieSearchResponse(title, url, finalType) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    /** --- Load a single section page --- */
    private suspend fun loadSectionPage(sectionName: String, baseUrl: String, page: Int): List<SearchResponse> {
        val last = sectionLastPage[sectionName]
        if (last != null && page > last) return emptyList()
        sectionPageCache[sectionName]?.get(page)?.let { return it }

        val url = "$baseUrl/page/$page/"
        val doc = app.get(url, headers = mapOf("user-agent" to "MONKE")).document

        // Unified Selector (V8)
        val items = doc.select(
            ".owl-item a, .owl-body a, .item article, article[aria-label='post'], article[aria-label='post'] a"
        ).mapNotNull { it.toSearchResponse() }.distinctBy { it.url }

        // V8: Harden Pagination Logic (Check for Next Page)
        val hasNextPage = doc.select("ul[aria-label='pagination'] li a")
            .any { it.text().toIntOrNull() == page + 1 || it.attr("aria-label").contains("Next") }

        if (items.isEmpty() && !hasNextPage) {
            sectionLastPage[sectionName] = page - 1
            return emptyList()
        }
        
        println("DEBUG CimaNow: Page=$page | Section=$sectionName | Items=${items.size} | NextPage=$hasNextPage")

        sectionPageCache.getOrPut(sectionName) { mutableMapOf() }[page] = items
        return items
    }

    /** --- Main Page --- */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (page == 1) {
                sectionPaginationMap.clear()
                sectionPageCache.clear()
                sectionLastPage.clear()

                val homePageLists = mutableListOf<HomePageList>()

                coroutineScope {
                    val deferredLists = homeSections.map { (name, url) ->
                        async {
                            try {
                                val doc = app.get(url, headers = mapOf("user-agent" to "MONKE")).document
                                // Unified selector matching loadSectionPage
                                val items = doc.select(
                                    ".owl-item a, .owl-body a, .item article, article[aria-label='post'], article[aria-label='post'] a"
                                )
                                .mapNotNull { it.toSearchResponse() }
                                .distinctBy { it.url }
                                
                                if (items.isNotEmpty()) {
                                    sectionPaginationMap[name] = url
                                    println("DEBUG CimaNow: Page=1 | Section=$name | Items=${items.size}")
                                    HomePageList(name, items)
                                } else {
                                    println("DEBUG CimaNow: Page=1 | Section=$name | Items=0 (Empty)")
                                    null
                                }
                            } catch (e: Exception) { 
                                println("DEBUG CimaNow: Failed to fetch section '$name': ${e.message}")
                                null 
                            }
                        }
                    }
                    homePageLists.addAll(deferredLists.awaitAll().filterNotNull())
                }
                newHomePageResponse(homePageLists)
            } else {
                val base = sectionPaginationMap[request.name] ?: return newHomePageResponse(emptyList())
                val items = loadSectionPage(request.name, base, page)
                newHomePageResponse(listOf(HomePageList(request.name, items)))
            }
        } catch (e: Exception) {
            println("DEBUG getMainPage error: ${e.message}")
            newHomePageResponse(emptyList())
        }
    }

    /** --- Search --- */
    override suspend fun search(query: String): List<SearchResponse> {
        val result = arrayListOf<SearchResponse>()
        val doc = app.get("$mainUrl/page/1/?s=$query").document
        
        doc.select(".owl-item a, .owl-body a, .item article, article[aria-label='post'] > a")
            .forEach { it.toSearchResponse()?.let(result::add) }

        val maxPage = doc.selectFirst("ul[aria-label='pagination']")?.select("li")?.not("li.active")?.lastOrNull()?.text()?.toIntOrNull() ?: 1
        
        if (maxPage > 1) {
             val limit = maxPage.coerceAtMost(5)
             (2..limit).forEach { i ->
                val pDoc = app.get("$mainUrl/page/$i/?s=$query").document
                pDoc.select(".owl-item a, .owl-body a, .item article, article[aria-label='post'] > a")
                    .forEach { it.toSearchResponse()?.let(result::add) }
            }
        }

        return result.distinctBy { it.url }.sortedBy { it.name }
    }

    /** --- Detect if URL is a Movie or Series --- */
    private fun detectTvType(url: String, doc: Document): TvType {
        if (doc.select("ul#eps li").isNotEmpty()) return TvType.TvSeries
        
        val pageTitle = doc.select("title").text()
        if (pageTitle.contains("مسلسل") || pageTitle.contains("برنامج")) return TvType.TvSeries
        
        val categories = doc.select("article ul li a").joinToString(" ") { it.text() }
        
        return when {
            categories.contains("مسلسلات") -> TvType.TvSeries
            categories.contains("افلام") || categories.contains("مسرحيات") || categories.contains("حفلات") -> TvType.Movie
            url.contains("/selary/") || url.contains("/episode/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    /** --- Extract Seasons for Series --- */
    private fun extractSeasons(doc: Document): List<Pair<Int, String>> =
        doc.select("section[aria-label='seasons'] ul li a")
            .mapNotNull { a ->
                val seasonUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val seasonNum = Regex("""الموسم\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                seasonNum to seasonUrl
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

    /** --- Load Episodes for a Season --- */
    private suspend fun loadSeasonEpisodes(seasonNumber: Int, seasonUrl: String): List<Episode> {
        val doc = app.get(seasonUrl).document
        return doc.select("ul#eps li").mapNotNull { ep ->
            val epUrl = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epNum = ep.select("a em").text().toIntOrNull()
            val epName = ep.select("a img:nth-child(2)").attr("alt").ifBlank { "Episode $epNum" }
            val epPoster = ep.select("a img:nth-child(2)").attr("src")
            newEpisode("$epUrl/watching/") {
                this.name = epName
                this.episode = epNum
                this.season = seasonNumber
                this.posterUrl = epPoster
            }
        }
    }

    /** --- Load Movie or Series Page --- */
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val posterUrl = doc.select("meta[property=\"og:image\"]").attr("content")
        val year = doc.select("article ul:nth-child(1) li a").lastOrNull()?.text()?.toIntOrNull()
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull() ?: ""
        val synopsis = doc.select("ul#details li:contains(لمحة) p").text()
        val tags = doc.selectFirst("article ul")?.select("li")?.map { it.text() }
        val recommendations = doc.select("ul#related li").mapNotNull { el ->
            val recUrl = fixUrlNull(el.select("a").attr("href")) ?: return@mapNotNull null
            val recPoster = el.select("img:nth-child(2)").attr("src")
            val recName = el.select("img:nth-child(2)").attr("alt")
            newMovieSearchResponse(recName, recUrl, TvType.Movie) { this.posterUrl = recPoster }
        }

        val tvType = detectTvType(url, doc)

        return if (tvType == TvType.TvSeries) {
            val seasons = extractSeasons(doc)
            val episodes = mutableListOf<Episode>()
            if (seasons.isNotEmpty()) {
                for ((seasonNum, seasonUrl) in seasons) {
                    episodes += loadSeasonEpisodes(seasonNum, seasonUrl)
                }
            } else {
                episodes += loadSeasonEpisodes(1, url)
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$url/watching/") {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    /** --- Load Streaming Links Modular --- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val mainPageUrl = data.replace("/watching/", "/").replace("//", "/").replace("https:/", "https://")
            val mainDoc = app.get(mainPageUrl, headers = mapOf("Referer" to mainUrl)).document

            var postId = mainDoc.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("?p=")
                ?: mainDoc.selectFirst("body")?.className()?.split(" ")?.find { it.startsWith("postid-") }?.substringAfter("postid-")

            if (postId.isNullOrBlank()) return false

            val serverIndices = listOf("00", "33", "34", "35", "31", "32", "7", "12")
            var foundLinks = 0

            // Try AJAX servers in parallel
            // V10: Parallel Execution
            val ajaxResults = coroutineScope {
                serverIndices.map { index ->
                    async {
                        try {
                            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                            val response = app.get(ajaxUrl, headers = mapOf("Referer" to mainPageUrl, "X-Requested-With" to "XMLHttpRequest")).text
                            val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                            if (iframeSrc.isNotBlank()) {
                                val fullSrc = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                                loadExtractor(fullSrc, subtitleCallback, callback)
                                true
                            } else {
                                false
                            }
                        } catch (_: Exception) {
                            false
                        }
                    }
                }.awaitAll()
            }
            
            foundLinks += ajaxResults.count { it }

            // Fallback: Scrape iframe directly from page
            if (foundLinks == 0) {
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
                for (sel in iframeSelectors) {
                    val iframes = doc.select(sel)
                    iframes.forEach { iframe ->
                        val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                        if (src.isNotBlank()) {
                            val fullSrc = if (src.startsWith("//")) "https:$src" else src
                            loadExtractor(fullSrc, subtitleCallback, callback)
                            foundLinks++
                        }
                    }
                    if (foundLinks > 0) break
                }
            }

            // Download links
            val doc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
            doc.select("ul#download li[aria-label=\"quality\"].box, ul#download li[aria-label=\"download\"] a").forEach { block ->
                block.select("a").forEach { link ->
                    val url = link.attr("href")
                    if (url.isBlank()) return@forEach
                    val qualityText = link.selectFirst("i")?.nextSibling()?.toString()?.trim() ?: ""
                    val quality = qualityText.getIntFromText() ?: Qualities.Unknown.value
                    callback.invoke(newExtractorLink(this.name, qualityText.ifBlank { "Download" }, url, ExtractorLinkType.VIDEO) { this.referer = data; this.quality = quality })
                }
            }

            return foundLinks > 0
        } catch (e: Exception) {
            println("DEBUG loadLinks Exception: ${e.message}")
            return false
        }
    }
}