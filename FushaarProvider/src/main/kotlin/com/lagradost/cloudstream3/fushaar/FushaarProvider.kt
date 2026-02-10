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
        // 1. Parse the main page (Static HTML) to find the player button
        val doc = app.get(data).document
        val playerUrl = doc.select("div#FCplayer a.video-play-button, div#FCplayer a.controls-play-pause-big")
            .mapNotNull { it.attr("href").takeIf { s -> s.isNotBlank() } }
            .firstOrNull() ?: data // Fallback to main page if button not found

        // 2. Load the player URL (or main URL) using WebView logic
        loadExtractor(fixUrl(playerUrl), data, subtitleCallback, callback)
        return true
    }
}