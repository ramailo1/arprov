@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.DubStatus
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.delay

class EgyDeadProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egydead.rip"
    override var name = "EgyDead"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val arabicOrdinals = listOf(
        listOf("الحادي عشر", "الحادية عشرة") to 11,
        listOf("الثاني عشر", "الثانية عشرة") to 12,
        listOf("الثالث عشر", "الثالثة عشرة") to 13,
        listOf("الرابع عشر", "الرابعة عشرة") to 14,
        listOf("الخامس عشر", "الخامسة عشرة") to 15,
        listOf("السادس عشر", "السادسة عشرة") to 16,
        listOf("السابع عشر", "السابعة عشرة") to 17,
        listOf("الثامن عشر", "الثامنة عشرة") to 18,
        listOf("التاسع عشر", "التاسعة عشرة") to 19,
        listOf("العشرون", "العشرين") to 20,
        listOf("الأول", "الاول", "أولى", "اولى", "أول", "اول") to 1,
        listOf("الثاني", "التاني", "ثانية", "تانية", "ثاني", "تاني") to 2,
        listOf("الثالث", "التالت", "ثالثة", "تالتة", "ثالث", "تالت") to 3,
        listOf("الرابع", "رابعة", "رابع") to 4,
        listOf("الخامس", "خامسة", "خامس") to 5,
        listOf("السادس", "سادسة", "سادس") to 6,
        listOf("السابع", "سابعة", "سابع") to 7,
        listOf("الثامن", "ثامنة", "ثامن") to 8,
        listOf("التاسع", "تاسعة", "تاسع") to 9,
        listOf("العاشر", "عاشرة", "عاشر") to 10
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun parseSeasonNumber(title: String, url: String? = null): Int? {
        val text = (title + " " + (url ?: "")).lowercase()
        
        // 1. Check URL patterns like -s01, -s1, /season-1/, s02e01, etc.
        val urlMatch = (url ?: "").let {
            Regex("""[-_/](?:s|season|part|sezon)[-_]?(\d{1,2})(?:[-_/]|$)""", RegexOption.IGNORE_CASE).find(it)
        }
        if (urlMatch != null) {
            val n = urlMatch.groupValues[1].toIntOrNull()
            if (n != null && n in 1..49) return n
        }

        // 2. Check compound Arabic ordinals first (11-19) then single (1-10)
        for ((words, num) in arabicOrdinals) {
            for (w in words) {
                if (Regex("""(?:الموسم|الجزء|موسم|جزء|season|part)\s+$w""", RegexOption.IGNORE_CASE).containsMatchIn(text)
                    || text.contains(" $w ") || text.endsWith(" $w") || text.startsWith("$w ")) {
                    return num
                }
            }
        }

        // 3. Digits after season/part keywords
        val digitMatch = Regex("""(?:الموسم|الجزء|موسم|جزء|season|part|s)\s*[:\-]?\s*(\d{1,2})(?:\D|$)""", RegexOption.IGNORE_CASE).find(text)
        if (digitMatch != null) {
            val n = digitMatch.groupValues[1].toIntOrNull()
            if (n != null && n in 1..49) return n
        }

        return null
    }

    private fun parseEpisodeNumber(text: String, url: String? = null): Int? {
        val urlEp = (url ?: "").let {
            Regex("""(?:e|ep|episode)[-_]?(\d{1,4})""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (urlEp != null) return urlEp

        val match = Regex("""(?:الحلقة|حلقه|الحلقه|حلقة|episode|ep)\s*[:\-]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: text.getIntFromText()
    }

    private fun String.cleanTitle(isDubbed: Boolean = false): String {
        var res = this.replace(
            Regex("""(?i)جميع مواسم مسلسل|جميع مواسم انمي|جميع مواسم كرتون|جميع مواسم|مترجم و مدبلج كامل|مترجم و مدبلج|مترجمة و مدبلجة|مترجم كامل|مترجمة كاملة|مشاهدة فيلم|مشاهدة عرض|مشاهدة مسلسل|مشاهدة كرتون|مشاهدة|مترجم|مترجمة|مدبلج كامل|مدبلجة كاملة|مدبلج بالمصري|مدبلجة بالمصري|مدبلج للعربية|مدبلج|مدبلجة|مسلسل|انمي|كرتون|برنامج|كاملة|كامل|اون لاين|اونلاين|تحميل""")
            , ""
        ).trim()
        res = res.replace(Regex("""^[\s\-–—،,و\.]+|[\s\-–—،,و\.]+$"""), "").trim()
        return if (isDubbed && !res.contains("مدبلج")) {
            "$res (مدبلج)"
        } else {
            res
        }
    }

    private fun List<Element>.sortSeasons(): List<Element> {
        return this.sortedWith { a, b ->
            val sA = parseSeasonNumber(a.text(), a.attr("abs:href"))
            val sB = parseSeasonNumber(b.text(), b.attr("abs:href"))
            if (sA != null && sB != null) return@sortedWith sA.compareTo(sB)
            if (sA != null) return@sortedWith -1
            if (sB != null) return@sortedWith 1

            val yA = a.text().getIntFromText() ?: a.attr("abs:href").getIntFromText()
            val yB = b.text().getIntFromText() ?: b.attr("abs:href").getIntFromText()
            if (yA != null && yB != null) return@sortedWith yA.compareTo(yB)
            if (yA != null) return@sortedWith -1
            if (yB != null) return@sortedWith 1

            0
        }
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a")
        if (link == null) return null
        
        val href = link.attr("href").takeIf { it.isNotEmpty() && it.contains("egydead") } ?: return null
        val rawTitle = (link.selectFirst("h1, h2, h3, .BottomTitle")?.text() ?: link.attr("title")).trim()
        if (rawTitle.isEmpty()) return null

        val isDub = rawTitle.contains("مدبلج", ignoreCase = true) || href.contains("-ar") || href.contains("-eg") || href.contains("مدبلج")
        val isSub = rawTitle.contains("مترجم", ignoreCase = true) || (!isDub && !href.contains("-jp") && !rawTitle.contains("الياباني"))
        val title = rawTitle.cleanTitle(isDub)
        if (title.isEmpty()) return null
        
        val posterUrl = link.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""
        
        val isAnimeOrCartoon = href.contains("انمي") || href.contains("anime") || href.contains("كرتون") || href.contains("cartoon") || rawTitle.contains("انمي") || rawTitle.contains("كرتون")
        val isSeries = href.contains("/serie/") || href.contains("/season/") || href.contains("/episode/")
        val tvType = when {
            isAnimeOrCartoon -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return when (tvType) {
            TvType.Anime -> {
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.addDubStatus(dubExist = isDub, subExist = isSub)
                }
            }
            TvType.TvSeries -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
            else -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/movies/?page=" to "احدث الافلام",
        "$mainUrl/category/english-movies/افلام-اجنبية-مدبلجة/?page=" to "افلام اجنبية مدبلجة",
        "$mainUrl/category/افلام-كرتون/افلام-كرتون-ديزني-باللهجة-المصرية/?page=" to "افلام كرتون مدبلجة بالمصري",
        "$mainUrl/episode/?page=" to "احدث الحلقات",
        "$mainUrl/season/?page=" to "احدث المواسم",
        "$mainUrl/serie/?page=" to "احدث المسلسلات",
        "$mainUrl/series-category/english-series-dubbed/?page=" to "مسلسلات اجنبي مدبلجة",
        "$mainUrl/series-category/turkish-series-dubbed/?page=" to "مسلسلات تركية مدبلجة",
        "$mainUrl/series-category/anime-series-dubbed/?page=" to "انميات مدبلجة",
        "$mainUrl/series-category/cartoon-series-dubbed/?page=" to "مسلسلات كرتون مدبلجة",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1000..2000).random().toLong())
        
        val url = if (request.data.contains("/page/movies/")) {
            if (page == 1) {
                "$mainUrl/page/movies/"
            } else {
                "$mainUrl/page/movies/page/$page/"
            }
        } else {
            request.data + page
        }

        val document = app.get(url, headers = requestHeaders).document
        
        val home = document.select("li.movieItem, div.BlockItem, a[href*='egydead']").toList().filter {
            val href = it.attr("href")
            if (it.tagName() == "a") {
                href.contains("202") || href.contains("/%") || 
                href.contains("/episode/") || href.contains("/season/") || href.contains("/serie/")
            } else true
        }.mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1000..2000).random().toLong())
        
        val doc = app.get("$mainUrl/?s=$query", headers = requestHeaders).document
        return doc.select("li.movieItem, div.BlockItem, a[href*='egydead']").toList().filter {
            val href = it.attr("href")
            if (it.tagName() == "a") {
                href.contains("202") || href.contains("/%") || 
                href.contains("/episode/") || href.contains("/season/") || href.contains("/serie/")
            } else true
        }.mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1000..2000).random().toLong())
        
        val doc = app.get(url, headers = requestHeaders).document

        // Redirect individual episode pages to their main series/season page for Netflix layout
        if (url.contains("/episode/")) {
            val breadcrumbLinks = doc.select(".breadcrumbs-single a")
            // Find specific season first (reversed search), or series
            val redirectUrl = breadcrumbLinks.reversed().find { 
                val href = it.attr("abs:href")
                (href.contains("/season/") || href.contains("/serie/")) && !href.contains("/series-category/") 
            }?.attr("abs:href")
            ?: doc.selectFirst("a[href*='/season/']:not([href$='/season/']), a[href*='/serie/']:not([href*='/series-category/']):not([href$='/serie/'])")?.attr("abs:href")
            
            if (!redirectUrl.isNullOrEmpty() && redirectUrl != url && !redirectUrl.endsWith("/episode/")) {
                return load(redirectUrl)
            }
        }

        val rawTitle = doc.selectFirst("div.singleTitle em, div.singleTitle, .breadcrumbs-single li.current, h1")?.text()?.trim()
            ?: doc.title().substringBefore("|").substringBefore("-").trim()

        val isDubbed = url.contains("-ar") || url.contains("-eg") || url.contains("مدبلج") || rawTitle.contains("مدبلج")
        val title = rawTitle.cleanTitle(isDubbed)

        val isMovie = !url.contains("/serie/") && !url.contains("/season/") && !url.contains("/episode/")

        val posterUrl = doc.selectFirst("div.single-thumbnail img, div.Poster img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        } ?: ""
        
        val synopsis = doc.select("div.extra-content").find { it.text().contains("القصه") || it.text().contains("القصة") }?.selectFirst("p")?.text() 
            ?: doc.selectFirst("div.Story p")?.text() ?: ""
        val year = doc.select("ul > li:contains(السنه) > a, li:contains(السنة) a").text().getIntFromText()
        val tags = doc.select("ul > li:contains(النوع) > a, li:contains(النوع) a").map { it.text() }
        val recommendations = doc.select("div.related-posts > ul > li, div.BlockItem").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        }

        val isAnimeOrCartoon = url.contains("انمي") || url.contains("anime") || url.contains("كرتون") || url.contains("cartoon") 
            || rawTitle.contains("انمي") || rawTitle.contains("كرتون")
            || tags.any { it.contains("انمي") || it.contains("أنمي") || it.contains("كرتون") }
        
        val allSeasonElements = doc.select("div.seasons-list a")

        // Separate Dubbed and Subbed seasons from the franchise/series
        val dubSeasonElements = allSeasonElements.filter { el ->
            val href = el.attr("abs:href")
            val text = el.text()
            href.contains("-ar") || href.contains("-eg") || href.contains("مدبلج") || text.contains("مدبلج")
        }.sortSeasons()

        val subSeasonElements = allSeasonElements.filter { el ->
            val href = el.attr("abs:href")
            val text = el.text()
            !href.contains("-ar") && !href.contains("-eg") && !href.contains("مدبلج") && !text.contains("مدبلج") &&
            !href.contains("-jp") && !text.contains("الياباني")
        }.sortSeasons()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        suspend fun loadSeasonEpisodes(
            seasonElements: List<Element>,
            targetList: MutableList<Episode>,
            isDubList: Boolean
        ) {
            if (seasonElements.isNotEmpty()) {
                seasonElements.forEachIndexed { index, seasonEl ->
                    val sUrl = seasonEl.attr("abs:href")
                    val sText = seasonEl.text()
                    val parsedSeason = parseSeasonNumber(sText, sUrl) ?: (index + 1)

                    val sDoc = if (sUrl == url) doc else {
                        try {
                            app.get(sUrl, headers = requestHeaders).document
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (sDoc != null) {
                        val epElements = sDoc.select("div.episodes-list a")
                        epElements.forEach { ep ->
                            val epUrl = ep.attr("abs:href")
                            val epText = ep.text()
                            val epNum = parseEpisodeNumber(epText, epUrl) ?: 1
                            var epName = epText.trim().ifEmpty { "Episode $epNum" }
                            if (isDubList && !epName.contains("مدبلج")) {
                                epName = "$epName (مدبلج)"
                            }
                            targetList.add(
                                newEpisode(epUrl) {
                                    this.name = epName
                                    this.season = parsedSeason
                                    this.episode = epNum
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            }
        }

        // Load both Sub and Dub lists if available
        loadSeasonEpisodes(subSeasonElements, subEpisodes, isDubList = false)
        loadSeasonEpisodes(dubSeasonElements, dubEpisodes, isDubList = true)

        // Fallback for standalone season pages (if seasons-list was empty)
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            val epElements = doc.select("div.episodes-list a")
            val seasonNum = parseSeasonNumber(rawTitle, url) ?: 1
            epElements.forEach { ep ->
                val epUrl = ep.attr("abs:href")
                val epText = ep.text()
                val epNum = parseEpisodeNumber(epText, epUrl) ?: 1
                var epName = epText.trim().ifEmpty { "Episode $epNum" }
                if (isDubbed && !epName.contains("مدبلج")) {
                    epName = "$epName (مدبلج)"
                }
                val epObj = newEpisode(epUrl) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = posterUrl
                }
                if (isDubbed) dubEpisodes.add(epObj) else subEpisodes.add(epObj)
            }
        }

        val distinctSub = subEpisodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        val distinctDub = dubEpisodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        // If the series has both sub and dub or is anime/cartoon, use AnimeLoadResponse to give SUB/DUB toggle tabs in CloudStream UI
        val hasBothVariants = distinctSub.isNotEmpty() && distinctDub.isNotEmpty()
        if (isAnimeOrCartoon || hasBothVariants) {
            return newAnimeLoadResponse(title, url, if (isAnimeOrCartoon) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.year = year
                this.recommendations = recommendations
                if (distinctSub.isNotEmpty()) addEpisodes(DubStatus.Subbed, distinctSub)
                if (distinctDub.isNotEmpty()) addEpisodes(DubStatus.Dubbed, distinctDub)
            }
        } else {
            // For standard TV Series with single variant
            val combinedEpisodes = if (isDubbed && distinctDub.isNotEmpty()) {
                distinctDub
            } else if (distinctSub.isNotEmpty()) {
                distinctSub
            } else {
                distinctDub
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                combinedEpisodes
            ) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.year = year
                this.recommendations = recommendations
            }
        }
    }
    
    private val streamhgDomains = listOf(
        "hanerix.com", "vibuxer.com", "hgplaycdn.com"
    )

    private val streamhgEmbedHosts = streamhgDomains + listOf("hgcloud.to", "hglink.to")

    private val packerHeader = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('"
    private val packerSplitEnd = "'.split('|'))"

    private suspend fun unpackPacker(html: String): String? {
        val start = html.indexOf(packerHeader)
        if (start < 0) return null
        val end = html.indexOf(packerSplitEnd, start)
        if (end < 0) return null
        val sepStart = html.lastIndexOf("',", end)
        if (sepStart < start) return null
        val sep = html.substring(sepStart + 2, end)
        val sepMatch = Regex("^(\\d+),(\\d+),'([\\s\\S]*)$").find(sep) ?: return null
        val a = sepMatch.groupValues[1].toInt()
        val c = sepMatch.groupValues[2].toInt()
        val k = sepMatch.groupValues[3].split("|")
        if (c > k.size) return null
        var p = html.substring(start + packerHeader.length, sepStart)
        for (i in c - 1 downTo 0) {
            val ki = k.getOrNull(i) ?: continue
            if (ki.isEmpty()) continue
            p = p.replace(Regex("\\b" + i.toString(a) + "\\b"), ki)
        }
        return p
    }

    private suspend fun resolveStreamHg(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = embedUrl.substringAfterLast('/').substringBefore('?')
        if (fileId.isEmpty()) return

        for (domain in streamhgDomains) {
            try {
                val pageUrl = "https://$domain/e/$fileId"
                val page = app.get(
                    pageUrl,
                    headers = headers.toMutableMap().apply {
                        this["User-Agent"] = userAgents.random()
                        this["Referer"] = embedUrl.substringBeforeLast('/')
                    }
                )
                if (!page.text.contains(packerHeader)) continue
                val unpacked = unpackPacker(page.text) ?: continue
                val linksMatch = Regex("var links=\\{([\\s\\S]*?)\\};").find(unpacked) ?: continue
                val block = linksMatch.groupValues[1]
                fun linkValue(name: String): String? {
                    return Regex("\"$name\":\"([^\"]*)\"").find(block)?.groupValues?.get(1)
                }
                val hls4 = linkValue("hls4")
                val hls3 = linkValue("hls3")
                val hls2 = linkValue("hls2")
                val rawUrl = hls4 ?: hls3 ?: hls2 ?: continue
                val streamUrl = if (rawUrl.startsWith("http")) rawUrl else "https://$domain$rawUrl"
                callback(
                    newExtractorLink(
                        "StreamHG",
                        "StreamHG",
                        streamUrl,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                    ) {
                        referer = pageUrl
                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                    }
                )
                return
            } catch (e: Exception) {
                // try next domain
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val randomUserAgent = userAgents.random()
        val requestHeaders = headers.toMutableMap()
        requestHeaders["User-Agent"] = randomUserAgent
        
        delay((1000..2000).random().toLong())
        
        val doc = app.post(data, data = mapOf("View" to "1"), headers = requestHeaders).document
        
        val seenStreamUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val seenServerKeys = mutableSetOf<String>()

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (seenStreamUrls.add(link.url)) {
                callback(link)
            }
        }

        fun extractServerKey(link: String): String {
            val cleaned = link.substringBefore("?").trimEnd('/')
            val lastSegment = cleaned.substringAfterLast('/')
            val host = runCatching { URI(link).host?.replace("www.", "") ?: "" }.getOrDefault("")
            return "$host:$lastSegment"
        }

        // 1. Streaming servers (ul.serversList, div.ServersList)
        doc.select("ul.serversList > li, div.ServersList li").forEach { li ->
            val iframeUrl = li.attr("data-link").trim()
            if (iframeUrl.isNotEmpty() && !iframeUrl.startsWith("javascript")) {
                val key = extractServerKey(iframeUrl)
                seenServerKeys.add(key)
                val host = runCatching { URI(iframeUrl).host?.replace("www.", "") ?: "" }.getOrDefault("")
                if (streamhgEmbedHosts.any { host.endsWith(it) }) {
                    resolveStreamHg(iframeUrl, subtitleCallback, wrappedCallback)
                } else {
                    loadExtractor(iframeUrl, data, subtitleCallback, wrappedCallback)
                }
            }
        }

        // 2. Download servers (.donwload-servers-list, ul.download) - only if not already loaded as stream
        doc.select(".donwload-servers-list > li a, ul.download a").forEach { element ->
            val url = element.attr("href").trim()
            if (url.isNotEmpty() && !url.startsWith("javascript") && !url.contains("egydead")) {
                val key = extractServerKey(url)
                if (seenServerKeys.add(key)) {
                    val host = runCatching { URI(url).host?.replace("www.", "") ?: "" }.getOrDefault("")
                    if (streamhgEmbedHosts.any { host.endsWith(it) }) {
                        resolveStreamHg(url, subtitleCallback, wrappedCallback)
                    } else {
                        loadExtractor(url, data, subtitleCallback, wrappedCallback)
                    }
                }
            }
        }

        return true
    }
}
