package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class AnimeBlkomProvider : MainAPI() {
    override var mainUrl = "https://animeblkom.net"
    override var name = "AnimeBlkom"
    override var lang = "ar"
    override val hasMainPage = true
    private val cfInterceptor = CloudflareKiller()

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )
	
    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("a").attr("href") ?: return null
        val name = select("div.name").text()
        val poster = select("div.poster img, div.image img").let { it.attr("data-original").ifEmpty { it.attr("data-src") } }
        val year = select("div[title=\"سنة الانتاج\"]").text().toIntOrNull()
        val episodesNumber = select("div[title=\"عدد الحلقات\"]").text().toIntOrNull()
        val tvType = select("div[title=\"النوع\"]").text().let { if(it.contains("فيلم|خاصة".toRegex())) TvType.AnimeMovie else if(it.contains("أوفا|أونا".toRegex())) TvType.OVA else TvType.Anime }
        
        if (name.isBlank() || url.isBlank()) return null
        
        return newAnimeSearchResponse(
            name,
            url,
            tvType,
        ) {
            addDubStatus(false, episodesNumber)
            this.year = year
            this.posterUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl" to "Recently Added", 
        "$mainUrl/anime-list?sort_by=rate&page=" to "Most rated",
        "$mainUrl/anime-list?sort_by=created_at&page=" to "Recently added List",
        "$mainUrl/anime-list?states=finished&page=" to "Completed"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + (if (request.data.contains("?")) "" else page), interceptor = cfInterceptor).document
        val list = doc.select("div.recent-episode, div.item.episode, div.content-inner").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list) as HomePageResponse
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ","+")
        return app.get("$mainUrl/search?query=$q").document.select("div.content.ratable").mapNotNull {
            it.toSearchResponse()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.select("span h1").text().replace("\\(.*".toRegex(),"")
        val poster = mainUrl + doc.select("div.poster img").attr("data-original")
        val description = doc.select(".story p").text()
        val genre = doc.select("p.genres a").map {
            it.text()
        }
        val year = doc.select(".info-table div:contains(تاريخ الانتاج) span.info").text().split("-")[0].toIntOrNull()
        val status = doc.select(".info-table div:contains(حالة الأنمي) span.info").text().let { if(it.contains("مستمر")) ShowStatus.Ongoing else ShowStatus.Completed }
        val nativeName = doc.select("span[title=\"الاسم باليابانية\"]").text().replace(".*:".toRegex(),"")
        val type = doc.select("h1 small").text().let {
            if (it.contains("movie")) TvType.AnimeMovie
            else if (it.contains("ova|ona".toRegex())) TvType.OVA
            else TvType.Anime
        }

        val mallink = doc.select("a.blue.cta:contains(المزيد من المعلومات)").attr("href")
        val malId = if(mallink.contains("myanimelist")) mallink.replace(".*e\\/|\\/.*".toRegex(),"").toIntOrNull() else null
        
        val episodes = arrayListOf<Episode>()
        val episodeElements = doc.select(".episode-link")
        if(episodeElements.isEmpty()) {
            episodes.add(newEpisode(url) {
                name = "Watch"
            })
        } else {
            episodeElements.forEach {
                val a = it.select("a")
                episodes.add(newEpisode(mainUrl + a.attr("href")) {
                    name = a.text().replace(":"," ")
                    episode = a.select("span").not(".pull-left").last()?.text()?.toIntOrNull()
                })
            }
        }
        return newAnimeLoadResponse(title, url, type) {
            addMalId(malId)
            japName = nativeName
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(if(title.contains("مدبلج")) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            plot = description
            tags = genre
            showStatus = status
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("div.item a[data-src]").forEach {
            it.attr("data-src").let { url ->
                if(url.startsWith("https://animetitans.net/")) {
                    val iframe = app.get(url).document
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "Animetitans " + it.text(),
                            iframe.select("script").last()?.data()?.substringAfter("source: \"")?.substringBefore("\"").toString(),
                            ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.Unknown.value
                            referer = this@AnimeBlkomProvider.mainUrl
                        }
                    )
                } else if(it.text() == "Blkom") {
                    val iframe = app.get(url).document
                    iframe.select("source").forEach { source ->
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                it.text(),
                                source.attr("src"),
                                ExtractorLinkType.VIDEO
                            ) {
                                quality = source.attr("res").toIntOrNull() ?: Qualities.Unknown.value
                                referer = this@AnimeBlkomProvider.mainUrl
                            }
                        )
                    }
                } else {
                    var sourceUrl = url
                    if(it.text().contains("Google")) sourceUrl = "http://gdriveplayer.to/embed2.php?link=$url"
                    loadExtractor(sourceUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        doc.select(".panel .panel-body a").forEach {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    it.attr("title") + " " + it.select("small").text() + " Download Source",
                    it.attr("href"),
                    ExtractorLinkType.VIDEO
                ) {
                    quality = it.text().replace("p.*| ".toRegex(),"").toIntOrNull() ?: Qualities.Unknown.value
                    referer = this@AnimeBlkomProvider.mainUrl
                }
            )
        }
        return true
    }
}