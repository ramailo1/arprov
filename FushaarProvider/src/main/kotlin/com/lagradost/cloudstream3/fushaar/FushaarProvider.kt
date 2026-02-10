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
        val year = doc.select("div.date").text().getIntFromText() ?: title.getIntFromText()
        val synopsis = doc.select("div.details > p").text()
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
        val doc = app.get(data).document
        
        // Method 1: Decode Base64 hash from akoam.news links
        val hashLinks = doc.select("a[href*='akoam.news/article454']")
            .mapNotNull { it.attr("href").takeIf { s -> s.isNotBlank() } }
        
        for (link in hashLinks) {
            try {
                val hashParam = Regex("""[?&]hash=([^&]+)""").find(link)?.groupValues?.get(1) ?: continue
                val decoded = String(android.util.Base64.decode(hashParam, android.util.Base64.DEFAULT))
                
                // Extract player URL from decoded text (Format: "... = <br>https://w.aflamy.pro/albaplayer/...")
                val playerUrl = Regex("""https?://[^\s<>"']+""").find(decoded)?.value
                if (playerUrl != null && playerUrl.contains("aflamy.pro/albaplayer")) {
                    if (extractFromPlayerPage(playerUrl, data, subtitleCallback, callback)) return true
                }
            } catch (e: Exception) { }
        }

        // Method 2: Fallback construction from slug
        try {
            val slug = data.substringAfterLast("/video-").substringBefore("-ar-online").substringBefore("/")
            if (slug.isNotBlank()) {
                val playerUrl = "https://w.aflamy.pro/albaplayer/$slug"
                if (extractFromPlayerPage(playerUrl, data, subtitleCallback, callback)) return true
            }
        } catch (e: Exception) { }

        // Method 3: Existing button extraction (fallback)
        val playerUrl = doc.select("div#FCplayer a.video-play-button, div#FCplayer a.controls-play-pause-big")
            .mapNotNull { it.attr("href").takeIf { s -> s.isNotBlank() } }
            .firstOrNull()
            
        if (playerUrl != null && playerUrl != data) {
            loadExtractor(fixUrl(playerUrl), data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun extractFromPlayerPage(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val playerDoc = app.get(playerUrl).document
            
            // 1. Primary iframe selector
            val iframeSrc = playerDoc.select("iframe#iframe").attr("src")
            if (iframeSrc.isNotBlank()) {
                loadExtractor(fixUrl(iframeSrc), referer, subtitleCallback, callback)
                return true
            }

            // 2. Secondary iframe/link search (embed patterns)
            playerDoc.select("a[href*='/embed-'], iframe[src*='/embed-']").forEach { element ->
                val embedUrl = element.attr("href").ifBlank { element.attr("src") }
                if (embedUrl.isNotBlank()) {
                    loadExtractor(fixUrl(embedUrl), referer, subtitleCallback, callback)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}