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
    override var mainUrl = "https://api.animegarden.net/v1/animeiat"
    val pageUrl = "https://www.animeiat.tv"
    override var name = "Animeiat"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Anime, TvType.AnimeMovie)

    // Old API data models (kept for compatibility)
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

    // New API data models
    data class Poster (
        @JsonProperty("url") var url: String? = null
    )
    
    data class AnimeData (
        @JsonProperty("name"           ) var name          : String? = null,
        @JsonProperty("slug"           ) var slug          : String? = null,
        @JsonProperty("status"         ) var status        : String? = null,
        @JsonProperty("episodes"       ) var episodes      : Int? = null,
        @JsonProperty("type"           ) var type          : String? = null,
        @JsonProperty("poster"         ) var poster        : Poster? = Poster(),
    )
    
    data class EpisodeHomeData (
        @JsonProperty("title"          ) var title         : String? = null,
        @JsonProperty("slug"           ) var slug          : String? = null,
        @JsonProperty("number"         ) var number        : Int? = null,
        @JsonProperty("poster"         ) var poster        : Poster? = Poster(),
        @JsonProperty("anime"          ) var anime         : AnimeData? = AnimeData(),
    )
    
    data class HomeResponse (
        @JsonProperty("data") var data: ArrayList<AnimeData> = arrayListOf(),
    )
    
    data class HomeEpisodeResponse (
        @JsonProperty("data") var data: ArrayList<EpisodeHomeData> = arrayListOf(),
    )

    override val mainPage = mainPageOf(
        "$mainUrl/home/sticky-episodes" to "حلقات مثبتة",
        "$mainUrl/home/currently-airing-animes" to "يعرض حاليا",
        "$mainUrl/home/completed-animes" to "مكتمل",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data.contains("sticky-episodes")) {
            // Sticky episodes returns episode objects
            val json = parseJson<HomeEpisodeResponse>(app.get(request.data).text)
            json.data.map {
                val animeSlug = it.anime?.slug ?: it.slug?.replace("-episode.*".toRegex(), "")
                newAnimeSearchResponse(
                    it.anime?.name ?: it.title.toString(),
                    "$pageUrl/anime/$animeSlug",
                    if (it.anime?.type == "movie") TvType.AnimeMovie else TvType.Anime,
                ) {
                    this.posterUrl = it.poster?.url
                    addDubStatus(false, 1) // Episode count not available in this endpoint
                }
            }
        } else {
            // Currently airing and completed return anime objects
            val json = parseJson<HomeResponse>(app.get(request.data).text)
            json.data.map {
                newAnimeSearchResponse(
                    it.name.toString(),
                    "$pageUrl/anime/${it.slug}",
                    if (it.type == "movie") TvType.AnimeMovie else TvType.Anime,
                ) {
                    this.posterUrl = it.poster?.url
                    addDubStatus(false, it.episodes)
                }
            }
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = parseJson<HomeResponse>(app.get("$mainUrl/anime?q=$query").text)
        return json.data.map {
            newAnimeSearchResponse(
                it.name.toString(),
                "$pageUrl/anime/${it.slug}",
                if(it.type == "movie") TvType.AnimeMovie else TvType.Anime,
            ) {
                addDubStatus(false, it.episodes)
                this.posterUrl = it.poster?.url
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
    
    // New API video data models
    data class VideoData (
        @JsonProperty("url"  ) var url  : String? = null,
        @JsonProperty("slug" ) var slug : String? = null,
        @JsonProperty("hash" ) var hash : String? = null,
    )
    
    data class EpisodeDetailData (
        @JsonProperty("video") var video: VideoData? = VideoData(),
    )
    
    data class EpisodeDetailResponse (
        @JsonProperty("data") var data: EpisodeDetailData? = EpisodeDetailData(),
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
        // Extract episode slug from the watch URL
        // URL format: https://www.animeiat.tv/watch/{episode-slug}
        val episodeSlug = data.replace("$pageUrl/watch/", "")
        
        // Call the new episode API endpoint
        val episodeApiUrl = "$mainUrl/episodes/$episodeSlug"
        
        try {
            val response = app.get(episodeApiUrl).text
            val episodeDetail = parseJson<EpisodeDetailResponse>(response)
            
            // Extract the direct video URL from the response
            val videoUrl = episodeDetail.data?.video?.url
            
            if (videoUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        videoUrl
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // If API call fails, return false
            return false
        }
        
        return false
    }
}
