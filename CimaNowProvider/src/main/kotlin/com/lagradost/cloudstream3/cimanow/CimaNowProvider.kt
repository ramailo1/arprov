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
    override val usesWebView = false // Disabled to prevent double-WebView issues with WebViewResolver
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
            // Fixed URL double-slash issue
            newEpisode("${epUrl.trimEnd('/')}/watching/") {
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
            // Fixed URL double-slash issue
            newMovieLoadResponse(title, url, TvType.Movie, "${url.trimEnd('/')}/watching/") {
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
        println("CimaNow: " + "========================================")
        println("CimaNow: " + "[LOADLINKS] Starting WebView extraction for URL: $data")
        println("CimaNow: " + "========================================")
        
        try {
            showToast("يرجى الانتظار بضع ثوانٍ حتى يبدأ المشغل...", Toast.LENGTH_SHORT)
            
            val extractedUrls = mutableSetOf<String>()
            var foundLinks = 0
            
            // JavaScript to interact with the page and extract sources
            // Includes "Super Probe" logic to dump DOM structure
            val extractionScript = """
                (function() {
                    var urls = [];
                    var clicked = false;
                    
                    try {
                        function log(msg) { urls.push('DEBUG|||' + msg); }
                        
                        log('Page Title: ' + document.title);
                        log('URL: ' + window.location.href);
                        
                        // --- PROBE: Dump Page Structure ---
                        var uls = document.querySelectorAll('ul');
                        log('Found ' + uls.length + ' UL elements');
                        for(var i=0; i<uls.length; i++) {
                            var u = uls[i];
                            var info = 'UL[' + i + '] id="' + (u.id||'') + '" class="' + (u.className||'') + '"';
                            if (u.id || u.className) log(info);
                        }
                        
                        var divs = document.querySelectorAll('div[id*="server"], div[class*="server"], div[id*="watch"], div[class*="watch"]');
                        for(var i=0; i<divs.length; i++) {
                             log('Div Candidate: id="' + divs[i].id + '" class="' + divs[i].className + '"');
                        }

                            // --- ACTIVE: Click Server Button (Skipping Action Buttons) ---
                            if (!clicked) {
                                var serverLists = document.querySelectorAll('ul.btns');
                                log('Found ' + serverLists.length + ' ul.btns elements');
                                
                                for (var u = 0; u < serverLists.length; u++) {
                                    if (clicked) break;
                                    var listItems = serverLists[u].querySelectorAll('li');
                                    
                                    for (var i = 0; i < listItems.length; i++) {
                                        var item = listItems[i];
                                        var text = (item.innerText || '').trim();
                                        
                                        // Skip Action Buttons (Return, Details, Add to List, Report, Favorites)
                                        if (text.includes('عودة') || text.includes('التفاصيل') || 
                                            text.includes('اضف') || text.includes('قائمتي') || 
                                            text.includes('بلغ') || text.includes('مفضلة')) {
                                            log('Skipping Action Button: ' + text.substring(0, 20));
                                            continue;
                                        }
                                        
                                        // Skip empty or too short items
                                        if (text.length < 2) continue;
                                        
                                        log('Targeting server candidate: ' + item.tagName + ' Text: ' + text.substring(0, 20));
                                        
                                        // Try clicking clickable children
                                        var clickableChild = item.querySelector('a, span, div');
                                        if (clickableChild) {
                                            log('Clicking child element: ' + clickableChild.tagName + ' Content: ' + clickableChild.innerText.substring(0,20));
                                            clickableChild.click();
                                        } else {
                                            log('No specific clickable child found, clicking LI');
                                            item.click();
                                        }
                                        
                                        clicked = true;
                                        log('CLICKED server element');
                                        break; // Stop after first successful click
                                    }
                                }
                                
                                if (!clicked) {
                                    log('No valid server button found to click after checking ' + serverLists.length + ' lists');
                                }
                            }

                        // --- EXTRACTION LOGIC ---
                        
                        // 1. Iframes
                        var iframes = document.querySelectorAll('iframe');
                        log('Iframes count: ' + iframes.length);
                        for (var i = 0; i < iframes.length; i++) {
                            var src = iframes[i].src || iframes[i].getAttribute('src');
                            if (src) urls.push('Iframe|||' + src);
                        }
                        
                        // 2. Video Tags
                        var videos = document.querySelectorAll('video');
                        log('Videos count: ' + videos.length);
                        for (var i = 0; i < videos.length; i++) {
                            var src = videos[i].src || videos[i].currentSrc;
                            if (src) urls.push('Video|||' + src);
                        }
                        
                        // 3. Global Variables
                        if (window.playerSrc) urls.push('Player|||' + window.playerSrc);
                        
                    } catch(e) {
                        urls.push('DEBUG|||Script Error: ' + e.toString());
                    }
                    
                    return JSON.stringify(urls);
                })()
            """.trimIndent()
            
            // Relaxed Regex to catch more potential streams
            val resolver = WebViewResolver(
                // Catch m3u8, mp4, common embeds, and php player endpoints
                interceptUrl = Regex("""(?i)(\.m3u8|\.mp4|vidstream|embedsito|dood|upstream|streamtape|mixdrop|cdn\.watch|player\.php|/embed/)"""),
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
                                    val parts = entry.split("|||")
                                    if (parts.size == 2 && !extractedUrls.contains(parts[1])) {
                                        extractedUrls.add(parts[1])
                                        println("CimaNow: " + "[WEBVIEW] Script Found: ${parts[1]} (${parts[0]})")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { }
                },
                timeout = 60000L // 60s timeout to allow for click + load
            )
            
            println("CimaNow: " + "[WEBVIEW] Starting resolution active...")
            
            // Fix double slashes in URL just in case
            val safeUrl = data.replace("(?<!:)/{2,}".toRegex(), "/")
            
            val (mainRequest, additionalRequests) = resolver.resolveUsingWebView(
                requestCreator("GET", safeUrl, headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                ))
            )
            
            // Collect all potential URLs (intercepted + extracted by script)
            val candidates = mutableSetOf<String>()
            if (mainRequest != null) candidates.add(mainRequest.url.toString())
            candidates.addAll(additionalRequests.map { it.url.toString() })
            candidates.addAll(extractedUrls)
            
            println("CimaNow: " + "[WEBVIEW] Candidates found: ${candidates.size}")
            showToast("Found ${candidates.size} potential links", Toast.LENGTH_SHORT)

            for (url in candidates) {
                // Filter out junk
                if (url.contains("favicon") || url.contains(".css") || url.contains(".js") || url.contains("google")) continue
                
                println("CimaNow: " + "[LINK CHECK] Processing: $url")
                
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    val qualityText = when {
                        url.contains("1080") -> "1080p"
                        url.contains("720") -> "720p"
                        url.contains("480") -> "480p"
                        else -> "Auto"
                    }
                    val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "$name $qualityText",
                            url,
                            type
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityFromText(qualityText)
                        }
                    )
                    foundLinks++
                } else if (url.contains("embed") || url.contains("player.php") || 
                           url.contains("vidstream") || url.contains("dood") || url.contains("streamtape")) {
                    // Try to load as extractor
                    val fixedUrl = if(url.startsWith("//")) "https:$url" else url
                    loadExtractor(fixedUrl, safeUrl, subtitleCallback, callback)
                    foundLinks++
                }
            }
            
            return foundLinks > 0
            
        } catch (e: Exception) {
            println("CimaNow ERROR: " + "[LOADLINKS] Exception: ${e.message}")
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
}
