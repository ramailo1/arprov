package com.lagradost.cloudstream3.fajershow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.net.URI
import java.util.*
import kotlin.collections.ArrayList
import com.fasterxml.jackson.module.kotlin.readValue

class FajerShowProvider : MainAPI() {
    private inline fun <reified T> parseJson(text: String): T {
        return mapper.readValue(text)
    }

    override var lang = "ar"
    override var mainUrl = "https://fajer.show"
    override var name = "FajerShow (Blocked by Cloudflare)"
    override val usesWebView = false
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    

    private fun Element.toSearchResponse(home: Boolean): SearchResponse? {
        val quality = select("span.quality").text().replace("-|p".toRegex(), "")
        if(home == true) {
            val titleElement = select("div.data h3 a")
            val posterUrl = select("img").attr("src")
            val tvType = if (titleElement.attr("href").contains("/movies/")) TvType.Movie else TvType.TvSeries
            // If you need to differentiate use the url.
            return newMovieSearchResponse(
                titleElement.text().replace(".*\\|".toRegex(), ""),
                titleElement.attr("href"),
                tvType,
            ) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        } else {
            val posterElement = select("img")
            val url = select("div.thumbnail > a").attr("href")
            return newMovieSearchResponse(
                posterElement.attr("alt"),
                url,
                if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries,
            ) {
                this.posterUrl = posterElement.attr("src")
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/genre/english-movies/page/" to "English Movies",
        "$mainUrl/genre/arabic-movies/page/" to "Arabic Movies",
        "$mainUrl/genre/turkish-movies/page/" to "Turkish Movies",
        "$mainUrl/genre/animation/page/" to "Animation Movies",
        "$mainUrl/genre/english-series/page/" to "English Series",
        "$mainUrl/genre/arabic-series/page/" to "Arabic Series",
        "$mainUrl/genre/turkish-series/page/" to "Turkish Series",
        "$mainUrl/genre/indian-series/page/" to "Indian Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        throw ErrorLoadingException("This source is currently blocked by Cloudflare protection.")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document
        return doc.select(".result-item > article").mapNotNull {
            it.toSearchResponse(false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        throw ErrorLoadingException("This source is currently blocked by Cloudflare protection.")
    }

    data class FajerLive (
        @JsonProperty("success"  ) var success  : Boolean?          = null,
        @JsonProperty("data"     ) var data     : ArrayList<Data>   = arrayListOf(),
    )
    data class Data (
        @JsonProperty("file"  ) var file  : String? = null,
        @JsonProperty("label" ) var label : String? = null,
        @JsonProperty("type"  ) var type  : String? = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("li.vid_source_option").not("[data-nume=\"trailer\"]").forEach { source ->
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to source.attr("data-post"),
                    "nume" to source.attr("data-nume"),
                    "type" to source.attr("data-type")
                )
            ).document.select("iframe").attr("src").let {
                val hostname = URI(it).host
                if (it.contains("show.alfajertv.com")) {
                    val url = URI(it)
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "AlfajerTV Palestine",
                            url.query.replace("&.*|source=".toRegex(), ""),
                            if (url.query.replace("&.*|source=".toRegex(), "").contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                             referer = data
                             quality = Qualities.Unknown.value
                        }
                    )
                    println("Palestine\n" + url.query.replace("&.*|source=".toRegex(), "") + "\n")
                }
                else if (it.contains("fajer.live")) {
                    val id = it.split("/v/").last().split('/')[0];
                    val response = parseJson<FajerLive>(app.post("https://$hostname/api/source/$id", data = mapOf("r" to "", "d" to hostname)).text)
                    for (it in response.data) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "FajerLive",
                                it.file ?: "",
                                if (it.type != "mp4") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                referer = data
                                quality = it.label?.getIntFromText() ?: Qualities.Unknown.value
                            }
                        )
                    }
                    println("FajerLive\n$response\n")
                }
                else loadExtractor(it, data, subtitleCallback, callback)
            }
        }
        return true
    }
}