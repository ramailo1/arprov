package com.lagradost.cloudstream3.mycima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class MyCimaProvider : MainAPI() {

    // ---------- AUTO DOMAIN POOL ----------
    private val domainPool = listOf(
        "https://mycima.rip",
        "https://mycima.bond",
        "https://mycima.sbs",
        "https://mycima.live"
    )

    private var activeDomain: String = domainPool.first()

    override var mainUrl = activeDomain
    override var name = "MyCima"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to activeDomain
        )

    // ---------- AUTO DOMAIN DETECTOR ----------
    private suspend fun getWorkingDomain(): String? {
        for (domain in domainPool) {
            val working = runCatching {
                app.get(domain, timeout = 10).isSuccessful
            }.getOrDefault(false)

            if (working) {
                activeDomain = domain
                mainUrl = domain
                return domain
            }
        }
        return null
    }

    private suspend fun safeGet(url: String): org.jsoup.nodes.Document? {
        val base = getWorkingDomain() ?: return null

        return runCatching {
            app.get(
                if (url.startsWith("http")) url else base + url,
                headers = headers
            ).document
        }.getOrNull()
    }

    // ---------- MAIN PAGE ----------
    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/movies/" to "أفلام",
        "/series/" to "مسلسلات",
        "/episodes/" to "الحلقات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = safeGet(url)
            ?: return newHomePageResponse(request.name, emptyList())

        val items = document.select("div#MainFiltar a.GridItem")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // ---------- SEARCH ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = safeGet("/?s=$query") ?: return emptyList()

        return document.select("div#MainFiltar a.GridItem")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (href.isBlank()) return null

        val title = selectFirst("strong")?.text()?.trim()
            ?: attr("title").trim()
            ?: return null

        val poster = selectFirst("img")?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }

        val type = when {
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ---------- LOAD ----------
    override suspend fun load(url: String): LoadResponse? {

        val document = safeGet(url) ?: return null
        val fixedUrl = fixUrl(url)

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")?.let { fixUrl(it) }

        val year = document.selectFirst("a[href*=release-year]")
            ?.text()?.toIntOrNull()

        val plot = document.selectFirst(".story p, .AsideContext")
            ?.text()?.trim()

        val genres = document.select("a[href*=/genre/]")
            .map { it.text() }

        val actors = document.select("a[href*=/actor/]")
            .map { Actor(it.text(), null) }

        val duration = document.selectFirst("span:contains(دقيقة)")
            ?.text()?.replace("[^0-9]".toRegex(), "")
            ?.toIntOrNull()

        val type = when {
            fixedUrl.contains("/series/") -> TvType.TvSeries
            fixedUrl.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                addActors(actors)
            }
        }

        val episodes = document.select(".EpisodesList a[href*=/episode/]")
            .distinctBy { it.attr("href") }
            .map { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epNum = ep.text()
                    .replace("[^0-9]".toRegex(), "")
                    .toIntOrNull()

                newEpisode(epUrl) {
                    this.name = ep.text().trim()
                    this.episode = epNum
                }
            }

        return newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            this.duration = duration
            addActors(actors)
        }
    }

    // ---------- SMART SERVER RANKING ----------
    private val serverPriority = listOf(
        "voe",
        "filemoon",
        "uqload",
        "streamhg",
        "dood",
        "okru"
    )

    private fun getServerScore(name: String): Int {
        val lower = name.lowercase()
        return serverPriority.indexOfFirst { lower.contains(it) }
            .let { if (it == -1) 999 else it }
    }

    private fun getQuality(text: String?): Int {
        return when {
            text?.contains("1080") == true -> Qualities.P1080.value
            text?.contains("720") == true -> Qualities.P720.value
            text?.contains("480") == true -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // ---------- LOAD LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = safeGet(data) ?: return false

        val servers = document.select(".WatchServersList li[data-id]")
            .map {
                val name = it.text()
                val id = it.attr("data-id")
                Triple(name, id, getServerScore(name))
            }
            .filter { it.second.isNotBlank() }
            .sortedBy { it.third } // smart priority ranking

        val usedLinks = mutableSetOf<String>()

        for ((serverName, serverId, _) in servers) {

            val ajaxUrl =
                "$activeDomain/wp-admin/admin-ajax.php?action=get_player&server=$serverId"

            val response = runCatching {
                app.get(ajaxUrl, headers = headers).document
            }.getOrNull() ?: continue

            val iframe = response.selectFirst("iframe")?.attr("src")
                ?: continue

            val finalUrl = fixUrl(iframe)

            if (usedLinks.contains(finalUrl)) continue
            usedLinks.add(finalUrl)

            val quality = getQuality(serverName)

            loadExtractor(
                finalUrl,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
