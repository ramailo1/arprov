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
        // Note: The value is surrounded by single quotes, not double quotes
        val scriptPattern = Regex("""hide_my_HTML_\s*=\s*'([^']+)'""")
        val match = scriptPattern.find(html)
        if (match == null) {
            println("CimaNow: " + "[DEOBFUSCATE] No obfuscation detected (hide_my_HTML_ not found)")
            return html // Not obfuscated
        }
        
        println("CimaNow: " + "[DEOBFUSCATE] Found obfuscated data pattern!")
        val obfuscatedData = match.groupValues[1]
        val parts = obfuscatedData.split(".")
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
        println("CimaNow: " + "[LOADLINKS] Starting for URL: $data")
        println("CimaNow: " + "========================================")
        
        try {
            // Step 1: Fetch raw HTML
            println("CimaNow: " + "[STEP 1] Fetching raw HTML...")
            val rawHtml = app.get(data).text
            println("CimaNow: " + "[STEP 1] Raw HTML length: ${rawHtml.length}")
            println("CimaNow: " + "[STEP 1] First 300 chars: ${rawHtml.take(300)}")
            
            // Step 2: Deobfuscate HTML if needed
            println("CimaNow: " + "[STEP 2] Attempting deobfuscation...")
            val html = deobfuscateHtml(rawHtml)
            val doc = Jsoup.parse(html)
            println("CimaNow: " + "[STEP 2] Final HTML length after deobfuscation: ${html.length}")
            
            // Step 3: Parse server buttons
            println("CimaNow: " + "[STEP 3] Looking for server buttons (ul#watch li, .servers-list li)...")
            val servers = doc.select("ul#watch li, .servers-list li").mapNotNull { li ->
                val index = li.attr("data-index")
                val name = li.text().trim()
                if (index.isNotBlank()) {
                    println("CimaNow: " + "[STEP 3] Found server: name='$name', index='$index'")
                    Pair(index, name)
                } else null
            }.sortedByDescending { (_, name) -> 
                name.contains("Cima Now", ignoreCase = true) || name.contains("Main", ignoreCase = true)
            }
            println("CimaNow: " + "[STEP 3] Total servers found: ${servers.size}")

            // Fallback indices if dynamic parsing failed
            val serverIndices = if (servers.isNotEmpty()) {
                servers.map { it.first }
            } else {
                println("CimaNow: " + "[STEP 3] No servers found, using fallback indices: 00, 01, 02, 03")
                listOf("00", "01", "02", "03")
            }
            println("CimaNow: " + "[STEP 3] Server indices to try: $serverIndices")

            var foundLinks = 0

            // Step 4: Extract Post ID
            println("CimaNow: " + "[STEP 4] Extracting Post ID...")
            val shortlinkHref = doc.select("link[rel='shortlink']").attr("href")
            println("CimaNow: " + "[STEP 4] Shortlink href: '$shortlinkHref'")
            
            val dataIdFromButton = doc.selectFirst("ul#watch li")?.attr("data-id") ?: ""
            println("CimaNow: " + "[STEP 4] data-id from button: '$dataIdFromButton'")
            
            val bodyClass = doc.select("body").attr("class")
            println("CimaNow: " + "[STEP 4] Body class: '${bodyClass.take(200)}'")
            
            val postId = shortlinkHref.substringAfter("p=")
                .takeIf { it.all { c -> c.isDigit() } && it.isNotBlank() }
                ?: dataIdFromButton
                    .takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
                ?: bodyClass.split(" ").find { it.startsWith("postid-") }
                    ?.substringAfter("postid-")
            
            if (postId.isNullOrBlank()) {
                println("CimaNow ERROR: " + "[STEP 4] FAILED - Could not extract Post ID!")
                return false
            }
            println("CimaNow: " + "[STEP 4] Post ID extracted: '$postId'")

            // Step 5: Make AJAX calls for each server
            println("CimaNow: " + "[STEP 5] Starting AJAX calls for ${serverIndices.size} servers...")
            val ajaxResults = coroutineScope {
                serverIndices.map { index ->
                    async {
                        try {
                            withTimeout(15000L) {
                                val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$postId"
                                println("CimaNow: " + "[STEP 5] AJAX call for index=$index, URL: $ajaxUrl")
                                
                                val response = app.get(ajaxUrl, headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")).text
                                println("CimaNow: " + "[STEP 5] AJAX response for index=$index, length: ${response.length}")
                                println("CimaNow: " + "[STEP 5] AJAX response content: ${response.take(300)}")
                                
                                val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                                println("CimaNow: " + "[STEP 5] Iframe src found: '$iframeSrc'")
                                
                                if (iframeSrc.isNotBlank()) {
                                    val fullSrc = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                                    println("CimaNow: " + "[STEP 5] Calling loadExtractor with: $fullSrc")
                                    loadExtractor(fullSrc, subtitleCallback, callback)
                                    true
                                } else {
                                    println("CimaNow: " + "[STEP 5] No iframe found for index=$index")
                                    false
                                }
                            }
                        } catch (e: Exception) {
                            println("CimaNow ERROR: " + "[STEP 5] AJAX error for index=$index: ${e.message}")
                            false
                        }
                    }
                }.awaitAll()
            }
            foundLinks += ajaxResults.count { it }
            println("CimaNow: " + "[STEP 5] AJAX calls complete. Found links from AJAX: $foundLinks")

            // Step 6: Fallback to static iframes
            if (foundLinks == 0) {
                println("CimaNow: " + "[STEP 6] No AJAX links found, trying static iframes...")
                val iframes = doc.select("iframe")
                println("CimaNow: " + "[STEP 6] Found ${iframes.size} iframes in document")
                for (iframe in iframes) {
                    val src = iframe.attr("src")
                    println("CimaNow: " + "[STEP 6] Iframe src: '$src'")
                    if (src.isNotBlank() && !src.contains("facebook") && !src.contains("twitter")) {
                        println("CimaNow: " + "[STEP 6] Loading extractor for static iframe: $src")
                        loadExtractor(src, subtitleCallback, callback)
                        foundLinks++
                    }
                }
            }

            // Step 7: Download links
            println("CimaNow: " + "[STEP 7] Looking for download links...")
            val downloadDoc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
            val downloadBlocks = downloadDoc.select("ul#download li[aria-label=\"quality\"].box, ul#download li[aria-label=\"download\"] a")
            println("CimaNow: " + "[STEP 7] Found ${downloadBlocks.size} download blocks")
            for (block in downloadBlocks) {
                val links = block.select("a")
                for (link in links) {
                    val url = link.attr("href")
                    if (url.isNotBlank()) {
                         val qualityText = link.selectFirst("i")?.nextSibling()?.toString()?.trim() ?: ""
                         val quality = qualityText.getIntFromText() ?: Qualities.Unknown.value
                         println("CimaNow: " + "[STEP 7] Download link: $url, quality: $qualityText")
                         callback.invoke(newExtractorLink(this.name, qualityText.ifBlank { "Download" }, url, ExtractorLinkType.VIDEO) { this.referer = data; this.quality = quality })
                    }
                }
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
}
