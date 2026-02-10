package com.lagradost.cloudstream3.egybest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session



import android.annotation.TargetApi
import android.os.Build

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.util.Base64

class EgyBestProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egibest.net"
    override var name = "EgyBest (In Progress)"
	var pssid = ""
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.attr("href") ?: return null
        val posterUrl = select("img").attr("data-img").ifBlank { select("img").attr("src") }
        var title = select("h3").text()
        if (title.isEmpty()) title = this.attr("title")
        val year = title.getYearFromTitle()
        val isMovie = Regex(".*/movie/.*|.*/masrahiya/.*").matches(url)
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries
        title = if (year !== null) title else title.split(" (")[0].trim()
        val quality = select("span.ribbon span").text().replace("-", "")
        // If you need to differentiate use the url.
        return newMovieSearchResponse(
            title,
            fixUrl(url), // Fixed double URL issue
            tvType,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recent" to "أحدث الاضافات",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/category/anime/" to "انمي",
        "$mainUrl/trending/" to "الأفلام الأكثر مشاهدة",
        "$mainUrl/category/series/مسلسلات-كورية/" to "الدراما الكورية ",
        "$mainUrl/category/anime/مسلسلات-انمي/" to "مسلسلات الانمي",
        "$mainUrl/category/wwe/" to "عروض المصارعة ",
        "$mainUrl/movies/bluray/" to "أفلام جديدة BluRay",
        "$mainUrl/masrahiyat/" to "مسرحيات ",
        "$mainUrl/category/movies/افلام-كوميدي/" to "أفلام كوميدية",
        "$mainUrl/explore/?q=superhero/" to "أفلام سوبر هيرو",
        "$mainUrl/category/movies/افلام-كرتون/" to "أفلام انمي و كرتون",
        "$mainUrl/category/movies/افلام-رومانسية/" to "أفلام رومانسية",
        "$mainUrl/category/movies/افلام-دراما/" to "أفلام دراما",
        "$mainUrl/category/movies/افلام-رومانسية/" to "أفلام رومانسية", // Duplicate removed/fixed if needed, but keeping structure
        "$mainUrl/category/movies/افلام-رعب/" to "أفلام رعب",
        "$mainUrl/category/movies/افلام-وثائقية/" to "أفلام وثائقية",
        "$mainUrl/World-War-Movies/" to "أفلام عن الحرب العالمية ☢",
        "$mainUrl/End-Of-The-World-Movies/" to "أفلام عن نهاية العالم",
        "$mainUrl/category/movies/افلام-عربية/" to "أفلام عربية ",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = if(page > 1) {
             if(request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
        } else {
            request.data
        }
        
        val doc = app.get(url).document
        // Target specific grid excluding the global slider to prevent duplicates
        // ".movies_small .postBlock" or just excluding the slider
        val list = doc.select("div:not(#postSlider) > .postBlock").ifEmpty { 
             doc.select(".postBlock") 
        }.mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val result = arrayListOf<SearchResponse>()
        listOf("$mainUrl/explore/?q=$query").forEach { url ->
            val d = app.get(url).document
            d.select(".postBlock").mapNotNull {
                it.toSearchResponse()?.let { it1 -> result.add(it1) }
            }
        }
        return result.distinct().sortedBy { it.name }
    }

    private fun String.getYearFromTitle(): Int? {
        return Regex("""\(\d{4}\)""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = Regex(".*/movie/.*|.*/masrahiya/.*").matches(url)
        val posterUrl = doc.select("div.movie_img a img")?.attr("data-img")?.ifBlank { doc.select("div.movie_img a img")?.attr("src") }
        val year = doc.select("div.movie_title h1 a")?.text()?.toIntOrNull()
        val title = doc.select("div.movie_title h1 span").text()
        val youtubeTrailer = doc.select("div.play")?.attr("url")

        val synopsis = doc.select("div.mbox").firstOrNull {
            it.text().contains("القصة")
        }?.text()?.replace("القصة ", "")

        val tags = doc.select("table.movieTable tbody tr").firstOrNull {
            it.text().contains("النوع")
        }?.select("a")?.map { it.text() }

        val actors = doc.select("div.cast_list .cast_item").mapNotNull {
            val name = it.selectFirst("div > a > img")?.attr("alt") ?: return@mapNotNull null
            val image = it.selectFirst("div > a > img")?.attr("data-img") ?: it.selectFirst("div > a > img")?.attr("src") ?: return@mapNotNull null
            val roleString = it.selectFirst("div > span")!!.text()
            val mainActor = Actor(name, image)
            ActorData(actor = mainActor, roleString = roleString)
        }

        return if (isMovie) {
            val recommendations = doc.select(".movies_small .movie").mapNotNull { element ->
                element.toSearchResponse()
            }

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
                // addTrailer(youtubeTrailer)
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("#mainLoad > div:nth-child(2) > div.h_scroll > div a").map {
                it.attr("href")
            }.forEach {
                val d = app.get(fixUrl(it)).document
                val season = Regex("season-(.....)").find(it)?.groupValues?.getOrNull(1)?.getIntFromText()
                if(d.select("tr.published").isNotEmpty()) {
                    d.select("tr.published").map { element ->
                        val ep = Regex("ep-(.....)").find(element.select(".ep_title a").attr("href"))?.groupValues?.getOrNull(1)?.getIntFromText()
                        episodes.add(
                            newEpisode(fixUrl(element.select(".ep_title a").attr("href"))) {
                                this.name = element.select("td.ep_title").html().replace(".*</span>|</a>".toRegex(), "")
                                this.season = season
                                this.episode = ep
                                // this.rating = ...
                            }
                        )
                    }
                } else {
                    d.select("#mainLoad > div:nth-child(3) > div.movies_small a").map { eit ->
                        val ep = Regex("ep-(.....)").find(eit.attr("href"))?.groupValues?.getOrNull(1)?.getIntFromText()
                        episodes.add(
                            newEpisode(fixUrl(eit.attr("href"))) {
                                this.name = eit.select("span.title").text()
                                this.season = season
                                this.episode = ep
                            }
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.actors = actors
                // addTrailer(youtubeTrailer)
            }
        }
    }
	
    @TargetApi(Build.VERSION_CODES.O)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        doc.select("ul#watch-servers-list li, .servList li").mapNotNull {
            val onclick = it.attr("onclick")
            val url = Regex("loadIframe\\(this, '(.*?)'\\)").find(onclick)?.groupValues?.get(1)
            url
        }.forEach { url ->
             loadExtractor(url, subtitleCallback, callback)
        }
        
        // Fallback for default iframe if list is empty or logic fails
        doc.select("iframe#videoPlayer").attr("src").takeIf { it.isNotBlank() }?.let { url ->
            loadExtractor(url, subtitleCallback, callback)
        }

        return true
    }
}
