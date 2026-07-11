package com.lagradost.cloudstream3.ifilmtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class IfilmtvProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://ar.ifilmtv.ir"
    override var name = "iFilmTV"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun String.toWesternDigits(): String {
        val arabicDigits = mapOf(
            '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
            '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
        )
        return this.map { arabicDigits[it] ?: it }.joinToString("")
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

    override val mainPage = mainPageOf(
        "$mainUrl/Series?order=1&page=1" to "مسلسلات",
        "$mainUrl/Film?order=1&page=1" to "أفلام",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        if (request.data.contains("/Series")) {
            val url = if (page == 1) request.data else "${request.data}&page=$page"
            val doc = app.get(url, timeout = 120).document
            doc.select("div.All-Film-body.Serial > div.content-all-film > a.inner-panel")
                .mapNotNull { it.toSearchResponse() }
                .also { items.addAll(it) }
        } else if (request.data.contains("/Film")) {
            val url = if (page == 1) request.data else "${request.data}&page=$page"
            val doc = app.get(url, timeout = 120).document
            doc.select("div.All-Film-body > div.content-all-film > a.inner-panel")
                .mapNotNull { it.toSearchResponse() }
                .also { items.addAll(it) }
        } else if (request.data.contains("/Home/PageingItem")) {
            val url = "${request.data}&page=$page"
            val resp = app.get(url).text
            val arr = JSONArray(resp)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val id = item.optInt("Id", 0)
                val title = item.optString("Title", "")
                val image = item.optString("ImageAddress_M", "")
                if (id > 0 && title.isNotBlank()) {
                    val searchUrl = "$mainUrl/Program/Content/$id"
                    val response = newMovieSearchResponse(title, searchUrl, TvType.TvSeries) {
                        this.posterUrl = fixUrl(image)
                    }
                    items.add(response)
                }
            }
        }
        return newHomePageResponse(request.name, items)
    }

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
        val doc = app.get(url, timeout = 120).document
        val html = doc.toString()
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h5")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val synopsis = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim() ?: ""
        val actors = doc.select("div.Film-Artists-panel > a").mapNotNull {
            val name = it.selectFirst("span")?.text()
            val image = it.selectFirst("img.fill-box")?.attr("src")?.let { fixUrl(it) }
            val role = it.selectFirst("h6")?.text()
            if (name.isNullOrBlank()) return@mapNotNull null
            val actor = Actor(name, image)
            ActorData(actor = actor, roleString = role)
        }.toList()

        if (url.contains("/Film/Content/")) {
            val videoUrl = fixUrlNull(doc.selectFirst("meta[property='og:video']")?.attr("content"))
                ?: doc.selectFirst("video source")?.attr("src")?.let { fixUrl(it) }
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
            for (ep in inter downTo 1) {
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
                val ajaxUrl = "$mainUrl/Home/PageingAttachmentItem?id=$contentId&page=$page&size=5"
                val resp = app.get(ajaxUrl).text
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
                            val videoUrl = "https://fa.ifilmtv.ir/$videoAddr"
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
                val mp4Url = "https://preview.presstv.ir/ifilm/$langE$idSerial/$ep.mp4"
                val hlsUrl = "https://vod.ifilmtv.ir/hls/$langE$idSerial/,$ep,${ep}_320,.mp4.urlset/master.m3u8"
                callback(newExtractorLink(name, "MP4", mp4Url, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = mainUrl
                })
                callback(newExtractorLink(name, "HLS", hlsUrl, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P720.value
                    this.referer = mainUrl
                })
            }
            return true
        }
        loadExtractor(data, data, subtitleCallback, callback)
        return true
    }
}