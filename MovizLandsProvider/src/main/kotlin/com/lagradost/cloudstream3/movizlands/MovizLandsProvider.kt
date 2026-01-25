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
        val url = select("div.Small--Box")
        val title = url.select("h3").text()
        val img = url.select("img")
        val posterUrl = img?.attr("src")?.ifEmpty { img?.attr("data-src") }
        val year = url.select(".WatchTime").text()?.getIntFromText()
        var quality = url.select(".Quality").text()?.replace(" |-|1080p|720p".toRegex(), "")?.replace("BluRay","BLURAY")
        val tvtype = if(title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(
            title.cleanTitle(),
            url.select("a").attr("href"),
            tvtype,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
    }
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "أضيف حديثا",
        "$mainUrl/category/movies/page/" to "أفلام",
        "$mainUrl/series/page/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document   
        val list = doc.select("div.Small--Box").mapNotNull { element ->
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
        var title = doc.select("h2.postTitle").text()
        val isMovie = title.contains("عرض|فيلم".toRegex())
        val synopsis = doc.select("section.story").text()
        val trailer = doc.select("div.InnerTrailer iframe").attr("data-src")
        var tags = sdetails.select("li:has(.fa-film) a").map { it.text() }
	val recommendations = doc.select(".BlocksUI#LoadFilter div.Small--Box").mapNotNull { element ->
                element.toSearchResponse()
        }


        return if (isMovie) {
        newMovieLoadResponse(
                title.cleanTitle().replace("$year",""),
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = synopsis
		this.recommendations = recommendations
		// addTrailer(trailer)
            }
    }   else    {
            val episodes = ArrayList<Episode>()
	    val episodesItem = doc.select(".EpisodesList").isNotEmpty()
	    val fBlock = doc.select(".BlockItem")?.first()
	    val img = fBlock?.select("img:last-of-type")

	    if(episodesItem){
		 title = doc.select(".SeriesSingle .ButtonsFilter.WidthAuto span").text()
		 doc.select(".EpisodesList .EpisodeItem").forEach { element ->
			 if(!element.text().contains("Full")){
				 episodes.add(
					 newEpisode(element.select("a").attr("href")) {
                                            this.episode = element.select("em").text().getIntFromText()
					 }
				 )
			 }
		 }
		newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                	this.posterUrl = posterUrl
                	this.year = year
                	this.tags = tags
                	this.plot = synopsis
			this.recommendations = recommendations
			// addTrailer(trailer)
               }
	    }else{	    
            posterUrl = img?.attr("src")?.ifEmpty { img?.attr("data-src") }
	    tags = fBlock?.select(".RestInformation span")!!.mapNotNull { t ->
                t.text()
            }
	    title = doc.select(".PageTitle .H1Title").text().cleanTitle()
            if(doc.select(".BlockItem a").attr("href").contains("/series/")){//seasons
                doc.select(".BlockItem").forEach { seas ->
                    val pageIt = seas.select("a").attr("href")
                    val Sedoc = app.get(pageIt).document
                    val pagEl = Sedoc.select(".pagination > div > ul > li").isNotEmpty()
                    if(pagEl) {
                            Sedoc.select(".pagination > div > ul > li:nth-child(n):not(:last-child) a").forEach {
                                val epidoc = app.get(it.attr("href")).document
                                    epidoc.select(".BlockItem").forEach { element ->
                                    episodes.add(
                                        newEpisode(element.select("a").attr("href")) {
                                            this.name = element.select(".BlockTitle").text()
                                            this.season = getSeasonFromString(element.select(".BlockTitle").text())
                                            this.episode = element.select(".EPSNumber").text().getIntFromText()
					                        this.posterUrl = element.select("img:last-of-type").attr("src")?.ifEmpty { img?.attr("data-src") }
                                        }
                                    )
                                }
                            }
                        }else{
                        Sedoc.select(".BlockItem").forEach { el ->
                        episodes.add(
                            newEpisode(el.select("a").attr("href")) {
                                    this.name = el.select(".BlockTitle").text()
                                    this.season = getSeasonFromString(el.select(".BlockTitle").text())
                                    this.episode = el.select(".EPSNumber").text().getIntFromText()
				                    this.posterUrl = el.select("img:last-of-type").attr("src")?.ifEmpty { img?.attr("data-src") }
                                }
                            )
                        }
                    }
                }

                        }   else    {//episodes
                    val pagEl = doc.select(".pagination > div > ul > li.active > a").isNotEmpty()
                    val pagSt = if(pagEl) true else false
                    if(pagSt){
                        doc.select(".pagination > div > ul > li:nth-child(n):not(:last-child) a").forEach { eppages ->
                            val it = eppages.attr("href")
                            val epidoc = app.get(it).document
                                epidoc.select(".BlockItem").forEach { element ->
                                episodes.add(
                                    newEpisode(element.select("a").attr("href")) {
                                        this.name = element.select(".BlockTitle").text()
                                        this.season = getSeasonFromString(element.select(".BlockTitle").text())
                                        this.episode = element.select(".EPSNumber").text().getIntFromText()    
					                    this.posterUrl = element.select("img:last-of-type").attr("src")?.ifEmpty { img?.attr("data-src") }
                                    }
                                )
                            }
                        }
                    }else{   
                    doc.select(".BlockItem").forEach { el ->
                    episodes.add(
                        newEpisode(el.select("a").attr("href")) {
                                this.name = el.select(".BlockTitle").text()
                                this.season = getSeasonFromString(el.select(".BlockTitle").text())
                                this.episode = el.select(".EPSNumber").text().getIntFromText()
				                this.posterUrl = el.select("img:last-of-type").attr("src")?.ifEmpty { img?.attr("data-src") }
                            }
                        )
                    }
                }
            }
                                
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
		    this.posterUrl = posterUrl?.getFullSize()
		    this.tags = tags
               }
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
	doc.select("code[id*='Embed'] iframe,.DownloadsList a").forEach {
                            val sourceUrl = it.attr("data-srcout").ifEmpty { it.attr("href") }
                            loadExtractor(sourceUrl, data, subtitleCallback, callback)
            }
	return true
    }
}