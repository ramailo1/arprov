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
        val json = parseJson<HomeResponse>(app.get("$mainUrl/anime?search=$query").text)
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
        @JsonProperty("id"             ) var id            : Int? = null,
        @JsonProperty("name"           ) var name          : String? = null,
        @JsonProperty("slug"           ) var slug          : String? = null,
        @JsonProperty("synopsis"       ) var synopsis      : String? = null,
        @JsonProperty("other_names"    ) var otherNames    : String? = null,
        @JsonProperty("age"            ) var age           : String? = null,
        @JsonProperty("type"           ) var type          : String? = null,
        @JsonProperty("status"         ) var status        : String? = null,
        @JsonProperty("poster"         ) var poster        : Poster? = Poster(),
        @JsonProperty("year"           ) var year          : Year?   = Year(),
        @JsonProperty("genres"         ) var genres        : ArrayList<Genres>  = arrayListOf(),

    )
    data class Load (

        @JsonProperty("data" ) var data : LoadData? = LoadData()

    )
    data class Meta (
        @JsonProperty("last_page"    ) var lastPage    : Int? = null,
    )
    data class EpisodeData (
        @JsonProperty("id"           ) var id          : Int? = null,
        @JsonProperty("title"        ) var title       : String? = null,
        @JsonProperty("slug"         ) var slug        : String? = null,
        @JsonProperty("number"       ) var number      : Int? = null,
        @JsonProperty("video_id"     ) var videoId     : Int? = null,
        @JsonProperty("poster"       ) var poster      : Poster? = Poster(),
    )
    data class Episodes (
        @JsonProperty("data"  ) var data  : ArrayList<EpisodeData> = arrayListOf(),
        @JsonProperty("meta"  ) var meta  : Meta = Meta()
    )
    
    // Nuxt Payload data models for video extraction
    data class PayloadEpisode (
        @JsonProperty("id"    ) var id    : Any? = null,
        @JsonProperty("title" ) var title : Any? = null,
        @JsonProperty("slug"  ) var slug  : Any? = null,
        @JsonProperty("video" ) var video : Any? = null,  // This is an index to the video object
    )
    data class PayloadVideo (
        @JsonProperty("id"   ) var id   : Any? = null,
        @JsonProperty("url"  ) var url  : Any? = null,  // This is an index to the URL string
        @JsonProperty("slug" ) var slug : Any? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        // Extract slug from URL: https://www.animeiat.tv/anime/{slug}
        val slug = url.replace("$pageUrl/anime/", "")
        
        // Fetch anime details using slug
        val animeApiUrl = "$mainUrl/anime/$slug"
        val animeResponse = app.get(animeApiUrl).text
        val json = parseJson<Load>(animeResponse)
        
        // Get the anime ID for fetching episodes
        val animeId = json.data?.id ?: throw ErrorLoadingException("Anime ID not found")
        
        // Fetch episodes using the anime ID
        val episodes = arrayListOf<Episode>()
        val episodesApiUrl = "$mainUrl/anime/$animeId/episodes"
        
        try {
            val episodesResponse = parseJson<Episodes>(app.get(episodesApiUrl).text)
            val totalPages = episodesResponse.meta.lastPage ?: 1
            
            (1..totalPages).map { pageNumber ->
                val pageUrl = if (pageNumber == 1) episodesApiUrl else "$episodesApiUrl?page=$pageNumber"
                parseJson<Episodes>(app.get(pageUrl).text).data.map {
                    episodes.add(
                        newEpisode(it.slug.toString()) {
                            this.name = it.title
                            this.episode = it.number
                            this.posterUrl = it.poster?.url
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // If episodes fetch fails, continue without episodes
        }
        
        return newAnimeLoadResponse(json.data?.name.toString(), url, if(json.data?.type == "movie") TvType.AnimeMovie else if(json.data?.type == "tv") TvType.Anime else TvType.OVA) {
            japName = json.data?.otherNames?.replace("\\n.*".toRegex(), "")
            engName = json.data?.name
            posterUrl = json.data?.poster?.url
            this.year = json.data?.year?.name?.toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodes)
            plot = json.data?.synopsis
            tags = json.data?.genres?.map { it.name.toString() }
            this.showStatus = if(json.data?.status == "finished_airing") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data contains the episode slug
        val episodeSlug = data
        
        // Fetch the Nuxt payload JSON from the watch page
        val payloadUrl = "$pageUrl/watch/$episodeSlug/_payload.json"
        
        try {
            val response = app.get(payloadUrl, headers = mapOf(
                "Referer" to pageUrl
            )).text
            
            // Parse the payload JSON as a raw list
            val payload = parseJson<List<Any>>(response)
            
            // Step 1: Find the index of the slug string in the array
            var slugIndex: Int? = null
            for (i in payload.indices) {
                if (payload[i] is String && payload[i] == episodeSlug) {
                    slugIndex = i
                    break
                }
            }
            
            if (slugIndex == null) {
                return false
            }
            
            // Step 2: Find the episode object that references this slug index
            var videoUrl: String? = null
            
            for (i in payload.indices) {
                val item = payload[i]
                if (item is Map<*, *>) {
                    // Check if this episode object's slug field equals our slug index
                    if (item.containsKey("slug") && item.containsKey("video")) {
                        val itemSlugIndex = when (val s = item["slug"]) {
                            is Number -> s.toInt()
                            is String -> s.toIntOrNull()
                            else -> null
                        }
                        
                        if (itemSlugIndex == slugIndex) {
                            // Found the episode object! Now get the video
                            val videoIndex = when (val v = item["video"]) {
                                is Number -> v.toInt()
                                is String -> v.toIntOrNull()
                                else -> null
                            }
                            
                            if (videoIndex != null && videoIndex < payload.size) {
                                val videoObj = payload[videoIndex]
                                if (videoObj is Map<*, *> && videoObj.containsKey("url")) {
                                    // Get the URL index
                                    val urlIndex = when (val u = videoObj["url"]) {
                                        is Number -> u.toInt()
                                        is String -> u.toIntOrNull()
                                        else -> null
                                    }
                                    
                                    if (urlIndex != null && urlIndex < payload.size) {
                                        val urlString = payload[urlIndex]
                                        if (urlString is String && urlString.startsWith("http")) {
                                            videoUrl = urlString
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
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
            // If payload fetch fails, return false
            return false
        }
        
        return false
    }
}
