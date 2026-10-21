package com.lagradost.cloudstream3.anime4up

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import org.json.JSONObject

class WitAnime : MainAPI() {
    override var name = "WitAnime"
    override var mainUrl = "https://witanime.site"
    override var lang = "ar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/browse" to "جميع الأنميات",
        "$mainUrl/anime-type/movie/" to "أفلام الأنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url).document
        val list = doc.select("a[href*='/anime/']").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = attr("abs:href").ifEmpty { selectFirst("a")?.attr("abs:href") } ?: return null
        if (!url.contains("/anime/")) return null

        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim() ?: selectFirst("h1, h2, h3")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null

        val posterUrl = img?.attr("data-src")?.ifEmpty { null }
            ?: img?.attr("src")
            ?: attr("data-image")

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/browse?search=$query").document
        return doc.select("a[href*='/anime/']").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().replace("WitAnime", "").trim()
        val poster = doc.selectFirst("img[alt*='$title'], div.aspect-\\[2\\/3\\] img, img.object-cover")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst("p.leading-relaxed, div.text-neutral-300, meta[property='og:description']")?.text()?.trim()

        val episodes = doc.select("a[href*='/watch/']").mapNotNull { aTag ->
            val href = aTag.attr("abs:href")
            val text = aTag.text().trim()
            if (!text.contains("الحلقة") && !href.matches(Regex(""".*/watch/.+/\d+.*"""))) return@mapNotNull null

            val epNum = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = text.ifEmpty { "الحلقة $epNum" }
                this.episode = epNum
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val sourcesUrl = "${data.trimEnd('/')}/sources"

        val headers = mapOf(
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json",
            "Referer" to data
        )

        try {
            val res = app.post(sourcesUrl, headers = headers)
            val json = JSONObject(res.text)
            
            // 1. Players
            val players = json.optJSONObject("players")
            if (players != null) {
                val keys = players.keys()
                while (keys.hasNext()) {
                    val q = keys.next()
                    val arr = players.optJSONArray(q) ?: continue
                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val token = sObj.optString("token")
                        if (token.isEmpty()) continue

                        try {
                            app.post("$mainUrl/watch/stream-source/$token", headers = headers)
                            val gateRes = app.get(
                                "$mainUrl/watch/stream-gate/$token",
                                headers = mapOf("Referer" to ""),
                                allowRedirects = false
                            )
                            val loc = gateRes.headers["Location"]
                            if (!loc.isNullOrEmpty()) {
                                loadExtractor(loc, data, subtitleCallback, callback)
                            } else if (gateRes.text.contains("iframe")) {
                                val gateDoc = org.jsoup.Jsoup.parse(gateRes.text)
                                gateDoc.select("iframe[src]").forEach { ifr ->
                                    val src = ifr.attr("src")
                                    if (src.isNotEmpty()) {
                                        loadExtractor(src, data, subtitleCallback, callback)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }

            // 2. Downloads (Mediafire, Mega)
            val downloads = json.optJSONObject("downloads")
            if (downloads != null) {
                val dKeys = downloads.keys()
                while (dKeys.hasNext()) {
                    val q = dKeys.next()
                    val dArr = downloads.optJSONArray(q) ?: continue
                    for (i in 0 until dArr.length()) {
                        val dObj = dArr.optJSONObject(i) ?: continue
                        val token = dObj.optString("token")
                        if (token.isEmpty()) continue

                        try {
                            app.post("$mainUrl/watch/download-source/$token", headers = headers)
                            val dlRes = app.get(
                                "$mainUrl/watch/download-gate/$token",
                                headers = mapOf("Referer" to ""),
                                allowRedirects = false
                            )
                            val loc = dlRes.headers["Location"]
                            if (!loc.isNullOrEmpty()) {
                                loadExtractor(loc, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }
}

open class Anime4upProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://w1.anime4up.rest"
    override var name = "Anime4up"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()

        // 1. Fetch Latest Episodes
        try {
            val episodesDoc = app.get("$mainUrl/episode/").document
            val episodesList = episodesDoc.select("div.anime-card-container").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinctBy { it.url }
            if (episodesList.isNotEmpty()) {
                homeList.add(HomePageList("أخر الحلقات المضافة", episodesList, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }

        // 2. Fetch Latest Anime from Home8
        try {
            val homeDoc = app.get("$mainUrl/home8/").document
            val animesList = homeDoc.select("div.anime-card-container").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinctBy { it.url }
            if (animesList.isNotEmpty()) {
                homeList.add(HomePageList("أخر الأنميات المضافة", animesList, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }

        // 3. Fetch Anime Movies from /anime-type/movie-3/
        try {
            val moviesDoc = app.get("$mainUrl/anime-type/movie-3/").document
            val moviesList = moviesDoc.select("div.anime-card-container").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinctBy { it.url }
            if (moviesList.isNotEmpty()) {
                homeList.add(HomePageList("أفلام الأنمي", moviesList, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }

        return newHomePageResponse(homeList, hasNext = false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = selectFirst("a[href*='/anime/']") ?: selectFirst("div.hover a") ?: selectFirst("a") ?: return null
        val rawUrl = aTag.attr("abs:href").ifEmpty { aTag.attr("href") }
        if (rawUrl.isEmpty()) return null

        val imgElement = selectFirst("img")
        val posterUrl = imgElement?.attr("data-image")?.ifEmpty { null }
            ?: imgElement?.attr("data-original")?.ifEmpty { null }
            ?: imgElement?.attr("data-src")?.ifEmpty { null }
            ?: imgElement?.attr("src")

        val title = imgElement?.attr("alt")?.ifEmpty { null }
            ?: selectFirst(".anime-card-title, h3, a")?.text()?.trim()
            ?: return null

        val typeText = select("div.anime-card-type > a").text()
        val type =
            if (typeText.contains("TV|Special".toRegex())) TvType.Anime
            else if (typeText.contains("OVA|ONA".toRegex())) TvType.OVA
            else if (typeText.contains("Movie")) TvType.AnimeMovie
            else TvType.Others

        return newAnimeSearchResponse(title, rawUrl, type) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?search_param=animes&s=$query").document
            .select("div.row.display-flex > div, div.anime-card-container").mapNotNull {
                it.toSearchResponse()
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        var targetUrl = url

        // If URL is an episode link, find the series page to show full episode list
        if (url.contains("/episode/")) {
            val animeLink = doc.select("a[href*='/anime/']").firstOrNull { it.text().contains("جميع حلقات") || it.text().contains("حلقات") }?.attr("abs:href")
                ?: doc.select("a[href*='/anime/']").firstOrNull()?.attr("abs:href")
            if (!animeLink.isNullOrEmpty()) {
                targetUrl = animeLink
                doc = app.get(targetUrl).document
            }
        }

        val title = doc.select("h1.anime-details-title, h1").text().trim()
        val poster = doc.select("div.anime-thumbnail img, .anime-details img").attr("src")
        val description = doc.select("p.anime-story, .anime-story").text().trim()
        val year = doc.select("div.anime-info:contains(بداية العرض)").text().replace("بداية العرض: ", "").toIntOrNull()

        val typeText = doc.select(".anime-info:contains(النوع) a").text()
        val type =
            if (typeText.contains("TV|Special".toRegex())) TvType.Anime
            else if (typeText.contains("OVA|ONA".toRegex())) TvType.OVA
            else if (typeText.contains("Movie")) TvType.AnimeMovie
            else TvType.Others

        val malId = doc.select("a.anime-mal").attr("href").replace(".*e\\/|\\/.*".toRegex(), "").toIntOrNull()

        val episodes = doc.select("div.ep_num, #episodesList .themexblock, .episodes-list a, .DivEpisodesList a, div.anime-card-poster").mapNotNull { el ->
            val aTag = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val episodeUrl = aTag.attr("abs:href")
            if (episodeUrl.isEmpty()) return@mapNotNull null

            val text = el.text().trim()
            val episodeNum = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-(\d+)(?:-[^/]+)?/?$""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()

            val style = aTag.attr("style")
            val posterUrl = style.substringAfter("url(\"").substringBefore("\")")
                .ifEmpty { style.substringAfter("url('").substringBefore("')") }
                .ifEmpty { el.selectFirst("img")?.attr("src") }

            newEpisode(episodeUrl) {
                this.name = text.ifEmpty { "الحلقة $episodeNum" }
                this.episode = episodeNum
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, targetUrl, type) {
            this.apiName = this@Anime4upProvider.name
            addMalId(malId)
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(if (title.contains("مدبلج")) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        val servers = mutableListOf<String>()
        doc.select("ul#episode-servers li[data-watch], ul#episode-servers li, [data-watch], [data-embed]").forEach { li ->
            val link = li.attr("data-watch").ifBlank { li.attr("data-embed") }.ifBlank { li.selectFirst("a")?.attr("href") ?: "" }
            if (link.isNotBlank() && link.startsWith("http")) {
                servers.add(link)
            }
        }

        doc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("http")) {
                servers.add(src)
            }
        }

        for (link in servers.distinct()) {
            try {
                val embedRes = app.get(link, referer = data).text
                val streamUrl = Regex("""(?:let|const|var)\s+streamUrl\s*=\s*["']([^"']+)["']""").find(embedRes)?.groupValues?.get(1)
                if (!streamUrl.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            "Anime4up",
                            "Anime4up HLS",
                            streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = link
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } catch (e: Exception) {
                // ignore
            }

            loadExtractor(link, data, subtitleCallback, callback)

            if (link.contains("megamax.me/iframe/")) {
                val id = link.substringAfter("/iframe/").substringBefore("\"").substringBefore("'")
                if (id.isNotEmpty()) {
                    loadExtractor("https://megamax.me/e/$id", data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
