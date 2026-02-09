@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log
import android.util.Base64
import kotlin.text.Charsets
import com.lagradost.cloudstream3.utils.getQualityFromName

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

    private fun decodeHtml(doc: Document): Document {
        val docStr = doc.toString()
        if (!docStr.contains("hide_my_HTML_")) {
            return doc
        }
        val script = doc.selectFirst("script") ?: return doc
        val scriptData = script.data() ?: return doc

        var hideMyHtmlContent = scriptData.substringAfter("var hide_my_HTML_", "")
        if (hideMyHtmlContent.isBlank()) return doc

        hideMyHtmlContent = hideMyHtmlContent.substringAfter("=", "")
        hideMyHtmlContent = hideMyHtmlContent.substringBeforeLast("';", hideMyHtmlContent)
        hideMyHtmlContent = hideMyHtmlContent.replace(Regex("['+\\n\" ]"), "")

        val lastNumber = Regex("-\\d+").findAll(scriptData).lastOrNull()?.value?.toIntOrNull() ?: 0
        val decodedHtml1 = decodeObfuscatedString(hideMyHtmlContent, lastNumber)
        val encodedHtml = String(decodedHtml1.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

        return Jsoup.parse(encodedHtml)
    }

    private fun decodeObfuscatedString(concatenated: String, lastNumber: Int): String {
        val output = StringBuilder()
        var start = 0
        for (i in concatenated.indices) {
            if (concatenated[i] == '.') {
                decodeAndAppend(output, lastNumber, concatenated.substring(start, i))
                start = i + 1
            }
        }
        if (start < concatenated.length) {
            decodeAndAppend(output, lastNumber, concatenated.substring(start))
        }
        return output.toString()
    }

    private fun decodeAndAppend(output: StringBuilder, lastNumber: Int, segment: String) {
        try {
            val decodedBytes = try {
                Base64.decode(segment, Base64.DEFAULT)
            } catch (e: Exception) {
                null
            }
            if (decodedBytes != null) {
                val decoded = String(decodedBytes, Charsets.UTF_8)
                val digits = decoded.filter { it.isDigit() }
                if (digits.isNotEmpty()) {
                    val num = digits.toIntOrNull()
                    if (num != null) {
                        output.append((num + lastNumber).toChar())
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    private suspend fun handlecima(iframeUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            val iframeResponse = app.get(finalUrl, referer = finalUrl).text

            val regex = Regex("""\[(\d+p)]\s+(/uploads/[^\"]+\.mp4)""")
            val baseUrl = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: ""
            val links = mutableListOf<ExtractorLink>()
            regex.findAll(iframeResponse).forEach { match ->
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath

                links.add(
                    newExtractorLink(
                        source = "CimaNow",
                        name = "CimaNow",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ).apply {
                        this.referer = finalUrl
                        this.quality = getQualityFromName(qualityStr)
                    }
                )
            }
            links.sortByDescending { it.quality }
            links.forEach { callback(it) }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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

        val isSeries = 
            title.contains("مسلسل") || 
            title.contains("برنامج") ||
            url.contains("مسلسل") ||
            url.contains("برنامج") ||
            select("li[aria-label=\"category\"] a").text().contains("مسلسل") ||
            select("li[aria-label=\"category\"] a").text().contains("برنامج") ||
            select("li[aria-label=\"tab\"]").text().contains("مسلسلات") ||
            select("li[aria-label=\"tab\"]").text().contains("برامج")

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
                                val doc = decodeHtml(app.get(url).document)
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
                val doc = decodeHtml(app.get("$base/page/$page/").document)
                val items = doc.select("section > article[aria-label='post']")
                    .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
                newHomePageResponse(listOf(HomePageList(request.name, items)))
            }
        } catch (e: Exception) { newHomePageResponse(emptyList()) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = decodeHtml(app.get("$mainUrl/page/1/?s=$query").document)
        return doc.select("section > article[aria-label='post']")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    private fun detectTvType(url: String, doc: Document): TvType {
        if (doc.select("ul#eps li").isNotEmpty()) return TvType.TvSeries
        val pageTitle = doc.select("title").text()
        if (pageTitle.contains("مسلسل") || pageTitle.contains("برنامج")) return TvType.TvSeries
        return if (url.contains("/selary/") || url.contains("/series/") || url.contains("/episode/")) TvType.TvSeries else TvType.Movie
    }

    private fun extractSeasons(doc: Document): List<Pair<Int, String>> =
        doc.select("section[aria-label='seasons'] ul li a").mapNotNull { a ->
            val seasonUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val seasonNum = Regex("""الموسم\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            seasonNum to seasonUrl
        }.distinctBy { it.first }.sortedBy { it.first }

    private suspend fun loadSeasonEpisodes(seasonNumber: Int, seasonUrl: String): List<Episode> {
        val doc = decodeHtml(app.get(seasonUrl).document)
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
        val doc = decodeHtml(app.get(url).document)
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

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("DEBUG loadLinks: Loading URL: $data")

            val seenUrls = java.util.Collections.synchronizedSet(HashSet<String>())
            val validLinksCount = java.util.concurrent.atomic.AtomicInteger(0)

            // Wrapper to deduplicate and call original callback
            val callbackWrapper: (ExtractorLink) -> Unit = { link ->
                if (link.url.isNotBlank() && seenUrls.add(link.url)) {
                    validLinksCount.incrementAndGet()
                    callback(link)
                }
            }

            // Clean URL safely
            val mainPageUrl = data.removeSuffix("/watching/")
            println("DEBUG loadLinks: Main page URL: $mainPageUrl")

            val headers = mapOf("Referer" to mainUrl)
            val mainDoc = decodeHtml(app.get(mainPageUrl, headers = headers).document)
            // If data == mainPageUrl, we can reuse mainDoc, but keeping separate is safer for 'watching' pages
            val watchingDoc = if (data == mainPageUrl) mainDoc else decodeHtml(app.get(data, headers = headers).document)

            // Extract post ID safely from both docs
            val docs = listOf(mainDoc, watchingDoc)
            var postId: String? = null
            
            for (doc in docs) {
                postId = doc.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("p=")
                    ?: doc.selectFirst("input[name='comment_post_ID']")?.attr("value")
                    ?: doc.selectFirst("body")?.className()?.split(" ")?.find { it.startsWith("postid-") }
                        ?.substringAfter("postid-")
                
                if (!postId.isNullOrBlank()) break
            }

            println("DEBUG loadLinks: Post ID: $postId")

            // Detect theme path
            val themePath = Regex("""wp-content/themes/([^/]+)/""").find(watchingDoc.html())?.groupValues?.get(1)
                ?: "Cima%20Now%20New"
            println("DEBUG loadLinks: Theme Path: $themePath")

            // Helper suspend function to safely try extractor and wait
            suspend fun safeTryExtractor(url: String) {
                val trimmed = url.trim()
                if (trimmed.isBlank() || trimmed.contains("ads") || trimmed.contains("doubleclick") || trimmed.startsWith("javascript:")) return
                try {
                    val fixedUrl = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
                    loadExtractor(fixedUrl, subtitleCallback, callbackWrapper)
                } catch (e: Exception) {
                    // Ignore individual extractor failures
                }
            }

            coroutineScope {
                val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                // 1. AJAX Extraction (if postId exists)
                if (!postId.isNullOrBlank()) {
                    // Extract dynamic server list from the "Watch" tab
                    val serverElements = watchingDoc.select("ul#watch li")
                    
                    tasks.add(async {
                        serverElements.map { li ->
                            async {
                                try {
                                    val serverId = li.attr("data-index")
                                    // Skip if no ID or if it looks like an ad/fake tab
                                    if (serverId.isNotBlank()) {
                                        val serverName = li.text().trim()
                                        // Retry up to 3 times to simulate clicking through ads
                                        for (attempt in 1..3) {
                                            val ajaxUrl = "$mainUrl/wp-content/themes/$themePath/core.php?action=switch&index=$serverId&id=$postId"
                                            val response = app.get(ajaxUrl, headers = mapOf("Referer" to mainPageUrl, "X-Requested-With" to "XMLHttpRequest")).text
                                            val doc = Jsoup.parse(response)

                                            val urlsToTry = doc.select("iframe[src]").map { it.attr("src") } +
                                                listOfNotNull(Regex("""https://[^"']+cimanowtv\.com/e/[^"']+""").find(response)?.value)
                                            
                                            // If we found links, try them and break the retry loop
                                            if (urlsToTry.isNotEmpty()) {
                                                urlsToTry.map { url -> 
                                                    async { 
                                                        if (serverName.contains("Cima Now", true)) {
                                                            handlecima(url, callbackWrapper)
                                                        } else {
                                                            safeTryExtractor(url) 
                                                        }
                                                    } 
                                                }.awaitAll()
                                                break
                                            }
                                            // If no links found, it might have been an "ad" response, so retry
                                            delay(500)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Ignore individual AJAX failures
                                }
                            }
                        }.awaitAll()
                        Unit
                    })
                }

                // 2. Static iframe extraction
                val iframeSelectors = listOf(
                    "ul#watch li[aria-label=\"embed\"] iframe",
                    "ul#watch li iframe",
                    "#watch iframe",
                    "iframe[src*='embed']",
                    "iframe[data-src*='embed']",
                    "iframe[src*='player']",
                    "iframe[data-src*='player']"
                )

                tasks.add(async {
                    watchingDoc.select(iframeSelectors.joinToString(", ")).map { iframe ->
                        val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                        async { safeTryExtractor(src) }
                    }.awaitAll()
                    Unit
                })

                // 3. Download links (Parallelized as well)
                tasks.add(async {
                    watchingDoc.select("ul#download li[aria-label='quality'] a").map { link ->
                         async {
                            val url = link.attr("href")
                            if (url.isNotBlank()) {
                                val text = link.text().trim()
                                val sizeText = link.select("p").text()
                                val qualityText = text.replace(sizeText, "").trim()
                                val quality = qualityText.getIntFromText() ?: Qualities.Unknown.value

                                if (url.contains("cimanowtv.com") || url.contains("worldcdn.online")) {
                                    callbackWrapper(
                                        newExtractorLink(
                                            this@CimaNowProvider.name,
                                            "Download - $qualityText",
                                            url,
                                            if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) { this.referer = data; this.quality = quality }
                                    )
                                } else {
                                    safeTryExtractor(url)
                                }
                            }
                         }
                    }.awaitAll()
                    Unit
                })
                
                // standard download buttons
                tasks.add(async {
                     watchingDoc.select("ul#download li[aria-label=\"download\"] a").map { link ->
                        async {
                            val url = link.attr("href")
                            if (url.isNotBlank()) safeTryExtractor(url)
                        }
                     }.awaitAll()
                     Unit
                })

                tasks.awaitAll()
            }

            println("DEBUG loadLinks: Total valid links: ${validLinksCount.get()}")
            return validLinksCount.get() > 0

        } catch (e: Exception) {
            println("DEBUG loadLinks: Exception: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}


