package com.lagradost.cloudstream3.ifilmtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.json.JSONArray
import java.net.URLEncoder

abstract class IfilmtvBase(
    override var name: String,
    override var lang: String,
    override var mainUrl: String,
    private val seriesLabel: String,
    private val moviesLabel: String,
) : MainAPI() {
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Live)

    companion object {
        val ALL_LIVE_STREAMS = listOf(
            Triple("iFilmTV العربية", "ar", "https://live.presstv.ir/hls/ifilmar.m3u8"),
            Triple("iFilmTV English", "en", "https://live.presstv.ir/hls/ifilmen.m3u8"),
            Triple("iFilmTV فارسی", "fa", "https://live.presstv.ir/hls/ifilmfa.m3u8"),
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = attr("href") ?: return null
        val title = selectFirst("h3, h6")?.text() ?: return null
        val posterUrl = selectFirst("img.fill-box")?.attr("src")
            ?: selectFirst("img")?.attr("src")
            ?: return null
        val tvType = when {
            url.contains("/Series/Content/") || url.contains("/series/Content/") -> TvType.TvSeries
            url.contains("/Film/Content/") -> TvType.Movie
            url.contains("/Program/Content/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, fixUrl(url), tvType) {
            this.posterUrl = fixUrl(posterUrl)
        }
    }

    private fun liveItem(title: String, langCode: String): SearchResponse {
        return newAnimeSearchResponse(title, "$mainUrl/Home/Live/$langCode", TvType.Live) {
            this.posterUrl = fixUrlNull("$mainUrl/img/i.png")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()

        when {
            request.data.contains("/Home/Live") -> {
                if (page == 1) {
                    ALL_LIVE_STREAMS.forEach { (title, langCode, _) ->
                        items.add(liveItem(title, langCode))
                    }
                }
            }
            request.data.contains("/Series") -> {
                val url = "${request.data}?order=1&page=$page"
                val doc = app.get(url, timeout = 120).document
                doc.select("div.All-Film-body.Serial > div.content-all-film > a.inner-panel")
                    .mapNotNull { it.toSearchResponse() }
                    .also { items.addAll(it) }
            }
            request.data.contains("/Film") -> {
                val url = "${request.data}?order=1&page=$page"
                val doc = app.get(url, timeout = 120).document
                doc.select("div.All-Film-body > div.content-all-film > a.inner-panel")
                    .mapNotNull { it.toSearchResponse() }
                    .also { items.addAll(it) }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/Home/Live" to "Live / البث الحي",
        "$mainUrl/Series" to seriesLabel,
        "$mainUrl/Film" to moviesLabel,
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/Home/Search?searchstring=$encoded"
        val resp = app.get(url).text
        val arr = JSONArray(resp)
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optInt("Id", 0)
            val title = item.optString("Title", "")
            val categoryId = item.optInt("CategoryId", 0)
            val image = item.optString("ImageAddress_S", "")
            if (id == 0 || title.isBlank()) continue
            val (contentUrl, tvType) = when (categoryId) {
                3 -> "$mainUrl/Series/Content/$id" to TvType.TvSeries
                5 -> "$mainUrl/Film/Content/$id" to TvType.Movie
                7 -> "$mainUrl/Program/Content/$id" to TvType.TvSeries
                else -> "$mainUrl/Series/Content/$id" to TvType.TvSeries
            }
            val response = newMovieSearchResponse(title, contentUrl, tvType) {
                this.posterUrl = fixUrl(image)
            }
            results.add(response)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val liveMatch = Regex("""Home/Live/(\w+)""").find(url)
        if (liveMatch != null) {
            val langCode = liveMatch.groupValues[1]
            val liveInfo = ALL_LIVE_STREAMS.firstOrNull { it.second == langCode } ?: ALL_LIVE_STREAMS[0]
            return newMovieLoadResponse(liveInfo.first, url, TvType.Live, liveInfo.third) {
                this.posterUrl = fixUrlNull("$mainUrl/img/i.png")
                this.plot = "iFilmTV Live - البث الحي"
            }
        }

        val doc = app.get(url, timeout = 120).document
        val html = doc.toString()
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h5")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val synopsis = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim() ?: ""
        val actors = doc.select("div.Film-Artists-panel > a").mapNotNull {
            val actorName = it.selectFirst("span")?.text()
            val image = it.selectFirst("img.fill-box")?.attr("src")?.let { fixUrl(it) }
            val role = it.selectFirst("h6")?.text()
            if (actorName.isNullOrBlank()) return@mapNotNull null
            val actor = Actor(actorName, image)
            ActorData(actor = actor, roleString = role)
        }.toList()

        if (url.contains("/Film/Content/")) {
            val videoUrl = doc.selectFirst("video source")?.attr("src")?.let { fixUrl(it) }
                ?: doc.selectFirst("meta[property='og:video']")?.attr("content")?.let {
                    if (it.startsWith("http")) it else fixUrl(it)
                }
                ?: return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster; this.plot = synopsis; this.actors = actors
                }
            return newMovieLoadResponse(title, url, TvType.Movie, videoUrl) {
                this.posterUrl = poster
                this.plot = synopsis
                this.actors = actors
            }
        }

        val episodes = mutableListOf<Episode>()
        val contentId = Regex("Uid['\"]?\\s*['\"]?(\\d+)").find(html)?.groupValues?.get(1)
            ?: url.substringAfterLast("/")

        val extrafild = Regex("""extrafild\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1)
        val idSerial = Regex("ID_Serial\\s*=\\s*(\\d+)").find(html)?.groupValues?.get(1)
        val inter = Regex("inter_\\s*=\\s*(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val langE = Regex("""langE\s*=\s*"(\w+)"""").find(html)?.groupValues?.get(1) ?: "ar"

        if (extrafild != null && extrafild.isNotBlank() && idSerial != null && inter != null) {
            for (ep in 1..inter) {
                val epData = "ifilmtv_path_a|$idSerial|$ep|$langE"
                episodes.add(newEpisode(epData) {
                    this.name = "الحلقة $ep"
                    this.episode = ep
                })
            }
        } else {
            var page = 1
            var hasMore = true
            while (hasMore) {
                val ajaxUrl = "$mainUrl/Home/PageingAttachmentItem?id=$contentId&page=$page&size=10"
                val resp = app.get(ajaxUrl).text
                if (resp.isBlank() || resp == "[]") {
                    hasMore = false
                } else {
                    val arr = JSONArray(resp)
                    if (arr.length() == 0) {
                        hasMore = false
                    } else {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val epNum = item.optInt("Episode", 0)
                            val videoAddr = item.optString("VideoAddress", "")
                            val imgAddr = item.optString("ImageAddress_M", "")
                            if (videoAddr.isNotBlank()) {
                                val videoUrl = if (videoAddr.startsWith("http")) videoAddr else "https://video.ifilmtv.ir/ifilm/$videoAddr"
                                episodes.add(newEpisode(videoUrl) {
                                    this.name = "الحلقة $epNum"
                                    this.episode = epNum
                                    if (imgAddr.isNotBlank()) this.posterUrl = fixUrl(imgAddr)
                                })
                            }
                        }
                        page++
                    }
                }
            }
        }

        val sorted = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = poster
            this.plot = synopsis
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("ifilmtv_path_a|")) {
            val parts = data.split("|")
            if (parts.size >= 4) {
                val idSerial = parts[1]
                val ep = parts[2]
                val langE = parts[3]
                val videoLang = if (langE == "fa") "" else langE
                
                // HTTP MP4 stream (Primary, bypasses certificate revocation block)
                val mp4UrlHttp = "http://preview.presstv.ir/ifilm/$videoLang$idSerial/$ep.mp4"
                callback(newExtractorLink(name, "MP4 (HTTP)", mp4UrlHttp, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = mainUrl
                })

                // HTTPS MP4 stream (Fallback)
                val mp4UrlHttps = "https://preview.presstv.ir/ifilm/$videoLang$idSerial/$ep.mp4"
                callback(newExtractorLink(name, "MP4 (HTTPS)", mp4UrlHttps, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = mainUrl
                })

                // HTTP HLS stream (Primary, fixed layout with leading comma after slash)
                val hlsUrlHttp = "http://vod.ifilmtv.ir/hls/$videoLang$idSerial/,$ep,${ep}_320,.mp4.urlset/master.m3u8"
                callback(newExtractorLink(name, "HLS (HTTP)", hlsUrlHttp, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P1080.value
                    this.referer = mainUrl
                })

                // HTTPS HLS stream (Fallback, fixed layout with leading comma after slash)
                val hlsUrlHttps = "https://vod.ifilmtv.ir/hls/$videoLang$idSerial/,$ep,${ep}_320,.mp4.urlset/master.m3u8"
                callback(newExtractorLink(name, "HLS (HTTPS)", hlsUrlHttps, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P1080.value
                    this.referer = mainUrl
                })
            }
            return true
        }

        if (data.startsWith("http")) {
            val type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, data, type) {
                this.quality = Qualities.P720.value
                this.referer = mainUrl
            })
            return true
        }

        return false
    }
}
