package com.lagradost.cloudstream3.shahidmbc

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay

class ShahidMBCProvider : MainAPI() {

    override var name = "Shahid"
    override var mainUrl = "https://shahid.mbc.net"
    override var lang = "ar"
    override val hasMainPage = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "ar,en-US;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ar/movies" to "أفلام شاهد",
        "$mainUrl/ar/series" to "مسلسلات شاهد",
        "$mainUrl/ar/anime" to "أنمي",
        "$mainUrl/ar/sports" to "رياضة"
    )

    // ---------------- UTIL ----------------

    private fun extractNextData(html: String): JsonNode? {
        val json = Regex(
            """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return null

        return mapper.readTree(json)
    }

    private fun parseRails(node: JsonNode): List<JsonNode> {
        return node["props"]?.get("pageProps")
            ?.get("initialState")
            ?.get("content")
            ?.get("rails")
            ?.toList()
            ?: emptyList()
    }

    // ---------------- MAIN PAGE ----------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val res = app.get(request.data, headers = headers)
        val json = extractNextData(res.text) ?: return newHomePageResponse(
            request.name,
            emptyList()
        )

        val items = mutableListOf<SearchResponse>()

        parseRails(json).forEach { rail ->
            rail["items"]?.forEach { item ->
                val title = item["title"]?.textValue() ?: return@forEach
                val url = item["url"]?.textValue() ?: return@forEach
                val poster = item["image"]?.get("path")?.textValue()

                val type = if (url.contains("/series/"))
                    TvType.TvSeries else TvType.Movie

                items.add(
                    newMovieSearchResponse(title, fixUrl(url), type) {
                        posterUrl = poster?.let { fixUrl(it) }
                    }
                )
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ---------------- SEARCH ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ar/search?term=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(url, headers = headers)
        val json = extractNextData(res.text) ?: return emptyList()

        val results = mutableListOf<SearchResponse>()

        parseRails(json).forEach { rail ->
            rail["items"]?.forEach { item ->
                val title = item["title"]?.textValue() ?: return@forEach
                val urlPath = item["url"]?.textValue() ?: return@forEach
                val poster = item["image"]?.get("path")?.textValue()

                results.add(
                    newMovieSearchResponse(title, fixUrl(urlPath), TvType.Movie) {
                        posterUrl = poster?.let { fixUrl(it) }
                    }
                )
            }
        }

        return results
    }

    // ---------------- LOAD ----------------

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = headers)
        val json = extractNextData(res.text) ?: return null

        val meta = json["props"]["pageProps"]["initialState"]["content"]["metadata"]
            ?: return null

        val title = meta["title"]?.textValue() ?: return null
        val poster = meta["image"]?.get("path")?.textValue()
        val plot = meta["description"]?.textValue()
        val year = meta["releaseYear"]?.intValue()

        val isSeries = url.contains("/series/")

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()

        json["props"]["pageProps"]["initialState"]["content"]["episodes"]
            ?.forEach { ep ->
                val epNum = ep["episodeNumber"]?.intValue()
                val epTitle = ep["title"]?.textValue() ?: "Episode $epNum"
                val epUrl = ep["url"]?.textValue() ?: return@forEach

                episodes.add(
                    newEpisode(fixUrl(epUrl)) {
                        name = epTitle
                        episode = epNum
                    }
                )
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster?.let { fixUrl(it) }
            this.plot = plot
            this.year = year
        }
    }

    // ---------------- STREAMS ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Shahid uses Widevine DRM which is not supported
        throw ErrorLoadingException("This content is DRM protected (Widevine) and cannot be played.")
    }
}
