package com.lagradost.cloudstream3.anime4up

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class WitAnime : Anime4upProvider() {
    override var name = "WitAnime"
    override var mainUrl = "https://witanime.com"
}

open class Anime4upProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://w1.anime4up.rest"
    override var name = "Anime4up"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()

        // 1. Fetch Latest Episodes from /episode/
        try {
            val episodesDoc = app.get("$mainUrl/episode/").document
            val episodesList = episodesDoc.select("div.anime-card-container").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinct()
            if (episodesList.isNotEmpty()) {
                homeList.add(HomePageList("أخر الحلقات المضافة", episodesList, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }

        // 2. Fetch Anime Movies from /anime-type/movie-3/
        try {
            val moviesDoc = app.get("$mainUrl/anime-type/movie-3/").document
            val moviesList = moviesDoc.select(".anime-card-container").mapNotNull { anime ->
                anime.toSearchResponse()
            }.distinct()
            if (moviesList.isNotEmpty()) {
                homeList.add(HomePageList("أفلام الأنمي", moviesList, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }

        return newHomePageResponse(homeList, hasNext = false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Case 1: Standard Anime Card (Movies, Slider, Episodes Page)
        val hoverDiv = selectFirst("div.hover")
        if (hoverDiv != null) {
            val url = hoverDiv.select("a").attr("href")
                .replace("-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-.*".toRegex(), "")
                .replace("episode", "anime")
            
            val imgElement = hoverDiv.select("img")
            // Prioritize data-image (slider) -> data-original -> data-src (lazy) -> src (fallback)
            val posterUrl = imgElement.attr("data-image").ifEmpty {
                imgElement.attr("data-original").ifEmpty {
                    imgElement.attr("data-src").ifEmpty { imgElement.attr("src") }
                }
            }
            
            val title = imgElement.attr("alt")
            val typeText = select("div.anime-card-type > a").text()
            val type =
                if (typeText.contains("TV|Special".toRegex())) TvType.Anime
                else if (typeText.contains("OVA|ONA".toRegex())) TvType.OVA
                else if (typeText.contains("Movie")) TvType.AnimeMovie
                else TvType.Others
            return newAnimeSearchResponse(title, url, type) { this.posterUrl = posterUrl }
        }

        // Case 2: Pinned Card (Fallback for main page mixed content)
        val pinnedCard = selectFirst("div.pinned-card") ?: if (this.hasClass("pinned-card")) this else null
        if (pinnedCard != null) {
             val linkElement = pinnedCard.selectFirst("a.image")
             if (linkElement != null) {
                 val url = linkElement.attr("href")
                    .replace("-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-.*".toRegex(), "")
                    .replace("episode", "anime")
                 val title = linkElement.attr("title")
                 val style = linkElement.attr("style")
                 val posterUrl = style.substringAfter("url(\"").substringBefore("\")")
                     .ifEmpty { style.substringAfter("url(").substringBefore(")") }
                     .ifEmpty { linkElement.attr("data-src") }
                 
                 return newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = posterUrl }
             }
        }
        
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?search_param=animes&s=$query").document
            .select("div.row.display-flex > div").mapNotNull {
                it.toSearchResponse()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.select("h1.anime-details-title").text()
        val poster = doc.select("div.anime-thumbnail img").attr("src")
        val description = doc.select("p.anime-story").text()
        val year = doc.select("div.anime-info:contains(بداية العرض)").text().replace("بداية العرض: ", "").toIntOrNull()

        val typeText = doc.select(".anime-info:contains(النوع) a").text()
        val type =
            if (typeText.contains("TV|Special".toRegex())) TvType.Anime
            else if (typeText.contains("OVA|ONA".toRegex())) TvType.OVA
            else if (typeText.contains("Movie")) TvType.AnimeMovie
            else TvType.Others

        val malId = doc.select("a.anime-mal").attr("href").replace(".*e\\/|\\/.*".toRegex(), "").toIntOrNull()

        // Updated Episodes Selector for new layout
        val episodes = doc.select("#episodesList .themexblock").mapNotNull {
            val aTag = it.selectFirst(".pinned-card > a") ?: return@mapNotNull null
            val episodeUrl = aTag.attr("href")
            
            // Image is in style="background-image: url('...')"
            val style = aTag.attr("style")
            val posterUrl = style.substringAfter("url(\"").substringBefore("\")")
                .ifEmpty { style.substringAfter("url('").substringBefore("')") }
            
            // Title/Number
            val infoDiv = it.selectFirst(".pinned-card .info")
            val episodeName = infoDiv?.selectFirst("h3")?.text() ?: "Episode"
            val episodeNumText = infoDiv?.selectFirst(".badge.light-soft span")?.text() 
            val episodeNum = episodeNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            newEpisode(episodeUrl) {
                this.name = episodeName
                this.episode = episodeNum
                this.posterUrl = posterUrl
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.apiName = this@Anime4upProvider.name
            addMalId(malId)
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(if (title.contains("مدبلج")) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // New Logic: Iterate over UL li elements with data-watch attribute
        doc.select("ul#episode-servers li[data-watch]").forEach { li ->
            val link = li.attr("data-watch")
            if (link.isNotBlank()) {
                loadExtractor(link, data, subtitleCallback, callback)
                
                // Add specific handling for megamax if needed, or if the user wants the /e/ variant for megamax
                if (link.contains("megamax.me/iframe/")) {
                     val id = link.substringAfter("/iframe/").substringBefore("\"").substringBefore("'")
                     if(id.isNotEmpty()) {
                         loadExtractor("https://megamax.me/e/$id", data, subtitleCallback, callback)
                     }
                }
            }
        }
        return true
    }
}
