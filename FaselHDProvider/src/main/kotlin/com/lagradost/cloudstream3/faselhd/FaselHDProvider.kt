package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FaselHDProvider : MainAPI() {
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val baseDomain = "faselhdx.bid"
    override var mainUrl = "https://web3126x.$baseDomain"

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "User-Agent" to userAgent,
        "Referer" to mainUrl
    )

    companion object {
        private val M3U8_REGEX = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
        private val SOURCES_REGEX = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""")
        private val PLAYER_TOKEN_REGEX = Regex("""video_player\?player_token=[^"'\s]+""")
    }

    private val cfKiller = CloudflareKiller()
    private val mutex = Mutex()

    // Resolve the real (post-redirect) mainUrl
    private suspend fun getRealMainUrl(): String {
        return try {
            val resp = app.get(mainUrl, allowRedirects = true, timeout = 10)
            val finalUrl = resp.url.trimEnd('/')
            val uri = java.net.URL(finalUrl)
            "${uri.protocol}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }

    // ---------- CLOUDFLARE BYPASS ----------
    private fun isBlocked(doc: Document): Boolean {
        val title = doc.select("title").text().lowercase()
        val body = doc.body().text().lowercase()
        return title.contains("just a moment") ||
               title.contains("security verification") ||
               title.contains("access denied") ||
               title.contains("cloudflare") ||
               title.contains("verify you are human") ||
               body.contains("verifying you are not a bot") ||
               body.contains("performing security verification") ||
               body.contains("checking your browser")
    }

    private suspend fun safeGet(url: String): Document? {
        return runCatching {
            val res = app.get(url, headers = defaultHeaders, timeout = 15)
            if (res.isSuccessful && !isBlocked(res.document)) return res.document
            
            mutex.withLock {
                val cfRes = app.get(url, headers = defaultHeaders, interceptor = cfKiller, timeout = 120)
                if (cfRes.isSuccessful) {
                    delay(2000)
                    if (!isBlocked(cfRes.document)) return cfRes.document
                }
                null
            }
        }.getOrNull()
    }

    // ---------- MAIN PAGE ----------
    override val mainPage = mainPageOf(
        "$mainUrl/most_recent" to "المضاف حديثاَ",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/anime" to "الأنمي",
        "$mainUrl/tvshows" to "البرامج التلفزيونية",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime-movies" to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = getRealMainUrl()
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = safeGet(url) ?: return newHomePageResponse(request.name, emptyList())
        val elements = doc.select("div.postDiv, article, .entry-box")
        return newHomePageResponse(request.name, elements.mapNotNull { it.toSearchResult() })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1, div.h1, .entry-title, h3, .title")?.text() 
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotEmpty() }
            ?: return null
        val href = selectFirst("a")?.attr("abs:href") ?: return null
        
        val img = selectFirst("div.imgdiv-class img, div.postInner img, img")
        val posterUrl = img?.let {
            (it.attr("data-src").ifEmpty { it.attr("data-original") }.ifEmpty { it.attr("src") })
                .let { url -> if (url.startsWith("//")) "https:$url" else url }
        }
        val quality = selectFirst("span.quality, span.qualitySpan")?.text()

        val type = if (href.contains("/episode/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = getRealMainUrl()
        val url = "$mainUrl/?s=$query"
        val doc = safeGet(url) ?: return emptyList()
        return doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = getRealMainUrl()
        val doc = safeGet(url) ?: return null
        val title = doc.selectFirst("div.title, h1.postTitle, div.postInner div.h1, .entry-title, h1")
            ?.text() ?: doc.title()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("div.posterImg img, .single-post img, img.posterImg, .entry-thumbnail img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.let { if (it.startsWith("//")) "https:$it" else it }

        val desc = doc.selectFirst("div.singleDesc p, div.singleDesc, .entry-content p, .story, .description")?.text()
        val year = doc.selectFirst("a[href*='series_year'], a[href*='movies_year']")
            ?.text()?.toIntOrNull()
            ?: doc.select("#singleList .col-xl-6, .single_info").find { it.text().contains("الصدور") || it.text().contains("سنة") }
                ?.text()?.substringAfter(":")?.trim()?.toIntOrNull()

        val recommendations = doc.select("div.postDiv, article, .entry-box").mapNotNull { it.toSearchResult() }
        val ph = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)

        val episodeLinks = doc.select("#vihtml a[href*='/episodes/'], #epAll a, div.epAll a")
        val isSeries = episodeLinks.isNotEmpty() 
            || doc.select("#seasonList, div.seasonLoop, a[href*='/episodes/']").isNotEmpty()
            || url.contains("/episodes/") || url.contains("/series/")

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.recommendations = recommendations
                this.posterHeaders = ph
            }
        } else {
            val episodes = episodeLinks.mapIndexed { idx, ep ->
                val epTitle = ep.text().trim()
                val epUrl = ep.attr("abs:href")
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                }
            }.distinctBy { it.data }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.recommendations = recommendations
                this.posterHeaders = ph
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        mainUrl = getRealMainUrl()
        val fixedData = if (data.contains(baseDomain)) {
            val uri = java.net.URL(data)
            data.replace("${uri.protocol}://${uri.host}", mainUrl)
        } else data

        val playerTokenUrl = runCatching {
            WebViewResolver(PLAYER_TOKEN_REGEX).resolveUsingWebView(
                requestCreator("GET", fixedData, referer = mainUrl, headers = defaultHeaders)
            ).first?.url?.toString()
        }.getOrNull()

        if (playerTokenUrl != null) {
            val m3u8Url = runCatching {
                WebViewResolver(
                    Regex("""\.m3u8"""),
                    additionalUrls = listOf(Regex("""\.mp4"""))
                ).resolveUsingWebView(
                    requestCreator("GET", playerTokenUrl, referer = fixedData, headers = defaultHeaders)
                ).first?.url?.toString()
            }.getOrNull()

            if (m3u8Url != null) {
                callback.invoke(
                    newExtractorLink(name, name, m3u8Url,
                        if (m3u8Url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerTokenUrl
                    }
                )
                return true
            }
        }

        return inlineM3u8Fallback(fixedData, callback)
    }

    private suspend fun inlineM3u8Fallback(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = safeGet(url) ?: return false
        val html = doc.html()

        val iframeSrc = doc.select("iframe[src*=video_player]").attr("abs:src").ifEmpty {
            doc.select("iframe[data-src*=video_player]").attr("abs:data-src")
        }

        if (iframeSrc.isNotEmpty()) {
            val m3u8 = runCatching {
                WebViewResolver(Regex("""\.m3u8""")).resolveUsingWebView(
                    requestCreator("GET", iframeSrc, referer = url, headers = defaultHeaders)
                ).first?.url?.toString()
            }.getOrNull()

            if (m3u8 != null) {
                callback.invoke(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) { this.referer = iframeSrc })
                return true
            }
        }
        
        val matches = (M3U8_REGEX.findAll(html) + SOURCES_REGEX.findAll(html) + 
                       Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)""").findAll(html))
            .map { it.groupValues.getOrNull(1) ?: it.value }
            .filterNotNull().distinct().toList()
        
        if (matches.isEmpty()) return false
        
        matches.forEach { videoUrl ->
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            callback.invoke(
                newExtractorLink(name, name, videoUrl,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = getVideoQuality(videoUrl)
                }
            )
        }
        return true
    }

    private fun getVideoQuality(quality: String?): Int {
        if (quality == null) return Qualities.Unknown.value
        return when {
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
