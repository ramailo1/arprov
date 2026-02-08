@file:Suppress("DEPRECATION")

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
    override val usesWebView = true
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
        if (url == null || url.contains("/news/") || url.contains("coming-soon", true) || url.contains("قريبا", true)) return null

        val images = select("img")
        
        // For poster and title: Many sections have TWO images:
        // - First image is often "logo" overlay
        // - Second image is the actual poster with title in alt
        var posterUrl = ""
        var altTitle = ""

        for (img in images) {
            val alt = img.attr("alt")?.trim() ?: ""
            if (alt.equals("logo", ignoreCase = true) || alt.isEmpty()) continue
            posterUrl = img.attr("data-src")?.ifBlank { null } ?: img.attr("src") ?: ""
            altTitle = alt
            break
        }

        if (posterUrl.isEmpty() && images.isNotEmpty()) {
            val firstImg = images.first()
            posterUrl = firstImg?.attr("data-src")?.ifBlank { null } ?: firstImg?.attr("src") ?: ""
        }
            
        var title = select("li[aria-label=\"title\"]").text().trim()
        if (title.isEmpty()) title = altTitle
        if (title.isEmpty()) title = images.firstOrNull()?.attr("alt")?.takeIf { !it.equals("logo", ignoreCase = true) } ?: ""
        if (title.isEmpty()) title = text().cleanHtml().trim()

        if (title.isNullOrBlank() || title.equals("logo", true)) return null

        val year = select("li[aria-label=\"year\"]").text().toIntOrNull() 
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        // Robust Type Detection from user snippet
        val isSeries = 
            title.contains("مسلسل") || 
            title.contains("برنامج") ||
            url.contains("مسلسل") ||
            url.contains("برنامج") ||
            select("li[aria-label=\"tab\"]").text().let { t -> 
                t.contains("مسلسلات") || t.contains("برامج")
            }

        val isMovie = !isSeries && (
            url.contains(Regex("فيلم|مسرحية|حفلات")) || 
            select("li[aria-label=\"tab\"]").text().contains("افلام")
        )
        
        val tvType = if (isSeries) TvType.TvSeries else if (isMovie) TvType.Movie else TvType.Movie

        // If it looks like an Episode (has "الحلقة"), force Series
        val finalType = if (title.contains("الحلقة") || select("li[aria-label=\"episode\"]").isNotEmpty()) {
             TvType.TvSeries
        } else {
             tvType
        }

        val quality = getQualityFromString(select("li[aria-label=\"ribbon\"]").text())
        val statusText = select("li[aria-label=\"status\"]").text()
        if (statusText.contains("قريبا")) return null

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
            newEpisode("$epUrl/watching/") {
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
            newMovieLoadResponse(title, url, TvType.Movie, "$url/watching/") {
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
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("DEBUG loadLinks: Loading URL: $data")
            
            val seenUrls = mutableSetOf<String>()
            var validLinksCount = 0
            
            val callbackWrapper: (ExtractorLink) -> Unit = { link ->
                if (link.url.isNotBlank() && seenUrls.add(link.url)) {
                    validLinksCount++
                    callback(link)
                }
            }
            
            // Remove /watching/ suffix to get the main page URL
            val mainPageUrl = data.replace("/watching/", "/").replace("//", "/")
                .replace("https:/", "https://")
            
            println("DEBUG loadLinks: Main page URL: $mainPageUrl")
            
            // Fetch both pages to find post ID
            val mainDoc = app.get(mainPageUrl, headers = mapOf("Referer" to mainUrl)).document
            val watchingDoc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
            
            // Extract post ID
            var postId: String? = null
            val docs = listOf(mainDoc, watchingDoc)
            for (doc in docs) {
                postId = doc.selectFirst("link[rel='shortlink']")?.attr("href")
                    ?.substringAfter("?p=")?.takeIf { it.isNotBlank() }
                
                if (postId == null) {
                    postId = doc.selectFirst("body")?.className()
                        ?.split(" ")?.find { it.startsWith("postid-") }
                        ?.substringAfter("postid-")
                }
                
                if (postId != null) {
                    println("DEBUG loadLinks: Found post ID: $postId")
                    break
                }
            }
            
            if (postId == null) println("DEBUG loadLinks: Could not find post ID")
            
            // 1. AJAX Extraction (Case A)
            if (postId != null) {
                val serverIndices = listOf(
                    "00", "33", "34", "35", "31", "66", "32", "7", "30", "12"
                )
                
                for (index in serverIndices) {
                    try {
                        val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                        val response = app.get(
                            ajaxUrl,
                            headers = mapOf(
                                "Referer" to mainPageUrl,
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).text
                        
                        val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                        if (iframeSrc.isNotEmpty()) {
                            val fullSrc = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                            loadExtractor(fullSrc, subtitleCallback, callbackWrapper)
                        } else {
                            // CimaNowTV CPM fallback
                            val cpmMatch = Regex("""https://[^"']+cimanowtv\.com/e/[^"']+""")
                                .find(response)?.value
                            if (cpmMatch != null) {
                                loadExtractor(cpmMatch, subtitleCallback, callbackWrapper)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore individual AJAX failures
                    }
                }
            }
            
            // 2. Static Iframe Extraction (Case B)
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
                val iframes = watchingDoc.select(selector)
                iframes.forEach { iframe ->
                    val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                    if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                        val fullSrc = if (src.startsWith("//")) "https:$src" else src
                        println("DEBUG loadLinks: Found static iframe: $fullSrc")
                        loadExtractor(fullSrc, subtitleCallback, callbackWrapper)
                    }
                }
            }

            // 3. Download Links (Case C)
            val downloadBlocks = watchingDoc.select("ul#download li[aria-label=\"quality\"].box, ul#download li[aria-label='quality'] a")
            downloadBlocks.forEach { element ->
                val links = if(element.tagName() == "a") listOf(element) else element.select("a")
                
                links.forEach { link ->
                    val url = link.attr("href")
                    if (url.isNotBlank()) {
                        val sizeText = link.select("p").text()
                        val fullText = link.text()
                        val qualityText = fullText.replace(sizeText, "").trim()
                        val quality = qualityText.getIntFromText() ?: Qualities.Unknown.value
                        
                        if (url.contains("cimanowtv.com") || url.contains("worldcdn.online")) {
                             callbackWrapper(
                                newExtractorLink(
                                    this.name,
                                    "Download - $qualityText",
                                    url,
                                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = quality
                                }
                            )
                        } else {
                            loadExtractor(url, subtitleCallback, callbackWrapper)
                        }
                    }
                }
            }

            watchingDoc.select("ul#download li[aria-label=\"download\"] a").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                     loadExtractor(url, subtitleCallback, callbackWrapper)
                }
            }
            
            println("DEBUG loadLinks: Total valid links found: $validLinksCount")
            return validLinksCount > 0
        } catch (e: Exception) {
            println("DEBUG loadLinks: Exception: ${e.message}")
            e.printStackTrace()
            return false
        }
    }


}
