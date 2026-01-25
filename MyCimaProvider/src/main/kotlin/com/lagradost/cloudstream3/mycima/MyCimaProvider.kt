package com.lagradost.cloudstream3.mycima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.fasterxml.jackson.module.kotlin.readValue

class MyCimaProvider : MainAPI() {
    private inline fun <reified T> parseJson(text: String): T {
        return mapper.readValue(text)
    }

    override var lang = "ar"
    override var mainUrl = "https://mycima.rip"
    private val alternativeUrl = "https://wecima.click"
    override var name = "MyCima"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private fun String.getImageURL(): String? {
        return this.replace("--im(age|g):url\\(|\\);".toRegex(), "")
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.Thumb--GridItem a")
        val posterUrl = select("span.BG--GridItem").let { it.attr("data-lazy-style").ifEmpty { it.attr("style") } }
            ?.getImageURL()
        val year = select("div.GridItem span.year")?.text()
        val title = select("div.Thumb--GridItem strong").text()
            .replace("$year", "")
            .replace("مشاهدة|فيلم|مسلسل|مترجم".toRegex(), "")
            .replace("( نسخة مدبلجة )", " ( نسخة مدبلجة ) ")
        // If you need to differentiate use the url.
        return newMovieSearchResponse(
            title,
            url.attr("href"),
            if(url.attr("title").contains("فيلم")) TvType.Movie else TvType.TvSeries,
        ) {
            this.posterUrl = posterUrl
            this.year = year?.getIntFromText()
        }
    }
    override val mainPage = mainPageOf(
            "$mainUrl/movies/page/" to "Movies",
            "$mainUrl/episodes/page/" to "Latest Episodes",
            "$mainUrl/seriestv/page/" to "Series",
            "$mainUrl/movies/top/page/" to "Top Movies",
            "$mainUrl/seriestv/top/page/" to "Top Series",
        )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val list = doc.select("div.Grid--WecimaPosts div.GridItem").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "%20")
        val result = arrayListOf<SearchResponse>()
        listOf(
            "$mainUrl/search/$q",
            "$mainUrl/search/$q/list/series/",
            "$mainUrl/search/$q/list/anime/"
        ).forEach { url ->
            val d = app.get(url).document
            d.select("div.Grid--WecimaPosts div.GridItem").mapNotNull {
                if (it.text().contains("اعلان")) return@mapNotNull null
                it.toSearchResponse()?.let { it1 -> result.add(it1) }
            }
        }
        return result.distinct().sortedBy { it.name }
    }

    data class MoreEPS(
        val output: String
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = doc.select("ol li:nth-child(3)").text().contains("افلام")
        val posterUrl =
            doc.select("wecima.separated--top")?.attr("data-lazy-style")?.getImageURL()
                ?.ifEmpty { doc.select("meta[itemprop=\"thumbnailUrl\"]")?.attr("content") }
                ?.ifEmpty { doc.select("wecima.separated--top")?.attr("style")?.getImageURL() }
        val year =
            doc.select("div.Title--Content--Single-begin h1 a.unline")?.text()?.getIntFromText()
        val title = doc.select("div.Title--Content--Single-begin h1").text()
            .replace("($year)", "")
            .replace("مشاهدة|فيلم|مسلسل|مترجم|انمي".toRegex(), "")
        // A bit iffy to parse twice like this, but it'll do.
        val duration =
            doc.select("ul.Terms--Content--Single-begin li").firstOrNull {
                it.text().contains("المدة")
            }?.text()?.getIntFromText()

        val synopsis = doc.select("div.StoryMovieContent").text()
            .ifEmpty { doc.select("div.PostItemContent").text() }

        val tags = doc.select("li:nth-child(3) > p > a").map { it.text() }

        val actors = doc.select("div.List--Teamwork > ul.Inner--List--Teamwork > li")?.mapNotNull {
            val name = it?.selectFirst("a > div.ActorName > span")?.text() ?: return@mapNotNull null
            val image = it.attr("style")
                ?.getImageURL()
                ?: return@mapNotNull null
            ActorData(actor = Actor(name, image))
        }
        val recommendations =
            doc.select("div.Grid--WecimaPosts div.GridItem")?.mapNotNull { element ->
                element.toSearchResponse()
            }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                this.actors = actors
            }
        } else {
            val episodes = ArrayList<Episode>()
            val seasons = doc.select("div.List--Seasons--Episodes a").not(".selected").map {
                it.attr("href")
            }
            val moreButton = doc.select("div.MoreEpisodes--Button")
            val season =
                doc.select("div.List--Seasons--Episodes a.selected").text().getIntFromText()
            doc.select("div.Seasons--Episodes div.Episodes--Seasons--Episodes a")
                .forEach {
                    episodes.add(
                        newEpisode(it.attr("href")) {
                            this.name = it.text()
                            this.season = season
                            this.episode = it.text().getIntFromText()
                        }
                    )
                }
            if (moreButton.isNotEmpty()) {
                val n = doc.select("div.Seasons--Episodes div.Episodes--Seasons--Episodes a").size
                val totals =
                    doc.select("div.Episodes--Seasons--Episodes a").first()!!.text().getIntFromText()
                val mEPS = arrayListOf(
                    n,
                    n + 40,
                    n + 80,
                    n + 120,
                    n + 160,
                    n + 200,
                    n + 240,
                    n + 280,
                    n + 320,
                    n + 360,
                    n + 400,
                    n + 440,
                    n + 480,
                    n + 520,
                    n + 660,
                    n + 700,
                    n + 740,
                    n + 780,
                    n + 820,
                    n + 860,
                    n + 900,
                    n + 940,
                    n + 980,
                    n + 1020,
                    n + 1060,
                    n + 1100,
                    n + 1140,
                    n + 1180,
                    n + 1220,
                    totals
                )
                mEPS.forEach { it ->
                    if (it != null) {
                        if (it > totals!!) return@forEach
                        val ajaxURL =
                            "$mainUrl/AjaxCenter/MoreEpisodes/${moreButton.attr("data-term")}/$it"
                        val jsonResponse = app.get(ajaxURL)
                        val json = parseJson<MoreEPS>(jsonResponse.text)
                        val document = Jsoup.parse(json.output?.replace("""\""", ""))
                        document.select("a").forEach {
                            episodes.add(
                                newEpisode(it.attr("href")) {
                                    this.name = it.text()
                                    this.season = season
                                    this.episode = it.text().getIntFromText()
                                }
                            )
                        }
                    }
                }
            }
            if (seasons.isNotEmpty()) {
                seasons.forEach { surl ->
                    if (surl.contains("%d9%85%d8%af%d8%a8%d9%84%d8%ac")) return@forEach
                    val seasonsite = app.get(surl).document
                    val fmoreButton = seasonsite.select("div.MoreEpisodes--Button")
                    val fseason = seasonsite.select("div.List--Seasons--Episodes a.selected").text()
                        .getIntFromText() ?: 1
                    seasonsite.select("div.Seasons--Episodes div.Episodes--Seasons--Episodes a")
                        .forEach {
                            episodes.add(
                                newEpisode(it.attr("href")) {
                                    this.name = it.text()
                                    this.season = fseason
                                    this.episode = it.text().getIntFromText()
                                }
                            )
                        }
                    if (fmoreButton.isNotEmpty()) {
                        val n =
                            seasonsite.select("div.Seasons--Episodes div.Episodes--Seasons--Episodes a").size
                        val totals =
                            seasonsite.select("div.Episodes--Seasons--Episodes a").first()!!.text()
                                .getIntFromText()
                        val mEPS = arrayListOf(
                            n,
                            n + 40,
                            n + 80,
                            n + 120,
                            n + 160,
                            n + 200,
                            n + 240,
                            n + 280,
                            n + 320,
                            n + 360,
                            n + 400,
                            n + 440,
                            n + 480,
                            n + 520,
                            n + 660,
                            n + 700,
                            n + 740,
                            n + 780,
                            n + 820,
                            n + 860,
                            n + 900,
                            n + 940,
                            n + 980,
                            n + 1020,
                            n + 1060,
                            n + 1100,
                            n + 1140,
                            n + 1180,
                            n + 1220,
                            totals
                        )
                        mEPS.forEach { it ->
                            if (it != null) {
                                if (it > totals!!) return@forEach
                                val ajaxURL =
                                    "$mainUrl/AjaxCenter/MoreEpisodes/${fmoreButton.attr("data-term")}/$it"
                                val jsonResponse = app.get(ajaxURL)
                                val json = parseJson<MoreEPS>(jsonResponse.text)
                                val document = Jsoup.parse(json.output?.replace("""\""", ""))
                                document.select("a").forEach {
                                    episodes.add(
                                        newEpisode(it.attr("href")) {
                                            this.name = it.text()
                                            this.season = fseason
                                            this.episode = it.text().getIntFromText()
                                        }
                                    )
                                }
                            }
                        }
                    } else return@forEach
                }
            }
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinct().sortedBy { it.episode }) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations
                this.actors = actors
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
        doc.select(".WatchServersList > ul > li").forEach {
            val url = it.select("btn").attr("data-url")
            loadExtractor(url, data, subtitleCallback, callback)
        }
        doc.select("ul.List--Download--Wecima--Single:nth-child(2) li").forEach {
                it.select("a").forEach { linkElement ->
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            linkElement.attr("href"),
                            ExtractorLinkType.VIDEO
                        ) {
                            referer = this@MyCimaProvider.mainUrl
                            quality = linkElement.select("resolution").text().getIntFromText() ?: 0
                        }
                    )
                }
            }
        return true
    }
}
