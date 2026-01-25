package com.lagradost.cloudstream3.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.util.Base64

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
        "$mainUrl/category/مسلسلات-كوريه/" to "Korean Series",
        "$mainUrl/category/مسلسلات-مصريه/" to "Egyptian Series",
        "$mainUrl/category/مسلسلات-هندية/" to "Indian Series",
        "$mainUrl/category/cartoon-series/" to "Cartoon",
        "$mainUrl/category/مسلسلات-رمضان/ramadan-series-2025/" to "Ramadan Series 2025",
        "$mainUrl/category/مسلسلات-رمضان/ramadan-series-2024/" to "Ramadan Series 2024",
        "$mainUrl/category/مسلسلات-رمضان/ramadan-series-2023/" to "Ramadan Series 2023",
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
        
        // Robust detection
        val episodesElements = doc.select(".episodes__list a, .seasons__list a")
        // If it has episodes, it's a series. If not, treat as movie (safe fallback for singles).
        val isMovie = episodesElements.isEmpty()

        val posterUrl = doc.selectFirst(".images__loader img")?.attr("data-src") 
            ?: doc.selectFirst(".poster__single img")?.attr("src")
            ?: doc.selectFirst(".poster__single img")?.attr("data-src")
            ?: doc.selectFirst(".poster img")?.attr("data-src")
            ?: doc.selectFirst(".single__poster img")?.attr("data-src")
            ?: doc.selectFirst("img[data-src]")?.attr("data-src")
            ?: doc.selectFirst(".images__loader img")?.attr("src")

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
            val episodes = episodesElements.mapNotNull { it ->
                val episodeName = it.text()
                val episodeNumber = it.selectFirst("b")?.text()?.toIntOrNull() 
                    ?: episodeName.getIntFromText()
                
                newEpisode(it.attr("href")) {
                    this.name = episodeName
                    this.episode = episodeNumber
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
        // If watchUrl is empty or same as current, try to parse current page for servers
        // Some pages might be the watch page itself
        val watchDoc = if (watchUrl.isNotEmpty() && watchUrl != data) {
            app.get(watchUrl, headers = mapOf("Referer" to data)).document
        } else {
            doc
        }

        val servers = watchDoc.select(".servers__list li")
        
        servers.map { 
            val link = it.attr("data-link")
            val name = it.text()
            
            // Extract the actual URL - format can be /play.php?url=BASE64 or /play/?id=BASE64 or direct
            val finalUrl = when {
                link.contains("url=") -> {
                    val encoded = link.substringAfter("url=")
                    try {
                        String(Base64.getDecoder().decode(encoded))
                    } catch (e: Exception) { link }
                }
                link.contains("id=") -> {
                    val encoded = link.substringAfter("id=")
                    try {
                        String(Base64.getDecoder().decode(encoded))
                    } catch (e: Exception) { link }
                }
                else -> link
            }

            // If parsed URL is valid, load it
            if (finalUrl.isNotEmpty()) {
                 if (finalUrl.contains("savefiles.com")) {
                     // Direct SaveFiles handling logic
                     val sourcesFound = mutableListOf<Boolean>()
                     try {
                         val doc = app.get(finalUrl).document
                         val source = doc.select("source").attr("src")
                         val sourcesFound = mutableListOf<Boolean>() // Re-init not needed but cleaner scope
                         
                         if (source.isNotEmpty()) {
                             callback.invoke(
                                 newExtractorLink(
                                     this.name,
                                     "$name (SaveFiles)",
                                     source,
                                     ExtractorLinkType.VIDEO
                                 ) {
                                     this.referer = finalUrl
                                 }
                             )
                             sourcesFound.add(true)
                         }
                         
                         // Fallback for SaveFiles
                         val codeMatch = Regex("/e/(\\w+)").find(finalUrl)
                         if (codeMatch != null) {
                             val code = codeMatch.groupValues[1]
                             val baseUrl = finalUrl.substringBefore("/e/")
                             mapOf(
                                 "download_o" to "1080p", "download_x" to "720p",
                                 "download_h" to "480p", "download_n" to "360p"
                             ).forEach { (param, quality) ->
                                  callback.invoke(
                                      newExtractorLink(
                                          this.name,
                                          "$name (SaveFiles $quality)",
                                          "$baseUrl/$code.html?$param",
                                          ExtractorLinkType.VIDEO
                                      ) { this.referer = finalUrl }
                                  )
                                  sourcesFound.add(true)
                             }
                         }
                     } catch(e: Exception) {}
                     
                     if (sourcesFound.isEmpty()) {
                         loadExtractor(finalUrl, data, subtitleCallback, callback)
                     }
                 } else if (finalUrl.startsWith("/") || finalUrl.contains("asd.homes")) {
                     // Internal link presumably
                     val urlToLoad = if (finalUrl.startsWith("/")) mainUrl + finalUrl else finalUrl
                     val doc = app.get(urlToLoad, headers = mapOf("Referer" to watchUrl)).document
                     val sourceElement = doc.select("source")
                     if (sourceElement.hasAttr("src")) {
                         callback.invoke(
                             newExtractorLink(
                                 this.name,
                                 "$name (Internal)",
                                 sourceElement.attr("src"),
                                 if (!sourceElement.attr("type").contains("mp4")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                             ) {
                                 this.quality = Qualities.Unknown.value
                                 this.referer = data
                             }
                         )
                     } else {
                         val iframeSrc = doc.select("iframe").attr("src")
                         if (iframeSrc.isNotEmpty()) {
                             loadExtractor(iframeSrc, data, subtitleCallback, callback)
                         }
                     }
                 } else {
                     loadExtractor(finalUrl, data, subtitleCallback, callback)
                 }
            }
        }

        // Download Links Logic
        val downloadUrl = doc.select(".download__btn").attr("href")
        if (downloadUrl.isNotEmpty()) {
            try {
                val downloadDoc = app.get(downloadUrl, headers = mapOf("Referer" to data)).document
                val downloadLinks = downloadDoc.select(".downloads__links__list li a")
                downloadLinks.map { 
                    val link = it.attr("href")
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
               // Ignore download errors
            }
        }
        
        return true
    }
}