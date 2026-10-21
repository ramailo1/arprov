package com.lagradost.cloudstream3.tuk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.amap
import org.jsoup.nodes.Element
import android.util.Base64
import org.json.JSONObject

class TukProvider : MainAPI() {
    override var mainUrl = "https://tuktukhd.com"
    override var name = "Tuk (Tuk Tuk Cinema)"
    override var lang = "ar"
    override val usesWebView = true
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val cfKiller = CloudflareKiller()
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private suspend fun safeGet(url: String, referer: String = "$mainUrl/"): org.jsoup.nodes.Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
        )
        val res = app.get(url, headers = headers, timeout = 30)
        val title = res.document.select("title").text().lowercase()
        return if (res.code == 403 || title.contains("just a moment") || title.contains("attention required")) {
            app.get(url, headers = headers, interceptor = cfKiller, timeout = 120).document
        } else {
            res.document
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recent/" to "المضاف حديثاً",
        "$mainUrl/category/movies-2/" to "أفلام",
        "$mainUrl/category/series-1/" to "مسلسلات",
        "$mainUrl/sercat/%D9%82%D8%A7%D8%A6%D9%85%D8%A9-%D8%A7%D9%84%D8%A7%D9%86%D9%85%D9%8a/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val doc = safeGet(url)
        
        val items = doc.select(".Block--Item, div[class*='Block--Item']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = safeGet(searchUrl)
        
        return doc.select(".Block--Item, div[class*='Block--Item']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = this.selectFirst(".Block--Info h2.title, .Block--Info h2, .Block--Info h3")?.text()?.trim()
            ?: a.attr("title").trim()
        if (title.isBlank()) return null
        val posterUrl = this.selectFirst(".Poster--Block img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val type = if (href.contains("episode") || href.contains("series")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = safeGet(url)
        
        val title = doc.selectFirst("h1.post-title")?.text()?.trim() ?: return null
        val posterUrl = doc.selectFirst(".MainSingle .image img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }
        val plot = doc.selectFirst(".story p")?.text()?.trim()
        val year = doc.selectFirst(".RightTaxContent a[href*='release-year']")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val isSeries = (doc.selectFirst(".Episodes--Box, .Episodes--List, a[href*='/series/']") != null || url.contains("series")) && !doc.selectFirst(".catssection a[href*='/movies/']").let { it != null }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Try to find full list on the page
            doc.select(".allepcont .row a").forEach { ep ->
                val href = ep.attr("href")
                if (href.isNotBlank()) {
                    val epTitle = ep.selectFirst(".ep-info h2")?.text()?.trim() ?: ""
                    val epNum = ep.selectFirst(".epnum")?.ownText()?.filter { it.isDigit() }?.toIntOrNull() 
                        ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                    val epThumb = ep.selectFirst(".image img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }
                    }
                    
                    episodes.add(newEpisode(href) {
                        this.name = if (epNum != null) "Episode $epNum" else epTitle
                        this.episode = epNum
                        this.posterUrl = epThumb
                    })
                }
            }
            
            // If we are on an episode page and have few episodes, try to find the "Main Series" link in breadcrumbs to get full list
            if (episodes.size <= 1 && url.contains("episode")) {
                val seriesLink = doc.select(".breadcrumb a[href*='/series/']").attr("href")
                if (seriesLink.isNotBlank()) {
                    val seriesDoc = safeGet(seriesLink, url)
                    seriesDoc.select(".allepcont .row a").forEach { ep ->
                        val href = ep.attr("href")
                        if (href.isNotBlank()) {
                            val epTitle = ep.selectFirst(".ep-info h2")?.text()?.trim() ?: ""
                            val epNum = ep.selectFirst(".epnum")?.ownText()?.filter { it.isDigit() }?.toIntOrNull() 
                                ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                            val epThumb = ep.selectFirst(".image img")?.let { img ->
                                img.attr("data-src").ifBlank { img.attr("src") }
                            }
                            
                            episodes.add(newEpisode(href) {
                                this.name = if (epNum != null) "Episode $epNum" else epTitle
                                this.episode = epNum
                                this.posterUrl = epThumb
                            })
                        }
                    }
                }
            }
            
            // If still empty, add itself
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.episode = title.filter { it.isDigit() }.toIntOrNull()
                    this.posterUrl = posterUrl
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.endsWith("/watch/")) data else "${data.removeSuffix("/")}/watch/"
        val doc = try {
            safeGet(watchUrl, data)
        } catch (e: Exception) {
            try {
                safeGet(data, "$mainUrl/")
            } catch (e2: Exception) {
                return false
            }
        }

        // 1. Check data-crypt on iframe (e.g. megatuktuk.store)
        doc.select("iframe[data-crypt]").forEach { iframe ->
            val crypt = iframe.attr("data-crypt").trim()
            if (crypt.isNotBlank()) {
                val decoded = runCatching { String(Base64.decode(crypt, Base64.DEFAULT)) }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    resolveTukServer(decoded, watchUrl, subtitleCallback, callback)
                }
            }
        }

        // 2. Check servers list with data-link or reversed base64
        doc.select(".watch--servers--list .server--item, [data-link], [data-linkbase64]").forEach { li ->
            val b64 = li.attr("data-linkbase64")
            val rawLink = if (b64.isNotBlank()) {
                runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrDefault("")
            } else {
                li.attr("data-link")
            }

            if (rawLink.isNotBlank()) {
                val reversed = rawLink.reversed()
                if (reversed.contains("aHR0c")) {
                    val b64Part = "aHR0c" + reversed.substringAfter("aHR0c").substringBefore("&")
                    val decoded = runCatching { String(Base64.decode(b64Part, Base64.DEFAULT)) }.getOrNull()
                    if (!decoded.isNullOrBlank()) {
                        resolveTukServer(decoded, watchUrl, subtitleCallback, callback)
                    }
                } else {
                    resolveTukServer(rawLink, watchUrl, subtitleCallback, callback)
                }
            }
        }

        // 3. Check downloads section (smart-external-link)
        doc.select("div.downloads a[href*='go.php?u=']").forEach { a ->
            val uParam = a.attr("href").substringAfter("go.php?u=").substringBefore("&")
            val decoded = runCatching { String(Base64.decode(java.net.URLDecoder.decode(uParam, "UTF-8"), Base64.DEFAULT)) }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                resolveTukServer(decoded, watchUrl, subtitleCallback, callback)
            }
        }

        // 4. Check iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && !src.contains("ads") && !src.contains("youtube") && !src.startsWith("javascript") && src != "about:blank") {
                resolveTukServer(src, watchUrl, subtitleCallback, callback)
            }
        }

        // 5. Check json-ld / schema for embedUrl
        val docHtml = doc.html()
        val embedRegex = Regex("""["']embedUrl["']\s*:\s*["']([^"']+)["']""")
        embedRegex.findAll(docHtml).forEach { match ->
            val link = match.groupValues[1].trim()
            if (link.isNotBlank()) {
                resolveTukServer(link, watchUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun resolveTukServer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val link = if (url.startsWith("//")) "https:$url" else url
        if (link.isBlank() || link.startsWith("javascript")) return

        if (link.contains("megatuktuk.store/iframe/") || link.contains("share4max.org/download/")) {
            val hashId = link.substringAfterLast("/")
            val iframeUrl = "https://megatuktuk.store/iframe/$hashId"
            try {
                val resp = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "X-Inertia" to "true",
                        "X-Inertia-Partial-Component" to "files/mirror/video",
                        "X-Inertia-Partial-Data" to "streams",
                        "X-Inertia-Version" to "7a7eafc95b15a2110f8f43bd7706b997",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
                val json = JSONObject(resp.text)
                val streamArray = json.optJSONObject("props")?.optJSONObject("streams")?.optJSONArray("data")
                if (streamArray != null) {
                    for (i in 0 until streamArray.length()) {
                        val streamObj = streamArray.optJSONObject(i) ?: continue
                        val mirrors = streamObj.optJSONArray("mirrors") ?: continue
                        for (j in 0 until mirrors.length()) {
                            val mirror = mirrors.optJSONObject(j) ?: continue
                            val mirrorLink = mirror.optString("link", "")
                            val fixed = if (mirrorLink.startsWith("//")) "https:$mirrorLink" else mirrorLink
                            if (fixed.isNotBlank()) {
                                loadExtractor(fixed, "https://megatuktuk.store/", subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        loadExtractor(link, referer, subtitleCallback, callback)
    }
}
