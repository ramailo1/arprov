package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FaselHDProvider : MainAPI() {
    override var mainUrl = "https://web12918x.faselhdx.bid"
    override var name = "FaselHD"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/most_recent" to "Recently Added",
        "$mainUrl/series" to "Series",
        "$mainUrl/movies" to "Movies",
        "$mainUrl/asian-series" to "Asian Series",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/tvshows" to "TV Shows",
        "$mainUrl/dubbed-movies" to "Dubbed Movies",
        "$mainUrl/hindi" to "Hindi",
        "$mainUrl/asian-movies" to "Asian Movies",
        "$mainUrl/anime-movies" to "Anime Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url).document
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1, h1, h2.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val img = selectFirst("div.imgdiv-class img, div.postInner img, img")
        var posterUrl = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("data-image").ifEmpty {
                        it.attr("data-srcset").ifEmpty { it.attr("src") }
                    }
                }
            }
        }

        if (!posterUrl.isNullOrEmpty() && posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val quality = selectFirst("span.quality")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.title, h1")?.text() ?: ""
        val poster = doc.selectFirst("div.posterImg img, img.poster")?.attr("src")
        val desc = doc.selectFirst("div.singleDesc p, div.singleDesc")?.text()

        val tags = doc.select("div#singleList .col-xl-6, div.singleInfo p").map { it.text() }
        val year = tags.find { it.contains("سنة الإنتاج") || it.contains("موعد الصدور") }
            ?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = tags.find { it.contains("مدة") }?.substringAfter(":")?.trim()
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        val isSeries = doc.select("div.epAll a, div#seasonList, a[href*=\"/episodes/\"]").isNotEmpty()

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("div#epAll a, div.episodeList a, a[href*=\"/episodes/\"]").forEach { ep ->
                val epTitle = ep.text()
                val epUrl = ep.absUrl("href").ifEmpty { ep.attr("href") }
                if (epUrl.isNotEmpty() && !epUrl.contains("#")) {
                    val epNumber = Regex("""(?:الحلقة|episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = epNumber
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // 1) Extract watch player iframe URL
        val playerUrl = doc.selectFirst("a[href*=\"video_player\"], iframe[src*=\"video_player\"], iframe[src*=\"embed\"]")
            ?.let { it.absUrl("href").ifEmpty { it.absUrl("src") } }

        if (!playerUrl.isNullOrEmpty()) {
            // Fetch the player page
            val playerDoc = app.get(playerUrl, referer = data).document
            val playerHtml = playerDoc.html()

            // Direct M3U8/MP4 extraction from player HTML
            val directPattern = Regex("""(https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:[^\s"'<>]*))""", RegexOption.IGNORE_CASE)
            directPattern.findAll(playerHtml).forEach { match ->
                val videoUrl = match.groupValues[1]
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        "$name - Watch",
                        videoUrl,
                        referer = playerUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                    )
                )
            }
        }

        // 2) Extract download links (T7MEEL, etc.)
        doc.select("a[href*=\"t7meel\"], a[href*=\"/download\"], a:matchesOwn((?i)تنزيل|تحميل|download)").forEach { link ->
            val dlUrl = link.absUrl("href")
            if (dlUrl.startsWith("http") && !dlUrl.contains("#")) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        "$name - Download",
                        dlUrl,
                        referer = data,
                        quality = Qualities.Unknown.value
                    )
                )
            }
        }

        return true
    }
}
