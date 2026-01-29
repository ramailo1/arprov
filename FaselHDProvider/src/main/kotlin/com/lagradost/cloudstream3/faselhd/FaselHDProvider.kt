package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FaselHDProvider : MainAPI() {
    override var mainUrl = "https://web1296x.faselhdx.bid"
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.postInner > div.h1")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null

        val img = this.selectFirst("div.imgdiv-class img")
            ?: this.selectFirst("div.postInner img")
            ?: this.selectFirst("img")

        var posterUrl = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("data-image").ifEmpty {
                        it.attr("data-srcset").ifEmpty { it.attr("src") }
                    }
                }
            }
        }

        if (posterUrl != null && posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val quality = this.selectFirst("span.quality")?.text()

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

        val title = doc.selectFirst("div.title")?.text() ?: doc.selectFirst("title")?.text() ?: ""
        val poster = doc.selectFirst("div.posterImg img")?.attr("src")
        val desc = doc.selectFirst("div.singleDesc p")?.text()

        val tags = doc.select("div#singleList .col-xl-6").map { it.text() }
        val year = tags.find { it.contains("سنة الإنتاج") }?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = tags.find { it.contains("مدة") }?.substringAfter(":")?.trim()
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        val isSeries = doc.select("div.epAll").isNotEmpty() || doc.select("div.seasonLoop").isNotEmpty()

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
            doc.select("div#epAll a").forEach { ep ->
                val epTitle = ep.text()
                val epUrl = ep.attr("href")
                val epNumber = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNumber
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val handled = HashSet<String>()

        suspend fun handlePlayer(url: String) {
            if (handled.add(url)) extractVideoFromPlayer(url, data, callback)
        }

        doc.selectFirst("iframe[name=player_iframe]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { handlePlayer(it) }

        doc.select(".tabs-ul > li").forEach { li ->
            val onclick = li.attr("onclick")
            val tabUrl = Regex("""location\.href\s*=\s*['"]([^'"]+)""").find(onclick)?.groupValues?.get(1)
            if (!tabUrl.isNullOrEmpty()) handlePlayer(if (tabUrl.startsWith("http")) tabUrl else mainUrl.trimEnd('/') + "/" + tabUrl.trimStart('/'))
        }

        doc.select("div.downloadLinks a").forEach { loadExtractor(it.attr("href"), data, subtitleCallback, callback) }

        return true
    }

    private suspend fun extractVideoFromPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerHtml = app.get(playerUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text

        val token = Regex("""player_token=([^&]+)""").find(playerUrl)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: Regex("""player_token=([^&]+)""").find(playerHtml)?.groupValues?.get(1)
            ?: return

        val ajaxUrl = Regex("""url\s*:\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)
            .find(playerHtml)?.groupValues?.get(1)?.trim()?.replace("\\/", "/") ?: return

        val absoluteAjaxUrl = if (ajaxUrl.startsWith("http")) ajaxUrl else mainUrl.trimEnd('/') + "/" + ajaxUrl.trimStart('/')

        val response = app.post(
            absoluteAjaxUrl,
            data = mapOf("token" to token),
            referer = playerUrl.substringBefore("?"),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to USER_AGENT
            )
        ).text.replace("\\/", "/")

        Regex("""https?://[^\s"']+\.m3u8""").findAll(response).forEach {
            callback(newExtractorLink(name, "$name HLS", it.value, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                quality = Qualities.Unknown.value
            })
        }
    }
}
