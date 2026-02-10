package com.lagradost.cloudstream3.fushaar

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class FushaarProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://s.fushar.video/m1/"
    override var name = "Fushaar"
    override val usesWebView = false
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
        "$mainUrl/category/افلام-اون-لاين-online-movies/page/" to "Movies | أفلام",
        "$mainUrl/category/افلام-اجنبية-اون-لاين/page/" to "English Movies | أفلام أجنبية",
        "$mainUrl/category/افلام-عربية-arabic-movies/page/" to "Arabic Movies | أفلام عربية",
        "$mainUrl/category/مسلسلات-اون-لاين-online-series/page/" to "Series | مسلسلات",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
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


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hash = Regex("""hash=([^&\s]+)""").find(data)?.groupValues?.get(1)
        
        if (hash != null) {
            try {
                // Decode hash: __ -> / and _ -> +
                val cleanHash = hash.replace("__", "/").replace("_", "+")
                val decoded = String(Base64.decode(cleanHash, Base64.DEFAULT))
                
                // Parse decoded string: "key =? URL" or "key => URL"
                Regex("""(.*?)\s*[=\-][>?]\s*(https?://[^\s\n]+)""").findAll(decoded).forEach { match ->
                    val url = match.groupValues[2].trim()
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Fallback to normal iframe if decoding fails
            }
        }

        // Normal extraction fallback
        val doc = app.get(data).document
        doc.select("iframe").mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }.forEach {
            loadExtractor(fixUrl(it), data, subtitleCallback, callback)
        }
        
        return true
    }
}