package com.lagradost.cloudstream3.mycima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.*

class MyCimaProvider : MainAPI() {

    // ---------- AUTO DOMAIN POOL ----------
    private val domainPool = listOf(
        "https://mycima.fan",
        "https://mycima.gold",
        "https://mycima.rip",
        "https://mycima.bond",
        "https://mycima.live"
    )

    private val cfKiller = CloudflareKiller()
    private var activeDomain: String = domainPool.first()
    private var checkedDomain = false

    override var mainUrl = activeDomain
    override var name = "MyCima"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
    )

    private val headers: Map<String, String>
        get() = baseHeaders + mapOf("Referer" to activeDomain)

    // ---------- AUTO DOMAIN DETECTOR ----------
    private suspend fun ensureDomain(): Boolean {
        if (checkedDomain) {
            val stillWorks = runCatching {
                app.get(activeDomain, timeout = 5).isSuccessful
            }.getOrDefault(false)

            if (stillWorks) return true
            checkedDomain = false
        }

        for (domain in domainPool) {
            val working = runCatching {
                val res = app.get(domain, timeout = 10, interceptor = cfKiller)
                res.isSuccessful && !res.document.select("title").text().contains("Just a moment")
            }.getOrDefault(false)

            if (working) {
                activeDomain = domain
                mainUrl = domain
                checkedDomain = true
                return true
            }
        }
        return false
    }

    private suspend fun safeGet(url: String): org.jsoup.nodes.Document? {
        if (!ensureDomain()) return null

        val fullUrl = if (url.startsWith("http")) url else activeDomain + url

        val response = runCatching {
            // First try without interceptor to be fast
            val res = app.get(fullUrl, headers = headers)
            if (res.isSuccessful && !res.document.select("title").text().contains("Just a moment")) {
                res
            } else {
                // Fallback to cfKiller
                app.get(fullUrl, headers = headers, interceptor = cfKiller, timeout = 60)
            }
        }.getOrNull()

        if (response?.isSuccessful == true) {
            val doc = response.document
            if (doc.select("title").text().contains("Just a moment")) return null
            return doc
        }

        // Retry domain rotation once
        checkedDomain = false
        if (!ensureDomain()) return null

        return runCatching {
            app.get(
                if (url.startsWith("http")) url else activeDomain + url,
                headers = headers
            ).document
        }.getOrNull()
    }

    // ---------- MAIN PAGE ----------
    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/episodes/" to "الحلقات",
        "/movies/" to "أفلام",
        "/series/" to "مسلسلات",
        "/category/مسلسلات-انمي/" to "مسلسلات انمي",
        "/category/عروض-مصارعة/" to "مصارعة حرة",
        "/category/برامج-تلفزيونية/" to "برامج تلفزيونية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = safeGet(url)
            ?: return newHomePageResponse(request.name, emptyList())

        val items = document.select("div#MainFiltar > a.GridItem, .GridItem")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // ---------- SEARCH ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = safeGet("/?s=$encoded") ?: return emptyList()

        return document.select("div#MainFiltar > .GridItem, .GridItem")
            .mapNotNull { it.toSearchResult() }
    }

    private fun detectType(url: String, document: Document? = null): TvType {
        val path = try { java.net.URLDecoder.decode(url, "UTF-8").lowercase() } catch (_: Exception) { url.lowercase() }

        return when {
            // Priority 1: Explicit path segments
            "/series/" in path && "/movies/" !in path && "/film/" !in path -> TvType.TvSeries
            "/anime/" in path || "انمي" in path -> TvType.Anime
            "/movies/" in path || "/film/" in path -> TvType.Movie
            
            // Priority 2: HTML Breadcrumbs / Categories as fallback
            document?.selectFirst(".breadcrumb, .Category, .category")?.text()?.let { 
                "مسلسلات" in it || "مسلسل" in it || "حلقات" in it || "انمي" in it
            } == true -> if (document?.selectFirst(".breadcrumb, .Category, .category")?.text()?.contains("انمي") == true) TvType.Anime else TvType.TvSeries
            document?.selectFirst(".breadcrumb, .Category, .category")?.text()?.contains("افلام") == true -> TvType.Movie
            
            // Priority 3: Keywords in slug (Decoded path)
            "مسلسل" in path || "حلقة" in path || "الحلقة" in path || "وي-سيما" in path || "وي_سيما" in path -> TvType.TvSeries
            path.contains("مسلسلات") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a")
        val title = anchor?.selectFirst("strong")?.text()?.trim() 
            ?: anchor?.attr("title")?.trim()
            ?: this.selectFirst("strong")?.text()?.trim()
            ?: return null
            
        val href = fixUrl(anchor?.attr("href") ?: this.attr("href"))
        val posterUrl = extractPosterUrl(this)
        val type = detectType(href, this.ownerDocument())
        
        return when (type) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun extractPosterUrl(element: Element, document: Element? = null): String? {
        val cssUrlRegex = Regex("""url\(['"]?([^')"]+)['"]?\)""")
        val attrs = listOf("style", "data-lazy-style", "data-style", "data-bg", "data-bgset")
        
        // 1. Check the element itself and common child elements
        val elems = listOf(element) + element.select(".BG--GridItem, .BG--Single-begin, .Img--Poster--Single-begin, .Thumb--GridItem, span, a, picture, img, wecima")
        
        for (el in elems) {
            // a) Check attributes for CSS url(...) or direct link
            for (attr in attrs) {
                el.attr(attr).takeIf { it.isNotBlank() }?.let { value ->
                    // CSS url(...)
                    cssUrlRegex.find(value)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return fixUrl(it) }
                    // Direct HTTP/HTTPS or protocol-relative
                    if (value.startsWith("http")) return fixUrl(value)
                    if (value.startsWith("//")) return fixUrl("https:$value")
                }
            }

            // b) Check <img> tags
            el.select("img[data-src], img[data-lazy-src], img[src], img[data-srcset]").forEach { img ->
                val url = img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank { img.attr("src").ifBlank { img.attr("data-srcset") } }
                }
                if (url.isNotBlank()) return fixUrl(url)
            }

            // c) Check <picture> sources
            el.select("picture source[data-srcset], picture img[data-src]").forEach { pic ->
                val url = pic.attr("data-srcset").ifBlank { pic.attr("data-src") }
                if (url.isNotBlank()) return fixUrl(url)
            }
        }

        // 2. Check meta tags in the document (if provided)
        document?.let { doc ->
            listOf("meta[property=og:image]", "meta[name=twitter:image]", "link[rel=image_src]").forEach { selector ->
                doc.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() && !it.contains("logo") && !it.contains("default") }?.let { return fixUrl(it) }
            }
        }

        return null // fallback if nothing found
    }

    // ---------- LOAD ----------
    override suspend fun load(url: String): LoadResponse? {

        val document = safeGet(url) ?: return null
        val fixedUrl = fixUrl(url)

        val title = document.selectFirst(
            "h1[itemprop=name], h1, h2, .Title, .title, meta[property=og:title]"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim() ?: return null

        // TIERED POSTER EXTRACTION
        val posterUrl = 
            document.selectFirst(".Img--Poster--Single-begin")?.let { extractPosterUrl(it, document) }
            ?: extractPosterUrl(document.selectFirst(".Poster--Single-begin") ?: document, document)

        val year = document.selectFirst("a[href*=release-year]")
            ?.text()?.toIntOrNull()

        val plot = document.selectFirst("div.story p, div:contains(قصة العرض) + div, .AsideContext")
            ?.text()?.trim()

        val genres = document.select("a[href*=/genre/]")
            .map { it.text() }

        val actors = document.select("a[href*=/actor/], a[href*=/producer/]")
            .map { Actor(it.text(), "") }

        val duration = document.selectFirst("span:contains(دقيقة)")
            ?.text()?.replace("[^0-9]".toRegex(), "")
            ?.toIntOrNull()

        // Improved series detection using precision logic + DOM override
        val type = detectType(fixedUrl, document)
        val hasEpisodeList = document.select(".EpisodesList, .episodes-list, .season-episodes, .WatchServersList li[data-id]").isNotEmpty() 
            || document.select("a[href*=/episode/], a[href*=/episode-], a:contains(حلقة), a:contains(الحلقة)").size > 1
            
        val isSeries = type == TvType.TvSeries || type == TvType.Anime || hasEpisodeList || fixedUrl.contains("episode") || fixedUrl.contains("series")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val episodeUrls = mutableSetOf<String>()

            // 1. Always include current URL if it looks like an episode
            val currentUrlDecoded = try { java.net.URLDecoder.decode(fixedUrl, "UTF-8") } catch(_: Exception) { fixedUrl }
            if (currentUrlDecoded.contains("حلقة") || currentUrlDecoded.contains("/episode/")) {
                val epNum = currentUrlDecoded.substringAfterLast("حلقة-").substringBefore("-").replace("[^0-9]".toRegex(), "").toIntOrNull()
                    ?: currentUrlDecoded.replace("[^0-9]".toRegex(), "").toIntOrNull()
                
                if (epNum != null && episodeUrls.add(fixedUrl)) {
                    episodes.add(
                        newEpisode(fixedUrl) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            // 2. Get episodes from current page list or seasons
            document.select(".EpisodesList a, div.episodes-list a, div.season-episodes a, a:has(span.episode), a.GridItem:has(strong)").forEach { ep ->
                val epHref = ep.attr("href")
                val fixedEp = fixUrl(epHref)
                // softened filter: only skip if it's the exact series root or category
                if (!fixedEp.startsWith("http") || fixedEp.endsWith("/series/") || fixedEp.contains("/category/") || !episodeUrls.add(fixedEp)) return@forEach
                
                val epText = ep.text().trim()
                val epNum = ep.selectFirst("span.episode, span:contains(حلقة), span:contains(الحلقة)")?.text()
                    ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                    ?: epText.replace("[^0-9]".toRegex(), "").toIntOrNull()
                
                val currentSeason = 1
                val cleanEpisodeNumber = epNum ?: (episodes.count { (it.season ?: 1) == currentSeason } + 1)

                episodes.add(
                    newEpisode(fixedEp) {
                        this.name = "Episode $cleanEpisodeNumber".trim()
                        this.episode = cleanEpisodeNumber
                        this.season = currentSeason
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            // Optimistic Episode Loading: Only try season links if no episodes found
            if (episodes.isEmpty()) {
                val seasonLinks = document.select("a[href*=/season/], a:contains(الموسم)")
                for (seasonLink in seasonLinks) {
                    val seasonHref = seasonLink.attr("href")
                    val seasonNumber = seasonLink.text()
                        .replace("[^0-9]".toRegex(), "")
                        .toIntOrNull()

                    if(seasonHref.isNotEmpty()) {
                        val seasonDoc = safeGet(seasonHref)
                        val epLabels = seasonDoc?.select(".EpisodesList a, a.GridItem, a:has(span.episode)")
                        if (epLabels != null) {
                            val currentSeason = seasonNumber ?: 1
                            for (epLabelInner in epLabels) {
                                val epHref = epLabelInner.attr("href")
                                val fixedEp = fixUrl(epHref)
                                if (!fixedEp.startsWith("http") || !episodeUrls.add(fixedEp)) continue
                                
                                val epText = epLabelInner.text().trim()
                                val epNum = epLabelInner.selectFirst("span.episode, span:contains(حلقة), span:contains(الحلقة)")?.text()
                                    ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                                    ?: epText.replace("[^0-9]".toRegex(), "").toIntOrNull()
                                
                                val cleanEpisodeNumber = epNum ?: (
                                    episodes.count { (it.season ?: 1) == currentSeason } + 1
                                )

                                episodes.add(
                                    newEpisode(fixedEp) {
                                        this.name = "Episode $cleanEpisodeNumber".trim()
                                        this.episode = cleanEpisodeNumber
                                        this.season = currentSeason
                                        this.posterUrl = posterUrl
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            episodes.sortWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

            return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                addActors(actors)
            }
        } else {
            return newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                addActors(actors)
            }
        }
    }

    // ---------- SMART SERVER RANKING ----------
    private val serverPriority = listOf(
        "vidsharing",
        "govid",
        "voe",
        "filemoon",
        "uqload",
        "streamhg",
        "dood",
        "okru"
    )

    private fun getServerScore(name: String): Int {
        val lower = name.lowercase()
        return serverPriority.indexOfFirst { lower.contains(it) }
            .let { if (it == -1) 999 else it }
    }

    private fun getQuality(text: String?): Int {
        val t = text?.lowercase() ?: return Qualities.Unknown.value

        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun decodeProxy(url: String): String {
        return if (url.contains("/play/")) {
            runCatching {
                val b64 = url.substringAfter("/play/")
                    .substringBefore("/")
                    .replace("_", "/")
                    .replace("-", "+")
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }.getOrElse { url }
        } else url
    }

    private fun decodeHex(hex: String): String {
        return try {
            val sb = StringBuilder()
            for (i in 0 until hex.length step 2) {
                val byteHex = hex.substring(i, i + 2)
                sb.append(Integer.parseInt(byteHex, 16).toChar())
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun getMohixLink(url: String, data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(url, headers = headers).text
        val hex = Regex("""(?:const|var)\s+Mohix\s*=\s*["']([0-9a-fA-F]+)["']""").find(page)?.groupValues?.get(1)
        if (hex != null) {
            val decoded = decodeHex(hex)
            if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                callback(
                    newExtractorLink(
                        "MyCima Player",
                        "MyCima Player",
                        decoded,
                        if (decoded.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQuality(decoded)
                    }
                )
                return true
            }
        }
        return false
    }

    // ---------- LOAD LINKS ----------
    // ---------- LOAD LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = safeGet(data) ?: return false

        // Data class to unify server info
        data class ServerInfo(val name: String, val idOrUrl: String, val isAjax: Boolean, val score: Int)

        val servers = document.select(".WatchServersList li")
            .mapNotNull {
                val name = it.text().trim()
                val id = it.attr("data-id")
                val watchUrl = it.attr("data-watch")

                when {
                    id.isNotBlank() -> ServerInfo(name, id, true, getServerScore(name))
                    watchUrl.isNotBlank() -> ServerInfo(name, watchUrl, false, getServerScore(name))
                    else -> null
                }
            }
            .distinctBy { it.idOrUrl }
            .sortedBy { it.score }

        // Also check for direct links in Method 3 style fallback, merging them in if not present
        val extraLinks = document.select("a[href*=filemoon], a[href*=streamhg], a[href*=earnvids]")
            .mapNotNull {
                val href = it.attr("href")
                if (href.isNotBlank()) ServerInfo(it.text(), href, false, getServerScore(it.text())) else null
            }
        
        val allServers = (servers + extraLinks).distinctBy { it.idOrUrl }.sortedBy { it.score }

        val usedLinks = mutableSetOf<String>()
        val foundAtomic = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            allServers.map { server ->
                async {
                    if (foundAtomic.get() && server.score > 10) return@async // Optimization: skip low prio if we have links

                    val finalUrl = if (server.isAjax) {
                        val response = runCatching {
                            app.post(
                                "$activeDomain/wp-admin/admin-ajax.php",
                                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                                data = mapOf(
                                    "action" to "get_player",
                                    "server" to server.idOrUrl
                                )
                            ).document
                        }.getOrNull()

                        val iframe = response?.selectFirst("iframe")?.attr("src")
                        if (iframe.isNullOrBlank()) return@async
                        fixUrl(decodeProxy(iframe))
                    } else {
                        fixUrl(decodeProxy(server.idOrUrl))
                    }

                    synchronized(usedLinks) {
                        if (usedLinks.contains(finalUrl)) return@async
                        usedLinks.add(finalUrl)
                    }

                    if (finalUrl.contains("govid") || finalUrl.contains("vidsharing")) {
                        if (getMohixLink(finalUrl, data, callback)) {
                            foundAtomic.set(true)
                            return@async
                        }
                    }

                    var extractorWorked = false
                    loadExtractor(
                        finalUrl,
                        data,
                        subtitleCallback
                    ) { link ->
                        extractorWorked = true
                        callback(link)
                    }
                    if (extractorWorked) foundAtomic.set(true)
                }
            }.awaitAll()
        }

        // Method 2: Standard iframe extraction (Fallback for embedded players not in list)
        if (!foundAtomic.get()) {
             document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty() && (src.startsWith("http") || src.startsWith("//"))) {
                    val finalUrl = fixUrl(decodeProxy(src))
                    if (!usedLinks.contains(finalUrl)) {
                        usedLinks.add(finalUrl)
                        if (finalUrl.contains("govid")) {
                            runBlocking {
                                if (getMohixLink(finalUrl, data, callback)) {
                                    foundAtomic.set(true)
                                }
                            }
                        }
                        
                        loadExtractor(finalUrl, data, subtitleCallback) { extractedLink ->
                            foundAtomic.set(true)
                            callback(extractedLink)
                        }
                    }
                }
            }
        }

        return foundAtomic.get()
    }
}
