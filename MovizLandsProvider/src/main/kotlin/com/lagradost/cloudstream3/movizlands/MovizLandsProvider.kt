package com.lagradost.cloudstream3.movizlands

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element


import android.annotation.SuppressLint

class MovizLandsProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://en.movizlands.com"
    override var name = "MovizLands"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun String.getFullSize(): String? {
        return this.replace("""-\d+x\d+""".toRegex(),"")
    }
    
    private fun String.cleanTitle(): String {
		val prefix = setOf("مشاهدة فيلم","مشاهدة وتحميل فيلم","تحميل","فيلم","انمي","إنمي","مسلسل","برنامج")
		val suffix = setOf("مدبلج للعربية","اون لاين","مترجم")
		this.let{ clean ->
            var aa = clean
				prefix.forEach{ pre ->
            	aa = if (aa.contains(pre))	aa.replace(pre,"") 	else	aa	}
                var bb = aa
				suffix.mapNotNull{ suf ->
            	bb = if (bb.contains(suf))	bb.replace(suf,"")	else	bb	}
        	return bb
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = select("h3, .BlockTitle").text()
        val img = select("img").first()
        val posterUrl = img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("abs:src")
        val year = select(".WatchTime, .InfoEndBlock li:last-child, .RestInformation li:last-child").text()?.getIntFromText()
        var quality = select(".Quality, .RestInformation li:first-child").text()?.replace(" |-|1080p|720p".toRegex(), "")?.replace("BluRay","BLURAY")
        val tvtype = if(title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        
        val url = select("a").first()?.attr("abs:href") ?: return null

        return newMovieSearchResponse(
            title.cleanTitle(),
            url,
            tvtype,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
    }
    override val mainPage = mainPageOf(
        "$mainUrl/home13/" to "الرئيسية",
        "$mainUrl/category/افلام-اجنبي/" to "افلام اجنبي",
        "$mainUrl/last/" to "أضيف حديثا",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات اسيوية",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات اجنبي",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات انمي",
        "$mainUrl/category/افلام-اسيوي/" to "افلام اسيوي",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document   
        val list = doc.select("div.Small--Box, .BlockItem").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ".toRegex(), "%20")
        val result = arrayListOf<SearchResponse>()

        val rlist = setOf(
            "$mainUrl/?s=$q",
        )
        rlist.forEach{ docs ->
            val d = app.get(docs).document
            d.select("div.Small--Box").mapNotNull {
                it.toSearchResponse()?.let {
            it1 -> result.add(it1)
            }
            }
        }
    return result
    }

private val seasonPatterns = arrayOf(
    Pair("الموسم العاشر|الموسم 10", 10),
    Pair("الموسم الحادي عشر|الموسم 11", 11),
    Pair("الموسم الثاني عشر|الموسم 12", 12),
    Pair("الموسم الثالث عشر|الموسم 13", 13),
    Pair("الموسم الرابع عشر|الموسم 14", 14),
    Pair("الموسم الخامس عشر|الموسم 15", 15),
    Pair("الموسم السادس عشر|الموسم 16", 16),
    Pair("الموسم السابع عشر|الموسم 17", 17),
    Pair("الموسم الثامن عشر|الموسم 18", 18),
    Pair("الموسم التاسع عشر|الموسم 19", 19),
    Pair("الموسم العشرون|الموسم 20", 20),
    Pair("الموسم الاول|الموسم 1", 1),
    Pair("الموسم الثاني|الموسم 2", 2),
    Pair("الموسم الثالث|الموسم 3", 3),
    Pair("الموسم الرابع|الموسم 4", 4),
    Pair("الموسم الخامس|الموسم 5", 5),
    Pair("الموسم السادس|الموسم 6", 6),
    Pair("الموسم السابع|الموسم 7", 7),
    Pair("الموسم الثامن|الموسم 8", 8),
    Pair("الموسم التاسع|الموسم 9", 9),
)

private fun getSeasonFromString(sName: String): Int {
    return seasonPatterns.firstOrNull{(pattern, seasonNum) -> sName.contains(pattern.toRegex()) }?.second ?: 1
}
        
    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        val sdetails = doc.select(".SingleDetails")
        var posterUrl = sdetails.select("img")?.attr("data-src")?.getFullSize()
        val year = sdetails.select("li:has(.fa-clock) a").text()?.getIntFromText()
        var title = doc.select("h1, h2.postTitle").text()
        val isMovie = title.contains("عرض|فيلم".toRegex())
        val synopsis = doc.select("section.story").text()
        val trailer = doc.select("div.InnerTrailer iframe").attr("data-src")
        var tags = sdetails.select("li:has(.fa-film) a").map { it.text() }
        val recommendations = doc.select(".related--Posts .Small--Box").mapNotNull {
            it.toSearchResponse()
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, doc.select(".BTNSDownWatch a.watch").attr("abs:href")) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            val episodeElements = doc.select(".EpisodesList .EpisodeItem")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { element ->
                    val epUrl = element.select("a").attr("abs:href")
                    if (epUrl.isNotEmpty() && !element.text().contains("Full")) {
                        episodes.add(
                            newEpisode(epUrl) {
                                this.episode = element.select("em").text().getIntFromText()
                                this.name = element.text()
                            }
                        )
                    }
                }
            } else if (doc.select(".allepcont a").isNotEmpty()) {
                doc.select(".allepcont a").forEach { element ->
                    val epUrl = element.attr("abs:href")
                    if(epUrl.isNotEmpty()) {
                        episodes.add(
                            newEpisode(epUrl) {
                                this.episode = element.select(".epnum").text().getIntFromText() ?: element.attr("title").getIntFromText()
                                this.name = element.attr("title").ifEmpty { element.text() }
                                this.posterUrl = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                            }
                        )
                    }
                }
            } else {
                doc.select(".BlockItem").forEach { element ->
                    val blockUrl = element.select("a").attr("abs:href")
                    if (blockUrl.isNotEmpty()) {
                         val blockName = element.select(".BlockTitle, .title").text()
                         episodes.add(
                             newEpisode(blockUrl) {
                                 this.name = blockName
                             }
                         )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
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
        
        val watchUrl = doc.select(".BTNSDownWatch a.watch").attr("abs:href").takeIf { it.isNotEmpty() } 
                      ?: doc.select(".WatchBar a, .WatchBar button, a[href*='/watch/']").attr("abs:href").takeIf { it.isNotEmpty() }
                      ?: if (data.endsWith("/watch/")) data else "${data.trimEnd('/')}/watch/"

        val watchDoc = if (watchUrl == data) doc else app.get(watchUrl).document
        
        watchDoc.select("ul#watch li").forEach { li ->
            val serverUrl = li.attr("data-watch")
            val serverName = li.text().trim()
            if (serverUrl.startsWith("http")) {
                loadExtractor(serverUrl, data, subtitleCallback) { link ->
                    callback(
ExtractorLink(
                        source = link.source,
                        name = "$serverName ${link.name}",
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        isM3u8 = link.isM3u8,
                        headers = link.headers,
                        type = link.type
                    )
                    )
                }
            }
        }
        return true
    }
}