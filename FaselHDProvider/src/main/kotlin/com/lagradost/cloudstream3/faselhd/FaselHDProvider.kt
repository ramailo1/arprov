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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url).document
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val img = selectFirst("div.imgdiv-class img")
            ?: selectFirst("div.postInner img")
            ?: selectFirst("img")

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

        // 1️⃣ iframe player
        doc.selectFirst("iframe[name=player_iframe]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { handlePlayer(it) }

        // 2️⃣ tabs (episodes / qualities under tabs)
        doc.select(".tabs-ul > li").forEach { li ->
            val onclick = li.attr("onclick")
            val tabUrl = Regex("""location\.href\s*=\s*['"]([^'"]+)""").find(onclick)?.groupValues?.get(1)
            if (!tabUrl.isNullOrEmpty()) {
                handlePlayer(if (tabUrl.startsWith("http")) tabUrl else mainUrl.trimEnd('/') + "/" + tabUrl.trimStart('/'))
            }
        }

        // 3️⃣ direct download links
        doc.select("div.downloadLinks a").forEach { a ->
            val url = a.absUrl("href")
            if (url.isNotBlank()) callback(newExtractorLink(name, "Download", url, ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                quality = Qualities.Unknown.value
            })
        }

        return true
    }

    private suspend fun extractVideoFromPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerHtml = app.get(
            playerUrl,
            referer = referer,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).text

        val handledLinks = mutableSetOf<String>()

        // 1️⃣ JS sources array (sources:[{file:"..."}])
        val regexSources = Regex("""sources\s*:\s*\[\s*\{[^\}]*file\s*:\s*['"]([^'"]+\.m3u8)['"]""")
        regexSources.findAll(playerHtml).forEach {
            val url = it.groupValues[1]
            if (handledLinks.add(url)) {
                callback(newExtractorLink(name, "$name HLS", url, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    quality = Qualities.Unknown.value
                })
            }
        }

        // 2️⃣ <source src="..."> tags
        val regexSourceTag = Regex("""<source[^>]+src=['"]([^'"]+\.m3u8)['"]""")
        regexSourceTag.findAll(playerHtml).forEach {
            val url = it.groupValues[1]
            if (handledLinks.add(url)) {
                callback(newExtractorLink(name, "$name HLS", url, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    quality = Qualities.Unknown.value
                })
            }
        }

        if (handledLinks.isEmpty()) {
            println("⚠️ No HLS links found in iframe: $playerUrl")
        }
    }
}
