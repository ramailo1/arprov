package com.lagradost.cloudstream3.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.annotation.JsonProperty

import com.lagradost.nicehttp.Requests

import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Base64

class AnimeiatProvider : MainAPI() {
    private inline fun <reified T> parseJson(text: String): T {
        return mapper.readValue(text)
    }
    override var lang = "ar"
    override var mainUrl = "https://api.animeiat.co/v1"
    val pageUrl = "https://www.animeiat.tv"
    override var name = "Animeiat"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Anime, TvType.AnimeMovie)

    data class Data (
        @JsonProperty("anime_name"     ) var animeName     : String? = null,
        @JsonProperty("title"     ) var title     : String? = null,
        @JsonProperty("slug"           ) var slug          : String? = null,
        @JsonProperty("story"          ) var story         : String? = null,
        @JsonProperty("other_names"    ) var otherNames    : String? = null,
        @JsonProperty("total_episodes" ) var totalEpisodes : Int? = null,
        @JsonProperty("number" ) var number : Int? = null,
        @JsonProperty("age"            ) var age           : String? = null,
        @JsonProperty("type"           ) var type          : String? = null,
        @JsonProperty("status"         ) var status        : String? = null,
        @JsonProperty("poster_path"    ) var posterPath    : String? = null,
    )
    data class All (
        @JsonProperty("data"  ) var data  : ArrayList<Data> = arrayListOf(),
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime?featured=1&page=" to "حلقات مثبتة",
        "$mainUrl/anime?status=ongoing&page=" to "يعرض حاليا",
        "$mainUrl/anime?status=completed&page=" to "مكتمل",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = parseJson<All>(app.get(request.data + page).text)
        val list = json.data.map {
            newAnimeSearchResponse(
                it.animeName ?: it.title.toString(),
                mainUrl + "/anime/" + it.slug.toString().replace("-episode.*".toRegex(),""),
                if (it.type == "movie") TvType.AnimeMovie else if (it.type == "tv") TvType.Anime else TvType.OVA,
            ) {
                addDubStatus(false, it.totalEpisodes ?: it.number)
                this.otherName = it.otherNames?.split("\n")?.last()
                this.posterUrl = "https://api.animeiat.co/storage/" + it.posterPath
            }
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = parseJson<All>(app.get("$mainUrl/anime?q=$query").text)
        return json.data.map {
            newAnimeSearchResponse(
                it.animeName.toString(),
                mainUrl + "/anime/" + it.slug.toString(),
                if(it.type == "movie") TvType.AnimeMovie else if(it.type == "tv") TvType.Anime else TvType.OVA,
            ) {
                addDubStatus(false, it.totalEpisodes)
                this.otherName = it.otherNames?.split("\n")?.last()
                this.posterUrl = "https://api.animeiat.co/storage/" + it.posterPath
            }
        }

    }

    data class Year (
        @JsonProperty("name"        ) var name        : String? = null,

    )
    data class Genres (
        @JsonProperty("name"        ) var name        : String? = null,
    )
    data class LoadData (
        @JsonProperty("anime_name"     ) var animeName     : String? = null,
        @JsonProperty("slug"           ) var slug          : String? = null,
        @JsonProperty("story"          ) var story         : String? = null,
        @JsonProperty("other_names"    ) var otherNames    : String? = null,
        @JsonProperty("age"            ) var age           : String? = null,
        @JsonProperty("type"           ) var type          : String? = null,
        @JsonProperty("status"         ) var status        : String? = null,
        @JsonProperty("poster_path"    ) var posterPath    : String? = null,
        @JsonProperty("year"           ) var year          : Year?              = Year(),
        @JsonProperty("genres"         ) var genres        : ArrayList<Genres>  = arrayListOf(),

    )
    data class Load (

        @JsonProperty("data" ) var data : LoadData? = LoadData()

    )
    data class Meta (
        @JsonProperty("last_page"    ) var lastPage    : Int? = null,
    )
    data class EpisodeData (
        @JsonProperty("title"        ) var title       : String? = null,
        @JsonProperty("slug"         ) var slug        : String? = null,
        @JsonProperty("number"       ) var number      : Int? = null,
        @JsonProperty("video_id"     ) var videoId     : Int? = null,
        @JsonProperty("poster_path"  ) var posterPath  : String? = null,
    )
    data class Episodes (
        @JsonProperty("data"  ) var data  : ArrayList<EpisodeData> = arrayListOf(),
        @JsonProperty("meta"  ) var meta  : Meta = Meta()
    )
    override suspend fun load(url: String): LoadResponse {
        val loadSession = Requests()
        val request = loadSession.get(url.replace(pageUrl, mainUrl)).text
        val json = parseJson<Load>(request)
        val episodes = arrayListOf<Episode>()
        (1..parseJson<Episodes>(loadSession.get("$url/episodes").text).meta.lastPage!!).map { pageNumber ->
            parseJson<Episodes>(loadSession.get("$url/episodes?page=$pageNumber").text).data.map {
                episodes.add(
                    newEpisode("$pageUrl/watch/"+it.slug) {
                        this.name = it.title
                        this.episode = it.number
                        this.posterUrl = "https://api.animeiat.co/storage/" + it.posterPath
                    }
                )
            }
        }
        return newAnimeLoadResponse(json.data?.animeName.toString(), "$mainUrl/anime/"+json.data?.slug, if(json.data?.type == "movie") TvType.AnimeMovie else if(json.data?.type == "tv") TvType.Anime else TvType.OVA) {
            japName = json.data?.otherNames?.replace("\\n.*".toRegex(), "")
            engName = json.data?.animeName
            posterUrl = "https://api.animeiat.co/storage/" + json.data?.posterPath
            this.year = json.data?.year?.name?.toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodes)
            plot = json.data?.story
            tags = json.data?.genres?.map { it.name.toString() }
            this.showStatus = if(json.data?.status == "completed") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if(data.contains("-episode")) data else "$data-episode-1"
        val doc = app.get(url).document
        
        // Extract the video slug from the JSON data in the page
        val scriptData = doc.select("script#__NUXT_DATA__").html()
        
        // Look for the video slug pattern (UUID format)
        val slugRegex = """"slug":"([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})"""".toRegex()
        val slug = slugRegex.find(scriptData)?.groupValues?.get(1)
        
        if (slug != null) {
            // Also try to extract the video URL from the JSON data
            val urlRegex = """"url":"(https://[^"]+\.mp4)"""".toRegex()
            val videoUrl = urlRegex.find(scriptData)?.groupValues?.get(1)
            
            if (videoUrl != null) {
                // Direct video URL found in JSON
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        videoUrl
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            
            // If no direct URL, try the player page
            val playerUrl = "$pageUrl/player/$slug"
            val playerDoc = app.get(playerUrl).document
            
            // Check for video tags
            playerDoc.select("video[src]").forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        video.attr("src")
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            // Check for source tags
            playerDoc.select("video source[src]").forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        source.attr("src")
                    ) {
                        this.referer = playerUrl
                        this.quality = source.attr("size").toIntOrNull() ?: Qualities.Unknown.value
                    }
                )
            }
            
            return true
        }
        
        return false
    }
}
