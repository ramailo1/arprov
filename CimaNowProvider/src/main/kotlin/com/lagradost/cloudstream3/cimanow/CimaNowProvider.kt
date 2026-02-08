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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaNowProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

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

    private val sectionPaginationMap = mutableMapOf<String, String>()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.value?.toIntOrNull()
    }

    private fun String.cleanHtml(): String = this.replace(Regex("<[^>]*>"), "").trim()

    private fun Element.toSearchResponse(): SearchResponse? {
        val isAnchor = tagName().equals("a", ignoreCase = true)
        val url = if (isAnchor) fixUrlNull(attr("href")) else fixUrlNull(selectFirst("a")?.attr("href"))
        if (url == null || url.contains("/news/") || url.contains("coming-soon", true)) return null

        val images = select("img")
        val posterImg = images.filter { 
            val src = it.attr("src").lowercase()
            !src.contains("logo") && !src.contains("user")
        }.firstOrNull() ?: images.firstOrNull()

        val posterUrl = posterImg?.attr("data-src")?.ifBlank { null } 
            ?: posterImg?.attr("src")?.ifBlank { null } ?: ""
            
        val title = select("li[aria-label=\"title\"]").text().takeIf { it.isNotBlank() } 
            ?: attr("aria-label").takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.cleanHtml()
            ?: posterImg?.attr("alt") 
            ?: text().cleanHtml()

        if (title.isNullOrBlank() || title.equals("logo", true)) return null

        val year = select("li[aria-label=\"year\"]").text().toIntOrNull()
        val isSeries = title.contains("مسلسل") || url.contains("مسلسل")
        val finalType = if (isSeries) TvType.TvSeries else TvType.Movie
        val quality = getQualityFromString(select("li[aria-label=\"ribbon\"]").text())

        return newMovieSearchResponse(title, url, finalType) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (page == 1) {
                sectionPaginationMap.clear()
                val homePageLists = mutableListOf<HomePageList>()
                coroutineScope {
                    val deferredLists = homeSections.map { (name, url) ->
                        async {
                            try {
                                val doc = app.get(url).document
                                val items = doc.select("section > article[aria-label='post']")
                                    .mapNotNull { it.toSearchResponse() }
                                    .distinctBy { it.url }
                                if (items.isNotEmpty()) {
                                    sectionPaginationMap[name] = url
                                    HomePageList(name, items)
                                } else null
                            } catch (e: Exception) { null }
                        }
                    }
                    homePageLists.addAll(deferredLists.awaitAll().filterNotNull())
                }
                newHomePageResponse(homePageLists)
            } else {
                val base = sectionPaginationMap[request.name] ?: return newHomePageResponse(emptyList())
                val doc = app.get("$base/page/$page/").document
                val items = doc.select("section > article[aria-label='post']")
                    .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
                newHomePageResponse(listOf(HomePageList(request.name, items)))
            }
        } catch (e: Exception) { newHomePageResponse(emptyList()) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/page/1/?s=$query").document
        return doc.select("section > article[aria-label='post']")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    private fun detectTvType(url: String, doc: Document): TvType {
        if (doc.select("ul#eps li").isNotEmpty()) return TvType.TvSeries
        val pageTitle = doc.select("title").text()
        if (pageTitle.contains("مسلسل") || pageTitle.contains("برنامج")) return TvType.TvSeries
        return if (url.contains("/selary/") || url.contains("/episode/")) TvType.TvSeries else TvType.Movie
    }

    private fun extractSeasons(doc: Document): List<Pair<Int, String>> =
        doc.select("section[aria-label='seasons'] ul li a").mapNotNull { a ->
            val seasonUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val seasonNum = Regex("""الموسم\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            seasonNum to seasonUrl
        }.distinctBy { it.first }.sortedBy { it.first }

    private suspend fun loadSeasonEpisodes(seasonNumber: Int, seasonUrl: String): List<Episode> {
        val doc = app.get(seasonUrl).document
        return doc.select("ul#eps li").mapNotNull { ep ->
            val epUrl = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epNum = ep.select("a em").text().toIntOrNull()
            val epName = ep.select("a img:nth-child(2)").attr("alt").ifBlank { "Episode $epNum" }
            val epPoster = ep.select("a img:nth-child(2)").attr("src")
            newEpisode(epUrl.trimEnd('/')) {
                this.name = epName
                this.episode = epNum
                this.season = seasonNumber
                this.posterUrl = epPoster
            }
        }
    }

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
                for ((seasonNum, seasonUrl) in seasons) { episodes += loadSeasonEpisodes(seasonNum, seasonUrl) }
            } else { episodes += loadSeasonEpisodes(1, url) }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { Pair(it.season ?: 1, it.episode ?: 0) }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })) {
                this.posterUrl = posterUrl; this.plot = synopsis; this.year = year; this.tags = tags; this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url.trimEnd('/')) {
                this.posterUrl = posterUrl; this.plot = synopsis; this.year = year; this.tags = tags; this.recommendations = recommendations
            }
        }
    }

    /** 
     * loadLinks using pure HTTP/AJAX approach
     * 
     * CimaNow Flow:
     * 1. Get post ID from main page shortlink or body class
     * 2. Call AJAX endpoint: core.php?action=switch&index=X&id=Y
     * 3. Parse iframe from response
     * 4. Use loadExtractor on iframe src
     */
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
                // Server indices to try via AJAX (browser analysis confirmed these)
                val serverIndices = listOf(
                    "00", // CimaNow main (first priority)
                    "33",
                    "34",
                    "35",
                    "31", // Vidguard (Added from browser inspection)
                    "66", // Upnshare
                    "32", // Filemoon
                    "7",  // OK
                    "30", // VK
                    "12"  // Uqload
                )
                
                for (index in serverIndices) {
                    try {
                        val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                        // println("DEBUG loadLinks: Calling AJAX: $ajaxUrl")
                        
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
                                loadExtractor(fullSrc, subtitleCallback) { link ->
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
                                loadExtractor(cpmMatch, subtitleCallback) { link ->
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
                        // Continue to next server index
                    }
                }
            }
            
            // Extract download links from the watching page
            val downloadDoc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
            
            // 1. Extract quality links (ul#download li[aria-label="quality"] a)
            downloadDoc.select("ul#download li[aria-label='quality'] a").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    // Extract text quality, removing size info usually in <p> tag if present
                    val sizeText = link.select("p").text()
                    val fullText = link.text()
                    val qualityText = fullText.replace(sizeText, "").trim()
                    
                    val quality = qualityText.getIntFromText() ?: Qualities.Unknown.value
                    
                    // Identify if it's a direct stream or external link
                    if (url.contains("cimanowtv.com") || url.contains("worldcdn.online")) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "Download - $qualityText",
                                url,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = quality
                            }
                        )
                        foundLinks++
                    } else {
                        // External hosts
                        loadExtractor(url, subtitleCallback, callback)
                        foundLinks++
                    }
                }
            }

            // 2. Extract other download servers (ul#download li[aria-label="download"] a)
            downloadDoc.select("ul#download li[aria-label='download'] a").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    loadExtractor(url, subtitleCallback, callback)
                    foundLinks++
                }
            }
            
            println("DEBUG loadLinks: Found $foundLinks streaming/download links")

            // Fallback: Try direct iframe scraping from watching page
            if (foundLinks == 0) {
                println("DEBUG loadLinks: Link count 0, trying fallback scraping")
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
                            println("DEBUG loadLinks: Found fallback iframe: $fullSrc")
                            loadExtractor(fullSrc, subtitleCallback, callback)
                            foundLinks++
                        }
                    }
                    if (foundLinks > 0) break
                }
            }
            
            return foundLinks > 0
        } catch (e: Exception) {
            println("DEBUG loadLinks: Exception: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
