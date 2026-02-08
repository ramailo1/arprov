package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        
        val mainUrl = mainUrl
        
        return suspendCoroutine { continuation ->
            var webView: WebView? = null
            
            // 60s timeout in Kotlin to ensure we don't hang execution
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (webView != null) {
                    println("CimaNow: " + "[WEBVIEW] Timeout reached")
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) {}
                    webView = null
                    continuation.resume(false)
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 60000)

            val api = this
            
            newWebView {
                webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        
                        if (msg.startsWith("RESULT_LINKS|||")) {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            try {
                                val json = msg.removePrefix("RESULT_LINKS|||")
                                println("CimaNow: " + "[WEBVIEW] Received links: $json")
                                val links = AppUtils.parseJson<List<String>>(json)
                                
                                var linksFound = 0
                                for (entry in links) {
                                    val parts = entry.split("|||")
                                    if (parts.size < 2) continue
                                    val type = parts[0]
                                    val url = parts[1]
                                    
                                    println("CimaNow: " + "[LINK] Found: $url ($type)")

                                    if (url.contains(".m3u8") || url.contains(".mp4")) {
                                        val qualityText = when {
                                            url.contains("1080") -> "1080p"
                                            url.contains("720") -> "720p"
                                            url.contains("480") -> "480p"
                                            else -> "Auto"
                                        }
                                        val linkType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        callback.invoke(
                                            newExtractorLink(
                                                api.name,
                                                "$name $qualityText",
                                                url,
                                                linkType,
                                                Qualities.Unknown.value
                                            ) {
                                                this.referer = mainUrl
                                            }
                                        )
                                        linksFound++
                                    } else if (url.contains("embed") || url.contains("player") || url.contains("vidstream") || url.contains("dood") || url.contains("streamtape")) {
                                         val fixedUrl = if(url.startsWith("//")) "https:$url" else url
                                         loadExtractor(fixedUrl, mainUrl, subtitleCallback, callback)
                                         linksFound++
                                    }
                                }
                                
                                try {
                                    stopLoading()
                                    destroy()
                                } catch (e: Exception) {}
                                webView = null
                                continuation.resume(linksFound > 0)
                                
                            } catch (e: Exception) {
                                e.printStackTrace()
                                try {
                                    stopLoading()
                                    destroy()
                                } catch (ex: Exception) {}
                                webView = null
                                continuation.resume(false)
                            }
                        } else if (msg.startsWith("DEBUG|||")) {
                             println("CimaNow: " + "[JS] ${msg.removePrefix("DEBUG|||")}")
                        }
                        return true
                    }
                }
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        println("CimaNow: " + "[WEBVIEW] Page loaded: $url")
                        // Inject Polling Script
                        val js = """
                            (function() {
                                var attempt = 0;
                                var maxAttempts = 60; // 30 seconds of polling (500ms * 60)
                                
                                function log(msg) { console.log("DEBUG|||" + msg); }
                                
                                function getLinks() {
                                    var urls = [];
                                    var iframes = document.querySelectorAll('iframe');
                                    for(var i=0; i<iframes.length; i++) {
                                        var src = iframes[i].src || iframes[i].getAttribute('src');
                                        if(src) urls.push("Iframe|||"+src);
                                    }
                                    var videos = document.querySelectorAll('video');
                                    for(var i=0; i<videos.length; i++) {
                                        var src = videos[i].src || videos[i].currentSrc;
                                        if(src) urls.push("Video|||"+src);
                                    }
                                    // Also check for global variables
                                    if(window.playerSrc) urls.push("Variable|||"+window.playerSrc);
                                    
                                    return urls;
                                }
                                
                                function tryClickServer() {
                                    log("Scanning for servers...");
                                    var clicked = false;
                                    
                                    // Strategy 1: Look for ul.btns li (Specific)
                                    var lists = document.querySelectorAll('ul.btns li');
                                    log("Found " + lists.length + " list items in ul.btns");
                                    
                                    for(var i=0; i<lists.length; i++) {
                                        var t = lists[i].innerText || "";
                                        // Filter
                                        if (t.includes("add") || t.includes("qayimati") || t.includes("اضف") || t.includes("قائمتي") || t.includes("عودة") || t.includes("Return") || t.includes("التفاصيل")) {
                                            continue;
                                        }
                                        // If it looks like a server or just a generic item in the list
                                        log("Clicking candidate (Strategy 1): " + t);
                                        lists[i].click();
                                        // Try clicking 'a' inside if exists
                                        if (lists[i].querySelector('a')) lists[i].querySelector('a').click();
                                        clicked = true;
                                        break; 
                                    }
                                    
                                    if (clicked) return true;

                                    // Strategy 2: Broad Search for "Server" keyword
                                    log("Strategy 1 failed/empty. Trying broad search...");
                                    var buttons = document.querySelectorAll('li, a, button, div.btn'); 
                                    
                                    for(var i=0; i<buttons.length; i++) {
                                        var t = buttons[i].innerText || "";
                                        if ((t.includes("سيرفر") || t.includes("مشاهدة") || t.includes("Server") || t.includes("Watch")) && 
                                            !t.includes("عودة") && !t.includes("Return") && !t.includes("قائمتي") && !t.includes("التفاصيل")) {
                                                
                                            log("Clicking candidate (Strategy 2): " + t);
                                            buttons[i].click();
                                            clicked = true;
                                            break; 
                                        }
                                    }
                                    return clicked;
                                }
                                
                                // Initial Click Attempt
                                setTimeout(function() {
                                    var clicked = tryClickServer();
                                    log("Server clicked: " + clicked);
                                }, 2000); // Wait 2s for initial JS to settle
                                
                                // Poll for results
                                var interval = setInterval(function() {
                                    var links = getLinks();
                                    log("Polling... Attempt " + attempt + "/" + maxAttempts + " Found: " + links.length);
                                    
                                    if (links.length > 0) {
                                        clearInterval(interval);
                                        console.log("RESULT_LINKS|||" + JSON.stringify(links));
                                    }
                                    
                                    if (attempt >= maxAttempts) {
                                        clearInterval(interval);
                                        console.log("RESULT_LINKS|||" + JSON.stringify(links)); 
                                    }
                                    attempt++;
                                }, 500);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }
                loadUrl(data)
            }
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

