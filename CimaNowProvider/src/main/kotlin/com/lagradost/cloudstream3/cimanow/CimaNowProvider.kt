@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.requestCreator
import android.util.Base64
import kotlin.text.Charsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.getQualityFromName

class CimaNowProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/home/"
    )

    private val homeSections = mapOf(
        "أحدث الإضافات" to "$mainUrl/%D8%A7%D9%84%D8%A7%D8%AD%D8%AF%D8%AB",
        "رمضان 2026" to "$mainUrl/category/%D8%B1%D9%85%D8%B6%D8%A7%D9%86/%D8%B1%D9%85%D8%B6%D8%A7%D9%86-2026",
        "أحدث المسلسلات العربية" to "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9",
        "أحدث المسلسلات الاجنبية" to "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9",
        "أحدث المسلسلات التركية" to "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%AA%D8%B1%D9%83%D9%8A%D8%A9",
        "أحدث الافلام العربية" to "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9",
        "أحدث الافلام الاجنبية" to "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9",
        "أحدث الافلام التركية" to "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%AA%D8%B1%D9%83%D9%8A%D8%A9",
        "أحدث الافلام الهندية" to "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D9%87%D9%86%D8%AF%D9%8A%D8%A9",
        "أحدث الافلام الانيميشن" to "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D9%86%D9%8A%D9%85%D9%8A%D8%B4%D9%86",
        "أحدث البرامج التلفزيونية" to "$mainUrl/category/%D8%A7%D9%84%D8%A8%D8%B1%D8%A7%D9%85%D8%AC-%D8%A7%D9%84%D8%AA%D9%84%D9%81%D8%B2%D9%8A%D9%88%D9%86%D9%8A%D8%A9",
        "أحدث المسرحيات" to "$mainUrl/category/%D9%85%D8%B3%D8%B1%D8%AD%D9%8A%D8%A7%D8%AA",
        "أحدث الحفلات" to "$mainUrl/category/%D8%AD%D9%81%D9%84%D8%A7%D8%AA"
    )

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

    /**
     * Decrypts the CimaNow protected video watching page payload (data-tuIyjvq).
     * Reverses every 10 chars, base64 decodes, and runs the RC4-like XOR decryption with the static key.
     */
    private fun decryptCimaNowPayload(rawHtml: String): Document? {
        val payloadMatch = Regex("""data-tuIyjvq=["']([^"']+)["']""").find(rawHtml) ?: return null
        val rawP = payloadMatch.groupValues[1]
        if (rawP.isBlank()) return null

        try {
            val vbKey = "h620760851n"
            val csz = 10
            val b64Builder = StringBuilder()
            var ci = 0
            while (ci < rawP.length) {
                val end = minOf(ci + csz, rawP.length)
                b64Builder.append(rawP.substring(ci, end).reversed())
                ci += csz
            }
            val rawBytes = Base64.decode(b64Builder.toString(), Base64.DEFAULT) ?: return null

            val sBox = IntArray(256) { i -> ((i * 11) + 20) and 255 }
            var j = 0
            for (i in 0 until 256) {
                j = (j + sBox[i] + vbKey[i % vbKey.length].code + ((i xor 20) and 255)) and 255
                val temp = sBox[i]
                sBox[i] = sBox[j]
                sBox[j] = temp
            }

            var ii = 0
            var jj = 0
            val tw = 44
            val jm = 15
            val decrypted = ByteArray(rawBytes.size)
            for (y in rawBytes.indices) {
                ii = (ii + 1) and 255
                jj = (jj + sBox[ii]) and 255
                val temp = sBox[ii]
                sBox[ii] = sBox[jj]
                sBox[jj] = temp
                val k = sBox[(sBox[ii] + sBox[jj]) and 255] xor (((y * 3) + tw) and 255) xor ((jj + ii) and jm)
                decrypted[y] = (rawBytes[y].toInt() xor k).toByte()
            }

            val dropChars = 60
            if (decrypted.size <= dropChars) return null
            val fullHtml = String(decrypted, dropChars, decrypted.size - dropChars, Charsets.UTF_8)
            return Jsoup.parse(fullHtml)
        } catch (e: Exception) {
            return null
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
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val isAnchor = tagName().equals("a", ignoreCase = true)
        val url = if (isAnchor) fixUrlNull(attr("href")) else fixUrlNull(selectFirst("a")?.attr("href"))
        if (url == null || url.contains("/news/") || url.contains("coming-soon", true) || url.contains("قريبا", true)) return null

        val images = select("img")
        var posterUrl = ""
        var altTitle = ""

        for (img in images) {
            val alt = img.attr("alt").trim()
            if (alt.equals("logo", ignoreCase = true) || alt.isEmpty()) continue
            posterUrl = img.attr("data-src").ifBlank { null } ?: img.attr("src")
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

        if (title.isBlank() || title.equals("logo", true)) return null

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
        
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

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
                val homePageLists = mutableListOf<HomePageList>()
                coroutineScope {
                    val deferredLists = homeSections.map { (name, url) ->
                        async {
                            try {
                                val doc = decodeHtml(app.get(url, headers = defaultHeaders).document)
                                val items = doc.select("section > article[aria-label='post'], article[aria-label='post'], section a")
                                    .mapNotNull { it.toSearchResponse() }
                                    .distinctBy { it.url }
                                if (items.isNotEmpty()) {
                                    HomePageList(name, items)
                                } else null
                            } catch (e: Exception) { null }
                        }
                    }
                    homePageLists.addAll(deferredLists.awaitAll().filterNotNull())
                }
                newHomePageResponse(homePageLists)
            } else {
                val base = homeSections[request.name] ?: return newHomePageResponse(emptyList())
                val doc = decodeHtml(app.get("$base/page/$page/", headers = defaultHeaders).document)
                val items = doc.select("section > article[aria-label='post'], article[aria-label='post']")
                    .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
                newHomePageResponse(listOf(HomePageList(request.name, items)))
            }
        } catch (e: Exception) { newHomePageResponse(emptyList()) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = decodeHtml(app.get("$mainUrl/page/1/?s=$query", headers = defaultHeaders).document)
        return doc.select("section > article[aria-label='post'], article[aria-label='post']")
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
        val doc = decodeHtml(app.get(seasonUrl, headers = defaultHeaders).document)
        return doc.select("ul#eps li").mapNotNull { ep ->
            val epUrl = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epNum = ep.select("a em").text().toIntOrNull()
            val epName = ep.select("a img:nth-child(2)").attr("alt").ifBlank { "Episode $epNum" }
            val epPoster = ep.select("a img:nth-child(2)").attr("src")
            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
                this.season = seasonNumber
                this.posterUrl = epPoster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = decodeHtml(app.get(url, headers = defaultHeaders).document)
        val posterUrl = doc.select("meta[property=\"og:image\"]").attr("content")
        val year = doc.select("article ul:nth-child(1) li a").lastOrNull()?.text()?.toIntOrNull()
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull() ?: ""
        val synopsis = doc.select("ul#details li:contains(لمحة) p, article p").text()
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
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl; this.plot = synopsis; this.year = year; this.tags = tags; this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val seenUrls = java.util.Collections.synchronizedSet(HashSet<String>())
            val validLinksCount = java.util.concurrent.atomic.AtomicInteger(0)

            val callbackWrapper: (ExtractorLink) -> Unit = { link ->
                if (link.url.isNotBlank() && seenUrls.add(link.url)) {
                    validLinksCount.incrementAndGet()
                    callback(link)
                }
            }

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

            // Clean URL safely
            val mainPageUrl = data.substringBefore("/watching/").trimEnd('/')
            val mainPageHeaders = mapOf("Referer" to "$mainUrl/home/", "User-Agent" to defaultHeaders["User-Agent"]!!)
            val mainDoc = decodeHtml(app.get(mainPageUrl, headers = mainPageHeaders).document)

            // Step 1: Discover watch token URL
            var watchTokenUrl: String? = if (data.contains("token=")) data else null

            if (watchTokenUrl == null) {
                val gateBtn = mainDoc.selectFirst("a.shine, a[href*='freex2line'], a[href*='loadon']")
                val gateUrl = gateBtn?.attr("href")?.trim()

                if (!gateUrl.isNullOrBlank()) {
                    try {
                        val resolver = WebViewResolver(Regex("""cimanow\.cc/.+/watching/\?token=|get-link\.php|blog-post\.html"""))
                        resolver.resolveUsingWebView(
                            requestCreator("GET", gateUrl, headers = mapOf("Referer" to mainPageUrl))
                        )
                        val blogRes = app.get(
                            "https://rm.freex2line.online/2020/02/blog-post.html/",
                            headers = mapOf("Referer" to "https://rm.freex2line.online/redirectingfree/")
                        ).text
                        val foundTokenUrl = Regex("""https?://cimanow\.cc[^\s"']+watching/\?token=[a-f0-9]{32,64}""").find(blogRes)?.value
                        if (foundTokenUrl != null) {
                            watchTokenUrl = foundTokenUrl
                        }
                    } catch (e: Exception) {
                        // Fallback to direct watch URL
                    }
                }
            }

            val finalWatchUrl = watchTokenUrl ?: "$mainPageUrl/watching/"
            val watchReferer = if (watchTokenUrl != null) "https://rm.freex2line.online/2020/02/blog-post.html/" else mainPageUrl
            val watchHeaders = mapOf("Referer" to watchReferer, "User-Agent" to defaultHeaders["User-Agent"]!!)
            
            val watchingRawText = app.get(finalWatchUrl, headers = watchHeaders).text
            // Attempt decryption of protected payload, otherwise fall back to standard decodeHtml
            val watchingDoc = decryptCimaNowPayload(watchingRawText) ?: decodeHtml(Jsoup.parse(watchingRawText))

            // Detect theme path
            val themePath = Regex("""wp-content/themes/([^/]+)/""").find(watchingDoc.html())?.groupValues?.get(1)
                ?: "Cima%20Now%20New"

            // Extract coreToken if present
            val dzMatch = Regex("""window\["_DZfQt"\]\s*=\s*\[([0-9,\s]+)\]""").find(watchingDoc.html())
            val coreToken = if (dzMatch != null) {
                val nums = dzMatch.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                val sb = StringBuilder()
                for ((i, v) in nums.withIndex()) {
                    sb.append((v xor 19 xor ((i * 13) and 255)).toChar())
                }
                sb.toString()
            } else ""

            coroutineScope {
                val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                // 1. Decrypted server tabs with data-uuJYYO
                val encryptedTabs = watchingDoc.select("ul#watch li[data-uuJYYO]")
                if (encryptedTabs.isNotEmpty()) {
                    tasks.add(async {
                        encryptedTabs.map { li ->
                            async {
                                try {
                                    val serverName = li.text().trim()
                                    val uujyyo = li.attr("data-uuJYYO")
                                    val parts = uujyyo.split("-").mapNotNull { it.toIntOrNull(16) }
                                    val sVal = StringBuilder()
                                    for ((hi, hx) in parts.withIndex()) {
                                        sVal.append((hx xor 19 xor ((hi * 7) and 255)).toChar())
                                    }
                                    val sStr = sVal.toString()
                                    if (":" in sStr) {
                                        val sParts = sStr.split(":", limit = 2)
                                        val serverId = sParts[0]
                                        val sPostId = sParts[1]
                                        
                                        val ajaxUrl = if (coreToken.isNotBlank()) {
                                            "$mainUrl/wp-content/themes/$themePath/core.php?action=switch&index=$serverId&id=$sPostId&token=$coreToken"
                                        } else {
                                            "$mainUrl/wp-content/themes/$themePath/core.php?action=switch&index=$serverId&id=$sPostId"
                                        }

                                        val response = app.get(
                                            ajaxUrl,
                                            headers = mapOf(
                                                "Referer" to finalWatchUrl,
                                                "X-Requested-With" to "XMLHttpRequest",
                                                "User-Agent" to defaultHeaders["User-Agent"]!!
                                            )
                                        ).text

                                        val doc = Jsoup.parse(response)
                                        val urlsToTry = doc.select("iframe[src]").map { it.attr("src") } +
                                            listOfNotNull(Regex("""https://[^"']+cimanowtv\.com/e/[^"']+""").find(response)?.value)

                                        urlsToTry.map { url ->
                                            async {
                                                if (serverName.contains("Cima Now", true) || url.contains("cimanowtv.com")) {
                                                    handlecima(url, callbackWrapper)
                                                } else {
                                                    safeTryExtractor(url)
                                                }
                                            }
                                        }.awaitAll()
                                    }
                                } catch (e: Exception) {
                                    // Ignore individual tab switch errors
                                }
                            }
                        }.awaitAll()
                        Unit
                    })
                }

                // 2. Legacy server elements (fallback if tabs are unencrypted)
                val serverElements = watchingDoc.select("ul#watch li[data-index]")
                if (serverElements.isNotEmpty()) {
                    var postId = watchingDoc.selectFirst("input[name='comment_post_ID']")?.attr("value")
                        ?: watchingDoc.selectFirst("body")?.className()?.split(" ")?.find { it.startsWith("postid-") }?.substringAfter("postid-")

                    if (!postId.isNullOrBlank()) {
                        tasks.add(async {
                            serverElements.map { li ->
                                async {
                                    try {
                                        val serverId = li.attr("data-index")
                                        val serverName = li.text().trim()
                                        if (serverId.isNotBlank()) {
                                            val ajaxUrl = if (coreToken.isNotBlank()) {
                                                "$mainUrl/wp-content/themes/$themePath/core.php?action=switch&index=$serverId&id=$postId&token=$coreToken"
                                            } else {
                                                "$mainUrl/wp-content/themes/$themePath/core.php?action=switch&index=$serverId&id=$postId"
                                            }
                                            val response = app.get(ajaxUrl, headers = mapOf("Referer" to finalWatchUrl, "X-Requested-With" to "XMLHttpRequest")).text
                                            val doc = Jsoup.parse(response)
                                            val urlsToTry = doc.select("iframe[src]").map { it.attr("src") } +
                                                listOfNotNull(Regex("""https://[^"']+cimanowtv\.com/e/[^"']+""").find(response)?.value)

                                            urlsToTry.map { url ->
                                                async {
                                                    if (serverName.contains("Cima Now", true) || url.contains("cimanowtv.com")) {
                                                        handlecima(url, callbackWrapper)
                                                    } else {
                                                        safeTryExtractor(url)
                                                    }
                                                }
                                            }.awaitAll()
                                        }
                                    } catch (e: Exception) {}
                                }
                            }.awaitAll()
                            Unit
                        })
                    }
                }

                // 3. Static iframe extraction
                val iframeSelectors = listOf(
                    "ul#watch li[aria-label=\"embed\"] iframe",
                    "ul#watch li iframe",
                    "#watch iframe",
                    "iframe[src*='embed']",
                    "iframe[data-src*='embed']",
                    "iframe[src*='player']",
                    "iframe[data-src*='player']",
                    "iframe[src*='video']"
                )

                tasks.add(async {
                    watchingDoc.select(iframeSelectors.joinToString(", ")).map { iframe ->
                        val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                        async { safeTryExtractor(src) }
                    }.awaitAll()
                    Unit
                })

                // 4. Direct download links
                tasks.add(async {
                    watchingDoc.select("ul#download li a").map { link ->
                        async {
                            val url = link.attr("href")
                            if (url.isNotBlank() && !url.startsWith("javascript:")) {
                                val text = link.text().trim()
                                val quality = text.getIntFromText() ?: Qualities.Unknown.value

                                if (url.contains("cimanowtv.com") || url.contains("worldcdn.online")) {
                                    callbackWrapper(
                                        newExtractorLink(
                                            this@CimaNowProvider.name,
                                            "Download - $text",
                                            url,
                                            if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) { this.referer = finalWatchUrl; this.quality = quality }
                                    )
                                } else {
                                    safeTryExtractor(url)
                                }
                            }
                        }
                    }.awaitAll()
                    Unit
                })

                tasks.awaitAll()
            }

            return validLinksCount.get() > 0

        } catch (e: Exception) {
            return false
        }
    }
}
