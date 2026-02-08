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
import kotlinx.coroutines.withTimeout
import android.util.Base64
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.CommonActivity.showToast
import android.widget.Toast

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

        // V12: Smart Poster Selection
        val images = select("img")
        val posterImg = images.filter { 
            val src = it.attr("src").lowercase()
            val alt = it.attr("alt").lowercase()
            !src.contains("logo") && !src.contains("user") && 
            !alt.contains("logo") && !alt.contains("user")
        }.sortedByDescending { 
            it.attr("src").contains("cover", ignoreCase = true) || 
            it.attr("data-src").contains("cover", ignoreCase = true) 
        }.firstOrNull() ?: images.firstOrNull()

        val posterUrl = posterImg?.attr("data-src")?.ifBlank { null } 
            ?: posterImg?.attr("src")?.ifBlank { null }
            ?: ""
            
        // V11: Relaxed Title Extraction
        val title = select("li[aria-label=\"title\"]").text().takeIf { it.isNotBlank() } 
            ?: attr("aria-label").takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.cleanHtml()
            ?: selectFirst("strong")?.text()?.cleanHtml()
            ?: posterImg?.attr("alt") 
            ?: text().cleanHtml()

        if (title.isNullOrBlank() || title.equals("logo", true)) return null

        val year = select("li[aria-label=\"year\"]").text().toIntOrNull() 
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val isSeries = title.contains("مسلسل") || title.contains("برنامج") ||
                url.contains("مسلسل") || url.contains("برنامج") ||
                select("li[aria-label=\"tab\"]").text().let { t -> t.contains("مسلسلات") || t.contains("برامج") }
        
        val isEpisodes = title.contains("الحلقة") || select("li[aria-label=\"episode\"]").isNotEmpty()
        
        val finalType = if (isEpisodes || isSeries) TvType.TvSeries else TvType.Movie
        
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

        // V12: Strict Content Selector (section > article[aria-label='post'])
        val items = doc.select("section > article[aria-label='post']")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }

        val hasNextPage = doc.select("ul[aria-label='pagination'] li a")
            .any { it.text().toIntOrNull() == page + 1 || it.attr("aria-label").contains("Next") }

        if (items.isEmpty() && !hasNextPage) {
            sectionLastPage[sectionName] = page - 1
            return emptyList()
        }
        
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
                                // V12: Strict Content Selector
                                val items = doc.select("section > article[aria-label='post']")
                                    .mapNotNull { it.toSearchResponse() }
                                    .distinctBy { it.url }
                                
                                if (items.isNotEmpty()) {
                                    sectionPaginationMap[name] = url
                                    HomePageList(name, items)
                                } else {
                                    null
                                }
                            } catch (e: Exception) { 
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
            newHomePageResponse(emptyList())
        }
    }

    /** --- Search --- */
    override suspend fun search(query: String): List<SearchResponse> {
        val result = arrayListOf<SearchResponse>()
        val doc = app.get("$mainUrl/page/1/?s=$query").document
        
        // V12: Strict Selector for Search too
        val searchItems = doc.select("section > article[aria-label='post']")
            .mapNotNull { it.toSearchResponse() }
        for (item in searchItems) {
            result.add(item)
        }

        val maxPage = doc.selectFirst("ul[aria-label='pagination']")?.select("li")?.not("li.active")?.lastOrNull()?.text()?.toIntOrNull() ?: 1
        
        if (maxPage > 1) {
             val limit = maxPage.coerceAtMost(5)
             for (i in 2..limit) {
                val pDoc = app.get("$mainUrl/page/$i/?s=$query").document
                val items = pDoc.select("section > article[aria-label='post']")
                    .mapNotNull { it.toSearchResponse() }
                for (item in items) {
                    result.add(item)
                }
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
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { Pair(it.season ?: 1, it.episode ?: 0) }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })) {
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

    /** --- Deobfuscate CimaNow HTML --- */
    private fun deobfuscateHtml(html: String): String {
        println("CimaNow: " + "[DEOBFUSCATE] Starting deobfuscation, raw HTML length: ${html.length}")
        
        // CimaNow obfuscates HTML using Base64-encoded char codes with a shift
        // Note: The value can be split across multiple lines with '+' concatenation
        // Pattern matches: hide_my_HTML_ = 'data' + 'data' + ... or multiline single quotes
        val scriptPattern = Regex("""hide_my_HTML_\s*=\s*[\r\n\s]*'([^']+)'""")
        val match = scriptPattern.find(html)
        if (match == null) {
            println("CimaNow: " + "[DEOBFUSCATE] No obfuscation detected (hide_my_HTML_ not found)")
            return html // Not obfuscated
        }
        
        // Check if there are additional concatenated parts ('+' followed by more data)
        // Gather all parts from concatenated string literal
        var fullData = match.groupValues[1]
        val continuationPattern = Regex("""'\s*\+\s*[\r\n\s]*'([^']+)'""")
        var searchStart = match.range.last + 1
        while (searchStart < html.length) {
            val continuation = continuationPattern.find(html, searchStart)
            if (continuation != null && continuation.range.first <= searchStart + 10) {
                fullData += continuation.groupValues[1]
                searchStart = continuation.range.last + 1
            } else {
                break
            }
        }
        
        println("CimaNow: " + "[DEOBFUSCATE] Found obfuscated data pattern!")
        val parts = fullData.split(".")
        println("CimaNow: " + "[DEOBFUSCATE] Split into ${parts.size} parts")
        
        val decoded = StringBuilder()
        var successCount = 0
        var failCount = 0
        
        for (part in parts) {
            try {
                val decodedBytes = Base64.decode(part, Base64.DEFAULT)
                val numericStr = String(decodedBytes)
                val charCode = numericStr.toIntOrNull() ?: continue
                decoded.append((charCode - 87653).toChar())
                successCount++
            } catch (_: Exception) {
                failCount++
                continue
            }
        }
        
        val result = decoded.toString()
        println("CimaNow: " + "[DEOBFUSCATE] Decoded ${successCount} chars, failed ${failCount}")
        println("CimaNow: " + "[DEOBFUSCATE] Result length: ${result.length}, first 200 chars: ${result.take(200)}")
        
        return result.ifBlank { html }
    }

    /** --- Load Streaming Links Modular --- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("CimaNow: " + "========================================")
        println("CimaNow: " + "[LOADLINKS] Starting WebView extraction for URL: $data")
        println("CimaNow: " + "========================================")
        
        try {
            showToast("يرجى الانتظار بضع ثوانٍ حتى يبدأ المشغل...", Toast.LENGTH_SHORT)
            
            val extractedUrls = mutableSetOf<String>()
            var foundLinks = 0
            
            // JavaScript to extract video sources from the player after JS executes
            val extractionScript = """
                (function() {
                    var urls = [];
                    
                    try {
                        // DEBUG: Log initial state
                        urls.push('DEBUG|||Page Title: ' + document.title);
                        urls.push('DEBUG|||HTML Length: ' + document.body.innerHTML.length);
                        urls.push('DEBUG|||Iframes found: ' + document.querySelectorAll('iframe').length);
                        urls.push('DEBUG|||Videos found: ' + document.querySelectorAll('video').length);
                        urls.push('DEBUG|||Quality Buttons found: ' + document.querySelectorAll('[data-url], .quality-btn, .server-btn, ul#watch li').length);
                        
                        // DEBUG: Dump iframes src
                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            urls.push('DEBUG|||Iframe[' + i + '] src: ' + (iframes[i].src || 'none'));
                        }
                    } catch(e) {
                        urls.push('DEBUG|||Error in probe script: ' + e.toString());
                    }
                    
                    // Method 1: Look for video/source elements
                    var videos = document.querySelectorAll('video source, video');
                    for (var i = 0; i < videos.length; i++) {
                        var src = videos[i].src || videos[i].getAttribute('src');
                        if (src && src.length > 0) urls.push('Video|||' + src);
                    }
                    
                    // Method 2: Look for iframe sources
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        var src = iframes[i].src || iframes[i].getAttribute('src');
                        if (src && src.length > 0 && !src.includes('facebook') && !src.includes('twitter')) {
                            urls.push('Iframe|||' + src);
                        }
                    }
                    
                    // Method 3: Look for quality buttons with data-url
                    var buttons = document.querySelectorAll('[data-url], .quality-btn, .server-btn, ul#watch li');
                    for (var i = 0; i < buttons.length; i++) {
                        var url = buttons[i].getAttribute('data-url') || buttons[i].getAttribute('data-src');
                        var quality = buttons[i].innerText.trim() || 'Auto';
                        if (url) urls.push(quality + '|||' + url);
                    }
                    
                    // Method 4: Check for global player variables
                    if (window.playerSrc) urls.push('Player|||' + window.playerSrc);
                    if (window.videoSrc) urls.push('Video|||' + window.videoSrc);
                    if (window.hlsUrl) urls.push('HLS|||' + window.hlsUrl);
                    
                    return JSON.stringify(urls);
                })()
            """.trimIndent()
            
            // Create WebViewResolver that intercepts video URLs
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4|vidstream|embedsito|dood|upstream|streamtape|mixdrop"""),
                additionalUrls = listOf(
                    Regex("""\.m3u8(\?|$)"""),
                    Regex("""\.mp4(\?|$)"""),
                    Regex("""master\.m3u8"""),
                    Regex("""playlist\.m3u8""")
                ),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                script = extractionScript,
                scriptCallback = { result ->
                    try {
                        println("CimaNow: " + "[WEBVIEW PROBE] Script result length: ${result.length}")
                        val cleaned = result.trim('"').replace("\\\"", "\"")
                        if (cleaned.startsWith("[")) {
                            val urlList = cleaned.removeSurrounding("[", "]")
                                .split("\",\"")
                                .map { it.trim('"') }
                                .filter { it.contains("|||") }
                            
                            for (entry in urlList) {
                                if (entry.startsWith("DEBUG|||")) {
                                    println("CimaNow: " + "[WEBVIEW PROBE] $entry")
                                } else {
                                    extractedUrls.add(entry)
                                    println("CimaNow: " + "[WEBVIEW] Extracted: $entry")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("CimaNow ERROR: " + "[WEBVIEW] Script parse error: ${e.message}")
                    }
                },
                timeout = 60000L
            )
            
            println("CimaNow: " + "[WEBVIEW] Starting WebView resolution...")
            
            // Resolve using WebView
            val (mainRequest, additionalRequests) = resolver.resolveUsingWebView(
                requestCreator("GET", data, headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                ))
            )
            
            println("CimaNow: " + "[WEBVIEW] Resolution complete. Main request: ${mainRequest?.url}, Additional: ${additionalRequests.size}")
            
            // Process intercepted URLs from network requests
            val allRequests = listOfNotNull(mainRequest) + additionalRequests
            for (request in allRequests) {
                val videoUrl = request.url.toString()
                println("CimaNow: " + "[WEBVIEW] Intercepted URL: $videoUrl")
                
                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                    val qualityText = when {
                        videoUrl.contains("1080") -> "1080p"
                        videoUrl.contains("720") -> "720p"
                        videoUrl.contains("480") -> "480p"
                        videoUrl.contains("360") -> "360p"
                        else -> "Auto"
                    }
                    
                    val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "$name - $qualityText",
                            videoUrl,
                            linkType
                        ) {
                            this.referer = data
                            this.quality = getQualityFromText(qualityText)
                        }
                    )
                    foundLinks++
                    println("CimaNow: " + "[WEBVIEW] Added direct link: $qualityText - $videoUrl")
                } else if (videoUrl.contains("vidstream") || videoUrl.contains("embedsito") || 
                           videoUrl.contains("dood") || videoUrl.contains("upstream") ||
                           videoUrl.contains("streamtape") || videoUrl.contains("mixdrop")) {
                    // These are embed URLs, use loadExtractor
                    println("CimaNow: " + "[WEBVIEW] Loading extractor for: $videoUrl")
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                    foundLinks++
                }
            }
            
            // Give a small delay for script execution
            delay(500)
            
            // Process URLs extracted from DOM via script
            for (entry in extractedUrls) {
                val parts = entry.split("|||")
                if (parts.size == 2) {
                    val qualityText = parts[0]
                    val videoUrl = parts[1]
                    
                    if (videoUrl.startsWith("http")) {
                        if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                            val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "$name - $qualityText",
                                    videoUrl,
                                    linkType
                                ) {
                                    this.referer = data
                                    this.quality = getQualityFromText(qualityText)
                                }
                            )
                            foundLinks++
                            println("CimaNow: " + "[WEBVIEW] Added from script: $qualityText - $videoUrl")
                        } else if (qualityText == "Iframe" || videoUrl.contains("embed") || videoUrl.contains("player")) {
                            // Iframe source - use loadExtractor
                            println("CimaNow: " + "[WEBVIEW] Loading extractor for iframe: $videoUrl")
                            val fullUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                            foundLinks++
                        }
                    }
                }
            }
            
            // Fallback: Try original AJAX method if WebView didn't find anything
            if (foundLinks == 0) {
                println("CimaNow: " + "[FALLBACK] WebView found nothing, trying AJAX fallback...")
                foundLinks += tryAjaxFallback(data, subtitleCallback, callback)
            }
            
            println("CimaNow: " + "========================================")
            println("CimaNow: " + "[LOADLINKS] COMPLETE. Total links found: $foundLinks")
            println("CimaNow: " + "========================================")
            return foundLinks > 0
        } catch (e: Exception) {
            println("CimaNow ERROR: " + "[LOADLINKS] EXCEPTION: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun getQualityFromText(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            quality.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    private suspend fun tryAjaxFallback(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var foundLinks = 0
        try {
            val rawHtml = app.get(data).text
            val html = deobfuscateHtml(rawHtml)
            val doc = Jsoup.parse(html)
            
            // Try to extract Post ID
            val shortlinkHref = doc.select("link[rel='shortlink']").attr("href")
            val dataIdFromButton = doc.selectFirst("ul#watch li")?.attr("data-id") ?: ""
            val bodyClass = doc.select("body").attr("class")
            
            val postId = shortlinkHref.substringAfter("p=")
                .takeIf { it.all { c -> c.isDigit() } && it.isNotBlank() }
                ?: dataIdFromButton.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
                ?: bodyClass.split(" ").find { it.startsWith("postid-") }?.substringAfter("postid-")
            
            if (!postId.isNullOrBlank()) {
                for (index in listOf("00", "01", "02", "03")) {
                    try {
                        withTimeout(15000L) {
                            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                            val response = app.get(ajaxUrl, headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")).text
                            val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                            
                            if (iframeSrc.isNotBlank()) {
                                val fullSrc = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                                loadExtractor(fullSrc, subtitleCallback, callback)
                                foundLinks++
                            }
                        }
                    } catch (e: Exception) {
                        // Continue to next index
                    }
                }
            }
            
            // Try static iframes
            val iframes = doc.select("iframe")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("facebook") && !src.contains("twitter")) {
                    loadExtractor(src, subtitleCallback, callback)
                    foundLinks++
                }
            }
        } catch (e: Exception) {
            println("CimaNow ERROR: " + "[FALLBACK] Exception: ${e.message}")
        }
        return foundLinks
    }
}

