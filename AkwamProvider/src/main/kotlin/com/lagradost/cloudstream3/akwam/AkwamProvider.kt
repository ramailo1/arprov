package com.lagradost.cloudstream3.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AkwamProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://akwams.org"
    override var name = "Akwam"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a.box, a") ?: return null
        val url = a.attr("href") ?: return null
        if (url.contains("/games/") || url.contains("/programs/")) return null
        val poster = selectFirst("picture > img, img")
        val title = poster?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: poster?.attr("title")?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3, .entry-title")?.text()
            ?: return null
        val posterUrl = poster?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: poster?.attr("src")
        val year = selectFirst(".badge-secondary, .badge")?.text()?.toIntOrNull()

        return newMovieSearchResponse(
            title,
            fixUrl(url),
            if (url.contains("/series/")) TvType.TvSeries else TvType.Movie,
        ) {
            this.posterUrl = posterUrl?.let { fixUrlNull(it) }
            this.year = year
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/series?page=" to "Series",
        "$mainUrl/shows?page=" to "Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val list = doc.select("div.entry-box").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document
        return doc.select("div.entry-box").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = fixUrl(url)
        val doc = app.get(cleanUrl).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown"
        val posterUrl = doc.selectFirst("picture > img, .entry-image img, img")?.attr("src")?.let { fixUrlNull(it) }

        val year = doc.select("div.font-size-16.text-white.mt-2, .widget-body div").firstOrNull {
            it.text().contains("السنة")
        }?.text()?.getIntFromText()

        val duration = doc.select("div.font-size-16.text-white.mt-2, .widget-body div").firstOrNull {
            it.text().contains("مدة الفيلم") || it.text().contains("المدة")
        }?.text()?.getIntFromText()

        val synopsis = doc.selectFirst("div.widget-body p, .entry-content p")?.text()?.trim()

        val tags = doc.select("div.font-size-16.d-flex.align-items-center.mt-3 > a, .badge-primary").map {
            it.text().trim()
        }.filter { it.isNotBlank() }

        val actors = doc.select("div.widget-body > div > div.entry-box > a").mapNotNull {
            val name = it?.selectFirst("div > .entry-title")?.text() ?: return@mapNotNull null
            val image = it.selectFirst("div > img")?.attr("src") ?: return@mapNotNull null
            ActorData(Actor(name, fixUrl(image)))
        }

        val epElements = doc.select("#series-episodes a.text-white, #series-episodes a[href]")
            .toList()
            .filter { it.attr("href").isNotBlank() && it.text().isNotBlank() }
            .distinctBy { it.attr("href") }

        val isSeries = epElements.isNotEmpty() || cleanUrl.contains("/series/")

        return if (isSeries && epElements.isNotEmpty()) {
            val episodes = epElements.mapNotNull { a ->
                val epHref = fixUrl(a.attr("href"))
                val epText = a.text().trim()
                val epNum = epText.getIntFromText()
                    ?: Regex("""(?i)الحلقة[^\d]*(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epHref) {
                    name = epText
                    episode = epNum
                    this.posterUrl = posterUrl
                }
            }.let { eps ->
                val isReversed = (eps.lastOrNull()?.episode ?: 1) < (eps.firstOrNull()?.episode ?: 0)
                if (isReversed) eps.reversed() else eps
            }

            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.duration = duration
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = data.trimEnd('/')

        // 1. Fetch Watch page for embedded players (Hgcloud, Mixdrop, etc.)
        val watchUrl = if (baseUrl.endsWith("/watch")) baseUrl else "$baseUrl/watch"
        try {
            val watchDoc = app.get(watchUrl, timeout = 15).document
            watchDoc.select("iframe[src]").forEach { ifr ->
                var src = ifr.attr("src").trim()
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http") && !src.contains("ads") && !src.contains("facebook")) {
                    loadExtractor(src, baseUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}

        // 2. Fetch Download page for all video mirror hosts
        val downloadUrl = if (baseUrl.endsWith("/download")) baseUrl else "$baseUrl/download"
        try {
            val downloadDoc = app.get(downloadUrl, timeout = 15).document
            val mirrorRegex = Regex("""(?i)(?:mixdrop|dood|streamruby|send\.cm|uqload|streamwish|byseraguci|vidhide|voe|hgcloud|mp4upload|vidmoly)""")
            downloadDoc.select("a[href]").forEach { a ->
                var href = a.attr("href").trim()
                if (href.startsWith("//")) href = "https:$href"
                if (href.startsWith("http") && mirrorRegex.containsMatchIn(href)) {
                    loadExtractor(href, baseUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}

        return true
    }
}
