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
    override var mainUrl = "https://s.fushar.video"
    override var name = "Fushaar"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)


    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("div.thumb > a") ?: return null
        val url = a.attr("href")
        val img = a.selectFirst("img") ?: return null
        val posterUrl = img.attr("data-src").ifBlank { img.attr("src") }
        val title = img.attr("alt")
        
        return newMovieSearchResponse(
            title,
            fixUrl(url),
            TvType.Movie,
        ) {
            this.posterUrl = fixUrl(posterUrl)
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
        return app.get("$mainUrl/?s=$q").document.select("article.poster").mapNotNull {
            it.toSearchResponse()
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("div.info-warpper h1").text().ifBlank { doc.title() }
        val posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val year = doc.select("div.date, div.year").text().getIntFromText() ?: title.getIntFromText()
        val synopsis = doc.select("div.details > p, div.description").text()
        val tags = doc.select("div.categories a, div.tags a").map { it.text() }
        val recommendations = doc.select("li.video-grid").mapNotNull { element ->
            element.toSearchResponse()
        }

        // The "watch" page might be different. Let's find the play button.
        val watchUrl = doc.selectFirst("a.video-play-button, a#play-video")?.attr("href") ?: url

        return newMovieLoadResponse(
            title,
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
            println("Fushaar Debug: Found hash in data URL: $hashValue")
            hashValue?.split("__")?.forEach { part ->
                try {
                    val decoded = String(android.util.Base64.decode(part, android.util.Base64.DEFAULT))
                    println("Fushaar Debug: Decoded part from data URL: $decoded")
                    val playerUrl = Regex("""https?://[^\s<>"']+""").find(decoded)?.value
                    if (playerUrl != null && (playerUrl.contains("aflamy.pro") || playerUrl.contains("albaplayer.pro") || playerUrl.contains("shadwo.pro"))) {
                        println("Fushaar Debug: Targeting playerUrl (Method 0): $playerUrl")
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
                println("Fushaar Debug: Found hash link: $link")
                hashValue.split("__").forEach { part ->
                    try {
                        val decoded = String(android.util.Base64.decode(part, android.util.Base64.DEFAULT))
                        println("Fushaar Debug: Decoded part: $decoded")
                        val playerUrl = Regex("""https?://[^\s<>"']+""").find(decoded)?.value
                        if (playerUrl != null && (playerUrl.contains("aflamy.pro") || playerUrl.contains("albaplayer.pro") || playerUrl.contains("shadwo.pro"))) {
                            println("Fushaar Debug: Targeting playerUrl: $playerUrl")
                            if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) {
                                println("Fushaar Debug: Success via Method 1")
                                anySuccess = true
                            }
                        }
                    } catch (e: Exception) { 
                        println("Fushaar Debug: Method 1 Exception: ${e.message}")
                    }
                }
            }
        
        if (anySuccess) return true

        // Method 2: Slug fallback (Improved)
        if (data.contains("/video-")) {
            try {
                val slug = data.substringAfterLast("/video-").substringBefore("-ar-online").substringBefore("/").trim()
                println("Fushaar Debug: Extracted slug: $slug")
                if (slug.isNotBlank()) {
                    val playerUrl = "https://w.aflamy.pro/albaplayer/$slug"
                    println("Fushaar Debug: Targeting playerUrl (Method 2): $playerUrl")
                    if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) {
                        println("Fushaar Debug: Success via Method 2")
                        return true
                    }
                }
            } catch (e: Exception) { 
                println("Fushaar Debug: Method 2 Exception: ${e.message}")
            }
        }

        // Method 3: Direct buttons
        doc.select("a.video-play-button, a#play-video, div#FCplayer a")
            .mapNotNull { it.attr("href").takeIf { it.isNotBlank() && it != data } }
            .distinct().forEach { playerUrl ->
                println("Fushaar Debug: Method 3 check playerUrl: $playerUrl")
                if (loadExtractorDirect(playerUrl, data, subtitleCallback, callback)) anySuccess = true
            }

        println("Fushaar Debug: Final anySuccess: $anySuccess")
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