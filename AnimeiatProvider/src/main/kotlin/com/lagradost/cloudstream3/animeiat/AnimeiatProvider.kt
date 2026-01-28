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
    
    data class VideoData (
        @JsonProperty("slug" ) var slug : String? = null,
        @JsonProperty("url"  ) var url  : String? = null,
    )
    
    data class EpisodeData (
        @JsonProperty("id"           ) var id          : Int? = null,
        @JsonProperty("title"        ) var title       : String? = null,
        @JsonProperty("slug"         ) var slug        : String? = null,
        @JsonProperty("number"       ) var number      : Int? = null,
        @JsonProperty("video_id"     ) var videoId     : Int? = null,
        @JsonProperty("poster"       ) var poster      : Poster? = Poster(),
        @JsonProperty("video"        ) var video       : VideoData? = VideoData(),
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
        // Extract slug from URL. Handle both /anime/ and /watch/ formats
        // https://www.animeiat.tv/anime/{slug}
        // https://www.animeiat.tv/watch/{slug}-episode-{number}
        val slug = if (url.contains("/watch/")) {
            url.substringAfter("/watch/").substringBefore("-episode")
        } else {
            url.replace("$pageUrl/anime/", "")
        }

        // Fetch anime details using slug
        val animeApiUrl = "$mainUrl/anime/$slug"
        val animeResponse = app.get(animeApiUrl).text
        val json = parseJson<Load>(animeResponse)

        val animeId = json.data?.id ?: throw ErrorLoadingException("Anime ID not found")

        // Fetch episodes using the anime ID
        val episodesList = arrayListOf<Episode>()
        val episodesApiUrl = "$mainUrl/anime/$animeId/episodes"

        try {
            val episodesResponse = parseJson<Episodes>(app.get(episodesApiUrl).text)
            val totalPages = episodesResponse.meta.lastPage ?: 1

            (1..totalPages).forEach { pageNumber ->
                val pageUrl = if (pageNumber == 1) episodesApiUrl else "$episodesApiUrl?page=$pageNumber"
                val pageData = parseJson<Episodes>(app.get(pageUrl).text)

                pageData.data.forEach {
                    // Assign the player ID as the video slug (UUID)
                    val playerSlug = it.video?.slug ?: it.slug ?: return@forEach
                    
                    // Proper Watch URL for the user/browser
                    val watchUrl = "$pageUrl/watch/${json.data?.slug}-episode-${it.number}"
                    
                    // Append UUID as a fake query parameter for loadLinks to extract
                    // This creates a valid URL for the browser (which ignores the extra param)
                    // and passes the UUID to loadLinks
                    val episodeData = "$watchUrl?uid=$playerSlug"

                    episodesList.add(newEpisode(episodeData) {
                        name = it.title
                        episode = it.number
                        posterUrl = it.poster?.url
                    })
                }
            }
        } catch (_: Exception) {
            // If episodes fetch fails, continue with empty list
        }

        return newAnimeLoadResponse(
            json.data?.name.toString(),
            url,
            when (json.data?.type) {
                "movie" -> TvType.AnimeMovie
                "tv" -> TvType.Anime
                "ova" -> TvType.OVA
                else -> TvType.Anime
            }
        ) {
            japName = json.data?.otherNames?.replace("\\n.*".toRegex(), "")
            engName = json.data?.name
            posterUrl = json.data?.poster?.url
            year = json.data?.year?.name?.toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodesList)
            plot = json.data?.synopsis
            tags = json.data?.genres?.map { it.name.toString() }
            showStatus = if (json.data?.status == "finished_airing") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Extract UUID from the fake query parameter "?uid="
            // Fallback to raw data if not found (backward compatibility)
            val playerSlug = if (data.contains("?uid=")) {
                data.substringAfter("?uid=")
            } else {
                // Try decoding legacy Base64 or LinkData if present, else raw
                try {
                     // Check if it's Base64 (simple check)
                     if (data.length > 20 && !data.contains("/")) String(Base64.decode(data, Base64.DEFAULT)) else data
                } catch (e: Exception) {
                    data
                }
            }
            
            val payloadUrl = "$pageUrl/player/$playerSlug/_payload.json"
            val response = app.get(payloadUrl, headers = mapOf("Referer" to pageUrl)).text
            val payload = parseJson<List<Any>>(response)

            // Recursive extraction helper (Handles Direct, Base64, and Index dereferencing)
            fun extractUrl(obj: Any?): String? {
                if (obj !is Map<*, *>) return null

                // 1. Direct URL
                val direct = obj["url"] as? String
                if (direct != null && direct.startsWith("http")) return direct

                // 2. Base64 URL
                val b64 = obj["url"] as? String
                if (b64 != null && b64.isNotEmpty()) {
                    try {
                        val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                        if (decoded.startsWith("http")) return decoded
                    } catch (_: Exception) {}
                }

                // 3. Index-based URL (Dereferencing)
                val index = when (val v = obj["url"]) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
                if (index != null && index < payload.size) {
                    return extractUrl(payload[index])
                }

                return null
            }

            // Loop through payload to find the correct URL
            for (item in payload) {
                val url = extractUrl(item)
                if (!url.isNullOrEmpty()) {
                    callback.invoke(newExtractorLink(this.name, this.name, url) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
