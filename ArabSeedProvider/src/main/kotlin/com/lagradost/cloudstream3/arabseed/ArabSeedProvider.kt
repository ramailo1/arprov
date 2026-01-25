package com.lagradost.cloudstream3.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class ArabSeedProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://a.asd.homes"
    override var name = "ArabSeed"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = attr("title").ifEmpty { select("h3").text() }
        if (title.isEmpty()) return null
        
        val posterUrl = select("img.images__loader").let { 
            it.attr("data-src").ifEmpty { 
                it.attr("src") 
            }
        }
        
        // Determine type from URL or title
        val href = attr("href")
        val tvType = when {
            href.contains("/series/") || title.contains("مسلسل") -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(
            title,
            href,
            tvType,
        ) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/foreign-movies-10/" to "Foreign Movies",
        "$mainUrl/category/arabic-movies-10/" to "Arabic Movies",
        "$mainUrl/category/netfilx/افلام-netfilx/" to "Netflix Movies",
        "$mainUrl/category/asian-movies/" to "Asian Movies",
        "$mainUrl/category/turkish-movies/" to "Turkish Movies",
        "$mainUrl/category/افلام-مدبلجة-1/" to "Dubbed Movies",
        "$mainUrl/category/indian-movies/" to "Indian Movies",
        "$mainUrl/category/افلام-كلاسيكيه/" to "Classic Movies",
        "$mainUrl/category/foreign-series-3/" to "Foreign Series",
        "$mainUrl/category/arabic-series-8/" to "Arabic Series",
        "$mainUrl/category/netfilx/مسلسلات-netfilx-1/" to "Netflix Series",
        "$mainUrl/category/turkish-series-2/" to "Turkish Series",
        "$mainUrl/category/مسلسلات-مدبلجة/" to "Dubbed Series",
        "$mainUrl/category/مسلسلات-كورية/" to "Korean Series",
        "$mainUrl/category/مسلسلات-مصرية/" to "Egyptian Series",
        "$mainUrl/category/مسلسلات-هندية/" to "Indian Series",
        "$mainUrl/category/cartoon-series/" to "Cartoon",
        "$mainUrl/category/مسلسلات-رمضان/" to "Ramadan Series",
        "$mainUrl/category/wwe-shows-1/" to "WWE",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, timeout = 120).document

        val home = document.select("a.movie__block").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = arrayListOf<SearchResponse>()
        arrayListOf(
            mainUrl to "series",
            mainUrl to "movies"
        ).forEach { (url, type) ->
            val doc = app.post(
                "$url/wp-content/themes/Elshaikh2021/Ajaxat/SearchingTwo.php",
                data = mapOf("search" to query, "type" to type),
                referer = mainUrl
            ).document
            doc.select("a.movie__block").mapNotNull {
                it.toSearchResponse()?.let { it1 -> list.add(it1) }
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val title = doc.selectFirst(".post__name")?.text()?.cleanTitle() ?: doc.select("h1").text()
        val isMovie = url.contains("/movies/") || title.contains("فيلم")

        val posterUrl = doc.selectFirst(".images__loader img")?.attr("data-src") 
            ?: doc.selectFirst(".images__loader img")?.attr("src")
            ?: doc.selectFirst("img[data-src]")?.attr("data-src")

        val synopsis = doc.select(".single__contents").text()
            .replace("قصة العرض :", "")
            .replace("قصة العرض", "")
            .trim()

        val year = doc.select("a[href*='/release-year/']").text().getIntFromText()
        val tags = doc.select("a[href*='/genre/']").map { it.text() }

        val actors = doc.select("a.d__flex.align__items__center.gap__10[href*='/actor/']").mapNotNull {
            val name = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val image = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")
            val roleString = it.selectFirst("span")?.text()
            val mainActor = Actor(name, image)
            ActorData(actor = mainActor, roleString = roleString)
        }

        val recommendations = doc.select("a.movie__block").mapNotNull { element ->
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
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
                this.year = year
            }
        } else {
            val episodes = doc.select("a[href*='الحلقة']").mapNotNull { it ->
                val episodeName = it.text()
                val episodeNumber = it.selectFirst("b")?.text()?.toIntOrNull() 
                    ?: episodeName.getIntFromText()
                
                newEpisode(it.attr("href")) {
                    this.name = episodeName
                    this.episode = episodeNumber
                    // Season logic is hard for this site as it usually lists all episodes
                    // defaulting to 1 or 0
                    this.season = 1 
                }
            }.distinctBy { it.data }.sortedBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.actors = actors
                this.recommendations = recommendations
                this.year = year
            }
        }
    }

    private fun String.cleanTitle(): String {
        return this.replace(")", "")
            .replace("(", "")
            .replace("مشاهدة", "")
            .replace("تحميل", "")
            .replace("فيلم", "")
            .replace("مسلسل", "")
            .replace("مترجم", "")
            .replace("اون لاين", "")
            .trim()
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val watchUrl = doc.select(".watch__btn").attr("href")
        if (watchUrl.isEmpty()) return false
        
        val watchDoc = app.get(watchUrl, headers = mapOf("Referer" to data)).document

        val indexOperators = arrayListOf<Int>()
        val list: List<Element> = watchDoc.select("ul > li[data-link], ul > h3").mapIndexed { index, element ->
            if(element.`is`("h3")) {
                indexOperators.add(index)
                element
            } else element
        }
        var watchLinks: List<Pair<Int, List<Element>>>;
        if(indexOperators.isNotEmpty()) {
            watchLinks = indexOperators.mapIndexed { index, it ->
                var endIndex = list.size
                if (index != indexOperators.size - 1) endIndex = (indexOperators[index + 1]) - 1
                list[it].text().getIntFromText() as Int to list.subList(it + 1, endIndex) as List<Element>
            }
        } else {
            watchLinks = arrayListOf(0 to list)
        }
        for ((quality, links) in watchLinks) {
            for (it in links) {
                val iframeUrl = it.attr("data-link")
                println(iframeUrl)
                if(it.text().contains("عرب سيد")) {
                    val sourceElement = app.get(iframeUrl).document.select("source")
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "ArabSeed",
                            sourceElement.attr("src"),
                            if (!sourceElement.attr("type").contains("mp4")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = if(quality != 0) quality else it.text().replace(".*- ".toRegex(), "").replace("\\D".toRegex(),"").toIntOrNull() ?: Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                } else loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}