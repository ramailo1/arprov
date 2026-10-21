package com.lagradost.cloudstream3.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class FushaarProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://u.fushar.video"
    override var name = "Fushaar"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("div.thumb > a") ?: return null
        val url = a.attr("href")
        val img = a.selectFirst("img") ?: return null
        val posterUrl = img.attr("data-src").ifBlank { img.attr("src") }
        val title = img.attr("alt")
        
        val isSeries = title.contains("مسلسل") || title.contains("الحلقة") || url.contains("مسلسل") || url.contains("series")
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(url), type) {
                this.posterUrl = fixUrl(posterUrl)
            }
        } else {
            newMovieSearchResponse(title, fixUrl(url), type) {
                this.posterUrl = fixUrl(posterUrl)
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اون-لاين-online-movies/" to "Movies | أفلام",
        "$mainUrl/category/مسلسلات-اون-لاين-online-series/" to "Series | مسلسلات",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(url).document
        val list = doc.select("li.video-grid").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        return app.get("$mainUrl/?s=$q").document.select("article.poster, li.video-grid").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val rawTitle = doc.select("div.info-warpper h1, h1").text().ifBlank { doc.title() }
        val posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val year = doc.select("div.date, div.year").text().getIntFromText() ?: rawTitle.getIntFromText()
        val synopsis = doc.select("div.details > p, div.description").text()
        val tags = doc.select("div.categories a, div.tags a").map { it.text() }
        val recommendations = doc.select("li.video-grid").mapNotNull { element ->
            element.toSearchResponse()
        }

        val isCategoryPage = url.contains("/category/") && !url.contains("/category/مسلسلات-اون-لاين-online-series/")
        val breadcrumbs = doc.select("ol.breadcrumbs a, .breadcrumbs a")
        val seriesCrumb = breadcrumbs.firstOrNull { 
            val h = it.attr("href")
            h.contains("/category/") && 
            !h.contains("/category/مسلسلات-اون-لاين-online-series/") && 
            !h.contains("/category/افلام-اون-لاين-online-movies/") &&
            h != "$mainUrl/"
        }
        val isSeries = isCategoryPage || seriesCrumb != null || 
            rawTitle.contains("مسلسل") || rawTitle.contains("الحلقة") || 
            url.contains("مسلسل") || url.contains("الحلقة")

        if (isSeries) {
            val seriesUrl = if (isCategoryPage) url else seriesCrumb?.attr("href")
            val cleanTitle = seriesCrumb?.text()?.ifBlank { null } 
                ?: rawTitle.substringBefore("الحلقة").trim()
            val episodes = mutableListOf<Episode>()
            val targetUrl = seriesUrl ?: url
            var nextEpPageUrl: String? = targetUrl
            var epPage = 1

            while (nextEpPageUrl != null && epPage <= 10) {
                val epDoc = if (epPage == 1 && targetUrl == url) doc else try {
                    app.get(fixUrl(nextEpPageUrl)).document
                } catch (_: Exception) { null }

                val items = epDoc?.select("li.video-grid") ?: emptyList()
                if (items.isEmpty()) break

                items.forEach { element ->
                    val a = element.selectFirst("div.thumb > a") ?: element.selectFirst("a") ?: return@forEach
                    val epHref = fixUrl(a.attr("href"))
                    val img = a.selectFirst("img")
                    val epPoster = img?.attr("data-src")?.ifBlank { img.attr("src") }
                    val epTitle = img?.attr("alt")?.ifBlank { a.attr("title") } ?: a.text()
                    val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+""").find(epTitle)?.groupValues?.firstOrNull()?.toIntOrNull()
                    val seasonNum = Regex("""الموسم\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = seasonNum
                            this.posterUrl = epPoster?.let { fixUrl(it) }
                        }
                    )
                }

                val nextLink = epDoc?.selectFirst("a.next, .pagination a.next, a[rel='next']")?.attr("href")
                if (!nextLink.isNullOrBlank() && nextLink != nextEpPageUrl) {
                    nextEpPageUrl = nextLink
                    epPage++
                } else {
                    nextEpPageUrl = null
                }
            }

            if (episodes.isEmpty()) {
                val watchUrl = doc.selectFirst("a.video-play-button, a#play-video")?.attr("href") ?: url
                val epNum = Regex("""الحلقة\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+""").find(rawTitle)?.groupValues?.firstOrNull()?.toIntOrNull() ?: 1
                val seasonNum = Regex("""الموسم\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodes.add(
                    newEpisode(fixUrl(watchUrl)) {
                        this.name = rawTitle
                        this.episode = epNum
                        this.season = seasonNum
                        this.posterUrl = posterUrl?.let { fixUrl(it) }
                    }
                )
            }

            return newTvSeriesLoadResponse(
                cleanTitle,
                url,
                TvType.TvSeries,
                episodes.distinctBy { it.data }.reversed()
            ) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
            }
        }

        // The "watch" page might be different. Let's find the play button.
        val watchUrl = doc.selectFirst("a.video-play-button, a#play-video")?.attr("href") ?: url

        return newMovieLoadResponse(
            rawTitle,
            url,
            TvType.Movie,
            fixUrl(watchUrl)
        ) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
            this.year = year
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
        }
    }


    override val hasChromecastSupport = true

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anySuccess = false
        val doc = app.get(data).document
        
        // Method 0: Check if 'data' URL itself has a hash (common when load() passes an akoam redirect)
        if (data.contains("hash=")) {
            val hashValue = Regex("""[?&]hash=([^&]+)""").find(data)?.groupValues?.get(1)
            hashValue?.split("__")?.forEach { part ->
                try {
                    val decoded = String(android.util.Base64.decode(part, android.util.Base64.DEFAULT))
                    val playerUrl = Regex("""https?://[^\s<>"']+""").find(decoded)?.value
                    if (playerUrl != null && (playerUrl.contains("aflamy.pro") || playerUrl.contains("albaplayer.pro") || playerUrl.contains("shadwo.pro") || playerUrl.contains("dazzwo.pro") || playerUrl.contains("dazwo.pro"))) {
                        if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) anySuccess = true
                    }
                } catch (e: Exception) { }
            }
        }
        if (anySuccess) return true

        // Method 1: Decode Base64 from akoam (Handle __ separator)
        doc.select("a[href*='akoam.news/article454']")
            .mapNotNull { it.attr("href").takeIf { it.isNotBlank() } }
            .forEach { link ->
                val hashValue = Regex("""[?&]hash=([^&]+)""").find(link)?.groupValues?.get(1) ?: return@forEach
                hashValue.split("__").forEach { part ->
                    try {
                        val decoded = String(android.util.Base64.decode(part, android.util.Base64.DEFAULT))
                        val playerUrl = Regex("""https?://[^\s<>"']+""").find(decoded)?.value
                        if (playerUrl != null && (playerUrl.contains("aflamy.pro") || playerUrl.contains("albaplayer.pro") || playerUrl.contains("shadwo.pro") || playerUrl.contains("dazzwo.pro") || playerUrl.contains("dazwo.pro"))) {
                            if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) {
                                anySuccess = true
                            }
                        }
                    } catch (e: Exception) { 
                    }
                }
            }
        
        if (anySuccess) return true

        // Method 2: Slug fallback (Improved)
        if (data.contains("/video-")) {
            try {
                val slug = data.substringAfterLast("/video-").substringBefore("-ar-online").substringBefore("/").trim()
                if (slug.isNotBlank()) {
                    val playerUrl = "https://w.aflamy.pro/albaplayer/$slug"
                    if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) {
                        return true
                    }
                }
            } catch (e: Exception) { 
            }
        }

        // Method 3: Direct buttons
        doc.select("a.video-play-button, a#play-video, div#FCplayer a")
            .mapNotNull { it.attr("href").takeIf { it.isNotBlank() && it != data } }
            .distinct().forEach { playerUrl ->
                if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) anySuccess = true
            }

        // Method 4: Check iframes on page (including internal /embed/ pages)
        doc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("http")) {
                if (src.contains("fushar") && src.contains("/embed/")) {
                    try {
                        val embedDoc = app.get(src, referer = data).document
                        embedDoc.select("iframe[src]").forEach { innerIframe ->
                            var innerSrc = innerIframe.attr("src")
                            if (innerSrc.startsWith("//")) innerSrc = "https:$innerSrc"
                            if (innerSrc.startsWith("http")) {
                                if (loadExtractorDirect(innerSrc, src, subtitleCallback, callback)) anySuccess = true
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                if (loadExtractorDirect(src, data, subtitleCallback, callback)) anySuccess = true
            }
        }

        return anySuccess
    }

    private suspend fun loadExtractorDirect(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            loadExtractor(fixUrl(url), referer, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            false
        }
    }
}