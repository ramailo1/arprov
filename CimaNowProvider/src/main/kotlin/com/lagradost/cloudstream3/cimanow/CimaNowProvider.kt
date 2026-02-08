package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            newEpisode("${epUrl.trimEnd('/')}/watching/") {
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
            newMovieLoadResponse(title, url, TvType.Movie, "${url.trimEnd('/')}/watching/") {
                this.posterUrl = posterUrl; this.plot = synopsis; this.year = year; this.tags = tags; this.recommendations = recommendations
            }
        }
    }

    /** 
     * loadLinks using WebViewResolver
     * 
     * CimaNow Flow:
     * 1. Watch page loads with server list (ul#watch li)
     * 2. Clicking a server triggers AJAX that loads iframe
     * 3. Iframe contains embed URL (e.g., cimanowtv.com/e/xxx)
     * 4. We intercept the iframe src or video URL
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // JavaScript to click the first server (Cima Now) and extract iframe
        val extractionScript = """
            (function() {
                var attempts = 0;
                var maxAttempts = 15;
                var clickedServer = false;
                
                function checkAndAct() {
                    attempts++;
                    if (attempts > maxAttempts) return;
                    
                    // Step 1: Look for timer/watch button (middle page)
                    var timerBtn = document.querySelector('a[href*="watching"], button:contains("مشاهدة"), a:contains("شاهد")');
                    if (!timerBtn) {
                        var allLinks = document.querySelectorAll('a, button');
                        for (var i = 0; i < allLinks.length; i++) {
                            var txt = allLinks[i].innerText || '';
                            if (txt.includes('مشاهدة وتحميل') || txt.includes('شاهد وحمل')) {
                                timerBtn = allLinks[i];
                                break;
                            }
                        }
                    }
                    if (timerBtn && !timerBtn.clicked) {
                        timerBtn.clicked = true;
                        timerBtn.click();
                        setTimeout(checkAndAct, 1000);
                        return;
                    }
                    
                    // Step 2: Click first server in ul#watch (prioritize Cima Now)
                    var serverList = document.querySelectorAll('ul#watch > li[data-index]');
                    if (serverList.length > 0 && !clickedServer) {
                        clickedServer = true;
                        // Find Cima Now server (data-index="00") or first server
                        var cimaServer = document.querySelector('ul#watch > li[data-index="00"]');
                        var targetServer = cimaServer || serverList[0];
                        if (targetServer) {
                            targetServer.click();
                        }
                        setTimeout(checkAndAct, 2000);
                        return;
                    }
                    
                    // Step 3: Check if iframe appeared after click
                    var iframe = document.querySelector('ul#watch li[aria-label="embed"] iframe[src]');
                    if (iframe) {
                        var src = iframe.src || iframe.getAttribute('src');
                        if (src && src.includes('http')) {
                            // Navigate to iframe to trigger interception
                            window.location.href = src;
                            return;
                        }
                    }
                    
                    // Keep checking
                    setTimeout(checkAndAct, 1000);
                }
                
                // Start after page settles
                setTimeout(checkAndAct, 1500);
            })();
        """.trimIndent()

        val resolver = WebViewResolver(
            interceptUrl = Regex("""(?i)(\.m3u8|\.mp4|cimanowtv|/e/|/embed/|filemoon|bysetayico|listeamed|dood|ok\.ru|vk\.com|uqload)"""),
            additionalUrls = listOf(
                Regex("""\.m3u8(\?|$)"""),
                Regex("""\.mp4(\?|$)"""),
                Regex("""cimanowtv\.com/e/"""),
                Regex("""master\.m3u8""")
            ),
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            script = extractionScript,
            timeout = 35000L
        )

        val safeUrl = data.replace("(?<!:)/{2,}".toRegex(), "/")

        try {
            val (mainRequest, additionalRequests) = resolver.resolveUsingWebView(
                requestCreator("GET", safeUrl, headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ))
            )

            val candidates = mutableSetOf<String>()
            mainRequest?.url?.toString()?.let { candidates.add(it) }
            candidates.addAll(additionalRequests.map { it.url.toString() })

            var foundLinks = false

            for (url in candidates) {
                if (url.contains("favicon") || url.contains(".css") || url.contains(".js")) continue

                when {
                    // Direct video files
                    url.contains(".m3u8") || url.contains(".mp4") -> {
                        val isM3u8 = url.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "$name سيرفر سيما ناو",
                                url,
                                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        foundLinks = true
                    }
                    // Embed pages - pass to extractors
                    url.contains("cimanowtv") || url.contains("/e/") || 
                    url.contains("filemoon") || url.contains("bysetayico") ||
                    url.contains("dood") || url.contains("uqload") ||
                    url.contains("ok.ru") || url.contains("vk.com") -> {
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }

            return foundLinks

        } catch (e: Exception) {
            return false
        }
    }
}
