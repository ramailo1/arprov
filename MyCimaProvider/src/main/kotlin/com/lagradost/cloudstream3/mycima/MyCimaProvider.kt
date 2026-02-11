package com.lagradost.cloudstream3.mycima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class MyCimaProvider : MainAPI() {

    // ---------- AUTO DOMAIN POOL ----------
    private val domainPool = listOf(
        "https://mycima.rip",
        "https://mycima.bond",
        "https://mycima.sbs",
        "https://mycima.live"
    )

    private var activeDomain: String = domainPool.first()

    override var mainUrl = activeDomain
    override var name = "MyCima"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
            "Referer" to activeDomain
        )

    // ---------- AUTO DOMAIN DETECTOR ----------
    private suspend fun getWorkingDomain(): String? {
        for (domain in domainPool) {
            val working = runCatching {
                app.get(domain, timeout = 10).isSuccessful
            }.getOrDefault(false)

            if (working) {
                activeDomain = domain
                mainUrl = domain
                return domain
            }
        }
        return null
    }

    private suspend fun safeGet(url: String): org.jsoup.nodes.Document? {
        val base = getWorkingDomain() ?: return null

        return runCatching {
            app.get(
                if (url.startsWith("http")) url else base + url,
                headers = headers
            ).document
        }.getOrNull()
    }

    // ---------- MAIN PAGE ----------
    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/movies/" to "أفلام",
        "/series/" to "مسلسلات",
        "/episodes/" to "الحلقات"
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
        val document = safeGet("/?s=$query") ?: return emptyList()

        return document.select("div#MainFiltar > .GridItem, .GridItem")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a")
        val title = anchor?.selectFirst("strong")?.text()?.trim() 
            ?: anchor?.attr("title")?.trim()
            ?: this.selectFirst("strong")?.text()?.trim()
            ?: return null
            
        val href = fixUrl(anchor?.attr("href") ?: this.attr("href"))
        val posterUrl = extractPosterUrl(this)
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
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

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

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

        // Improved series detection - check URL first, then verify with elements
        // Movies should NOT be classified as series even if they have related content sections
        val isSeriesUrl = fixedUrl.contains("/series/") || fixedUrl.contains("/episode/")
        val hasEpisodeElements = document.select(".EpisodesList a[href*=/episode/], a[href*=/episode/]").isNotEmpty()
        val isSeries = isSeriesUrl && hasEpisodeElements

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Get episodes from current page list or seasons
            document.select(".EpisodesList a, div.episodes-list a, div.season-episodes a, a:has(span.episode)").forEach { ep ->
                val epHref = ep.attr("href")
                val epName = ep.selectFirst("strong")?.text() ?: ep.text().trim()
                val epNum = ep.selectFirst("span.episode, span:contains(حلقة)")?.text()
                    ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                
                if (epHref.isNotEmpty()) {
                    episodes.add(
                        newEpisode(fixUrl(epHref)) {
                            this.name = epName
                            this.episode = epNum
                        }
                    )
                }
            }
            
            // If no episodes found, try season links
            if (episodes.isEmpty()) {
                val seasonLinks = document.select("a[href*=/season/], a:contains(الموسم)")
                seasonLinks.forEach { seasonLink ->
                    val seasonHref = seasonLink.attr("href")
                    if(seasonHref.isNotEmpty()) {
                        val seasonDoc = safeGet(seasonHref)
                        seasonDoc?.select(".EpisodesList a, a.GridItem, a:has(span.episode)")?.forEach { ep ->
                            val epHref = ep.attr("href")
                            val epName = ep.selectFirst("strong")?.text() ?: ep.text().trim()
                            val epNum = ep.selectFirst("span.episode")?.text()
                                ?.replace("[^0-9]".toRegex(), "")?.toIntOrNull()
                            
                            if (epHref.isNotEmpty()) {
                                episodes.add(
                                    newEpisode(fixUrl(epHref)) {
                                        this.name = epName
                                        this.episode = epNum
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
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
        return when {
            text?.contains("1080") == true -> Qualities.P1080.value
            text?.contains("720") == true -> Qualities.P720.value
            text?.contains("480") == true -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // ---------- LOAD LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Helper to decode govid.live/play/ proxy URLs
        val decodeProxy: (String) -> String = { url ->
            if (url.contains("govid.live/play/")) {
                try {
                    val b64 = url.substringAfter("/play/").substringBefore("/").replace("_", "/").replace("-", "+")
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                } catch (e: Exception) { url }
            } else url
        }

        val document = safeGet(data) ?: return false

        val servers = document.select(".WatchServersList li[data-id]")
            .map {
                val name = it.text()
                val id = it.attr("data-id")
                Triple(name, id, getServerScore(name))
            }
            .filter { it.second.isNotBlank() }
            .sortedBy { it.third } // smart priority ranking

        val usedLinks = mutableSetOf<String>()

        // Method 1: AJAX Player extraction
        for ((serverName, serverId, _) in servers) {

            val ajaxUrl =
                "$activeDomain/wp-admin/admin-ajax.php?action=get_player&server=$serverId"

            val response = runCatching {
                app.get(ajaxUrl, headers = headers).document
            }.getOrNull() ?: continue

            val iframe = response.selectFirst("iframe")?.attr("src")
                ?: continue

            val finalUrl = fixUrl(decodeProxy(iframe))

            if (usedLinks.contains(finalUrl)) continue
            usedLinks.add(finalUrl)

            val quality = getQuality(serverName)

            loadExtractor(
                finalUrl,
                subtitleCallback,
                callback
            )
        }

        // Method 2: Standard iframe extraction (fallback)
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && (src.startsWith("http") || src.startsWith("//"))) {
                val finalUrl = fixUrl(decodeProxy(src))
                if (!usedLinks.contains(finalUrl)) {
                    usedLinks.add(finalUrl)
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            }
        }

        // Method 3: Direct server links (fallback)
        document.select("ul#watch li[data-watch], a[href*=filemoon], a[href*=streamhg], a[href*=earnvids]").forEach { link ->
            val href = link.attr("data-watch").ifEmpty { link.attr("href") }
            if (href.isNotEmpty()) {
                val finalUrl = fixUrl(decodeProxy(href))
                if (!usedLinks.contains(finalUrl)) {
                    usedLinks.add(finalUrl)
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
