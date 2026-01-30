package com.lagradost.cloudstream3.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class CimaNowProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow (In Progress)"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.attr("href") ?: ""
        val posterUrl = select("img").attr("data-src").ifEmpty { select("img").attr("src") }
        var title = select("img").attr("alt")
        if (title.isEmpty()) title = this.text()
        val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
        val tvType = if (url.contains("فيلم|مسرحية|حفلات".toRegex())) TvType.Movie else TvType.TvSeries
        val quality = select("li[aria-label=\"ribbon\"]").first()?.text()?.replace(" |-|1080|720".toRegex(), "")
        val dubEl = select("li[aria-label=\"ribbon\"]:nth-child(2)").isNotEmpty()
        val dubStatus = if(dubEl) select("li[aria-label=\"ribbon\"]:nth-child(2)").text().contains("مدبلج")
        else select("li[aria-label=\"ribbon\"]:nth-child(1)").text().contains("مدبلج")
        if(dubStatus) title = "$title (مدبلج)"
        return newMovieSearchResponse(
            "$title ${select("li[aria-label=\"ribbon\"]:contains(الموسم)").text()}",
            url,
            tvType,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {

        val doc = app.get("$mainUrl/home", headers = mapOf("user-agent" to "MONKE")).document
        val pages = doc.select("section").not("section:contains(أختر وجهتك المفضلة)").not("section:contains(تم اضافته حديثاً)").map {
            val name = it.select("span").html().replace("<em>.*| <i c.*".toRegex(), "")
            val list = it.select(".item a").mapNotNull {
                if(it.attr("href").contains("$mainUrl/category/|$mainUrl/الاكثر-مشاهدة/".toRegex())) return@mapNotNull null
                it.toSearchResponse()
            }
            HomePageList(name, list)
        }
        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val result = arrayListOf<SearchResponse>()
        val doc = app.get("$mainUrl/page/1/?s=$query").document
        val paginationElement = doc.select("ul[aria-label=\"pagination\"]")
        doc.select(".item a").map {
            val postUrl = it.attr("href")
            if(it.select("li[aria-label=\"episode\"]").isNotEmpty()) return@map
            if(postUrl.contains("$mainUrl/expired-download/|$mainUrl/افلام-اون-لاين/".toRegex())) return@map
            result.add(it.toSearchResponse()!!)
        }
        if(paginationElement.isNotEmpty()) {
            val max = paginationElement.select("li").not("li.active").last()?.text()?.toIntOrNull()
            if (max != null) {
                if(max > 5) return result.distinct().sortedBy { it.name }
                (2..max!!).toList().forEach {
                    app.get("$mainUrl/page/$it/?s=$query\"").document.select(".item a").map { element ->
                        val postUrl = element.attr("href")
                        if(element.select("li[aria-label=\"episode\"]").isNotEmpty()) return@map
                        if(postUrl.contains("$mainUrl/expired-download/|$mainUrl/افلام-اون-لاين/".toRegex())) return@map
                        result.add(element.toSearchResponse()!!)
                    }
                }
            }
        }
        return result.distinct().sortedBy { it.name }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val posterUrl = doc.select("body > script:nth-child(3)").html().replace(".*,\"image\":\"|\".*".toRegex(),"").ifEmpty { doc.select("meta[property=\"og:image\"]").attr("content") }
        val year = doc.select("article ul:nth-child(1) li a").last()?.text()?.toIntOrNull()
        val title = doc.select("title").text().split(" | ")[0]
        val isMovie = title.contains("فيلم|حفلات|مسرحية".toRegex())
        val youtubeTrailer = doc.select("iframe")?.attr("src")

        val synopsis = doc.select("ul#details li:contains(لمحة) p").text()

        val tags = doc.select("article ul").first()?.select("li")?.map { it.text() }

        val recommendations = doc.select("ul#related li").map { element ->
            newMovieSearchResponse(
                element.select("img:nth-child(2)").attr("alt"),
                element.select("a").attr("href"),
                TvType.Movie,
            ) {
                this.posterUrl = element.select("img:nth-child(2)").attr("src")
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                "$url/watching"
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                // addTrailer(youtubeTrailer)
            }
        } else {
            val episodes = doc.select("ul#eps li").map { episode ->
                newEpisode(episode.select("a").attr("href")+"/watching") {
                    this.name = episode.select("a img:nth-child(2)").attr("alt")
                    this.season = doc.select("span[aria-label=\"season-title\"]").html().replace("<p>.*|\n".toRegex(), "").getIntFromText()
                    this.episode = episode.select("a em").text().toIntOrNull()
                    this.posterUrl = episode.select("a img:nth-child(2)").attr("src")
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations
                // addTrailer(youtubeTrailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("ul#download [aria-label=\"quality\"]").forEach {
            val name = if(it.select("span").text().contains("فائق السرعة")) "Fast Servers" else "Servers"
            it.select("a").forEach { media ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        name,
                        media.attr("href"),
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = media.text().getIntFromText() ?: Qualities.Unknown.value
                        this.referer = this@CimaNowProvider.mainUrl
                    }
                )
            }
        }
        return true
    }
}