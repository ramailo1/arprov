package com.lagradost.cloudstream3.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.annotation.JsonProperty
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
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    data class Poster(@JsonProperty("url") var url: String? = null)
    data class AnimeData(
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("type") var type: String? = null,
        @JsonProperty("poster") var poster: Poster? = Poster(),
        @JsonProperty("episodes") var episodes: Int? = null
    )
    data class HomeResponse(@JsonProperty("data") var data: ArrayList<AnimeData> = arrayListOf())

    data class Year(@JsonProperty("name") var name: String? = null)
    data class Genres(@JsonProperty("name") var name: String? = null)
    data class LoadData(
        @JsonProperty("id") var id: Int? = null,
        @JsonProperty("name") var name: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("synopsis") var synopsis: String? = null,
        @JsonProperty("other_names") var otherNames: String? = null,
        @JsonProperty("type") var type: String? = null,
        @JsonProperty("status") var status: String? = null,
        @JsonProperty("poster") var poster: Poster? = Poster(),
        @JsonProperty("year") var year: Year? = Year(),
        @JsonProperty("genres") var genres: ArrayList<Genres> = arrayListOf()
    )
    data class Load(@JsonProperty("data") var data: LoadData? = LoadData())

    data class EpisodeData(
        @JsonProperty("title") var title: String? = null,
        @JsonProperty("number") var number: Int? = null,
        @JsonProperty("poster") var poster: Poster? = Poster(),
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("video") var video: Map<String, Any>? = null
    )
    data class Episodes(@JsonProperty("data") var data: ArrayList<EpisodeData> = arrayListOf(), @JsonProperty("meta") var meta: Map<String, Any>? = null)

    override val mainPage = mainPageOf(
        "$mainUrl/home/sticky-episodes" to "حلقات مثبتة",
        "$mainUrl/home/currently-airing-animes" to "يعرض حاليا",
        "$mainUrl/home/completed-animes" to "مكتمل",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data.contains("sticky-episodes")) {
            val json = parseJson<HomeResponse>(app.get(request.data).text)
            json.data.map {
                newAnimeSearchResponse(it.name ?: "", "$pageUrl/anime/${it.slug}", if (it.type == "movie") TvType.AnimeMovie else TvType.Anime) {
                    posterUrl = it.poster?.url
                }
            }
        } else {
            val json = parseJson<HomeResponse>(app.get(request.data).text)
            json.data.map {
                newAnimeSearchResponse(it.name ?: "", "$pageUrl/anime/${it.slug}", if (it.type == "movie") TvType.AnimeMovie else TvType.Anime) {
                    posterUrl = it.poster?.url
                    addDubStatus(false, it.episodes)
                }
            }
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = parseJson<HomeResponse>(app.get("$mainUrl/anime?search=$query").text)
        return json.data.map {
            newAnimeSearchResponse(it.name ?: "", "$pageUrl/anime/${it.slug}", if (it.type == "movie") TvType.AnimeMovie else TvType.Anime) {
                posterUrl = it.poster?.url
                addDubStatus(false, it.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var slug = url.replace("$pageUrl/anime/", "")
        
        // Fix: If the slug ends with "-episode-{number}", strip it to get the anime slug
        // This handles clicks from "Latest Episodes" which pass the episode URL
        val episodeRegex = "(.*)-episode-\\d+$".toRegex()
        episodeRegex.find(slug)?.groupValues?.get(1)?.let {
            slug = it
        }

        val animeApiUrl = "$mainUrl/anime/$slug"
        val json = parseJson<Load>(app.get(animeApiUrl).text)
        val animeId = json.data?.id ?: throw ErrorLoadingException("Anime ID not found")

        val episodesList = arrayListOf<Episode>()
        val episodesApiUrl = "$mainUrl/anime/$animeId/episodes"
        try {
            val episodesResponse = parseJson<Episodes>(app.get(episodesApiUrl).text)
            episodesResponse.data.forEach {
                val watchUrl = "$pageUrl/watch/${json.data?.slug}-episode-${it.number}"
                episodesList.add(newEpisode(watchUrl) {
                    name = it.title
                    episode = it.number
                    posterUrl = it.poster?.url
                })
            }
        } catch (_: Exception) {}

        return newAnimeLoadResponse(json.data?.name ?: "", url, if (json.data?.type == "movie") TvType.AnimeMovie else TvType.Anime) {
            posterUrl = json.data?.poster?.url
            plot = json.data?.synopsis
            addEpisodes(DubStatus.Subbed, episodesList)
            tags = json.data?.genres?.map { it.name ?: "" }
            showStatus = if (json.data?.status == "finished_airing") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data here is the Watch URL (e.g. .../watch/slug-episode-1)
        // We append /_payload.json to get the Nuxt payload directly
        val payloadUrl = if (data.endsWith("/_payload.json")) data else "$data/_payload.json"

        try {
            val jsonString = app.get(payloadUrl).text
            // Parse the Nuxt payload (Array of objects)
            val rawJson = parseJson<List<Any>>(jsonString)

            // Helper to resolve Nuxt references (indices)
            fun resolve(element: Any?): Any? {
                return when (element) {
                    is Int -> if (element in rawJson.indices) rawJson[element] else element
                    else -> element
                }
            }

            // Recursive URL extractor that handles dereferencing
            fun extractUrl(obj: Any?, depth: Int = 0): String? {
                if (depth > 10 || obj == null) return null

                // If we found a string that looks like a URL, check it
                if (obj is String) {
                    if (obj.startsWith("http")) return obj
                    // Check for Base64 encoded URL
                    if (obj.length > 20 && !obj.contains(" ")) {
                        try {
                            val decoded = String(Base64.decode(obj, Base64.DEFAULT))
                            if (decoded.startsWith("http")) return decoded
                        } catch (_: Exception) { }
                    }
                    // Check if it's a UUID (Player Slug) -> Fetch Player Payload
                    if (obj.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                        return "UUID:$obj"
                    }
                    return null
                }

                // If it's an index, resolve and recurse
                if (obj is Int) {
                    return extractUrl(resolve(obj), depth + 1)
                }

                // If it's a Map/Object
                if (obj is Map<*, *>) {
                    // Prioritize specific keys
                    val keys = listOf("url", "file", "src", "video", "slug", "link")

                    // First pass: direct checks
                    for (key in keys) {
                        val value = obj[key]
                        // Don't recurse yet, check if immediate value is useful
                        if (value is String && value.startsWith("http")) return value
                    }

                    // Second pass: resolve indices/recurse
                    for (key in keys) {
                        val value = obj[key]
                        // Skip primitive non-string values to save recursion except for Ints which are indices
                        if (value is Int || value is Map<*, *> || value is List<*>) {
                             val result = extractUrl(value, depth + 1)
                             if (result != null) return result
                        }
                    }
                    
                    // Third pass: check values of video/source objects
                    obj.forEach { (k, v) ->
                        if (k is String && k == "video") { // Look hard at video object
                             val result = extractUrl(v, depth + 1)
                             if (result != null) return result
                        }
                    }
                }
                
                return null
            }

            var foundUrl: String? = null

            // Iterate through top-level objects to find the detailed data
            // We iterate reversed because data is usually at the end
            for (item in rawJson.reversed()) {
                val resolvedItem = if (item is Int) resolve(item) else item
                val result = extractUrl(resolvedItem)

                if (result != null) {
                    if (result.startsWith("UUID:")) {
                        // It's a UUID, fetch nested player payload as fallback
                        val uuid = result.removePrefix("UUID:")
                        val playerPayloadUrl = "$pageUrl/player/$uuid/_payload.json"
                        try {
                            val playerJsonString = app.get(playerPayloadUrl).text
                            val playerRawJson = parseJson<List<Any>>(playerJsonString)
                            
                            // Simple linear search in player payload (no deep recursion needed usually)
                            fun simpleSearch(pObj: Any?): String? {
                                if (pObj is String && pObj.startsWith("http")) return pObj
                                if (pObj is Int && pObj in playerRawJson.indices) return simpleSearch(playerRawJson[pObj])
                                if (pObj is Map<*, *>) {
                                    pObj["url"]?.let { return simpleSearch(it) }
                                }
                                return null
                            }
                            
                            for (pItem in playerRawJson.reversed()) {
                                val res = simpleSearch(pItem)
                                if (res != null) {
                                    foundUrl = res
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        foundUrl = result
                    }

                    if (foundUrl != null) break
                }
            }

            if (foundUrl != null) {
                callback.invoke(
                    newExtractorLink(this.name, this.name, foundUrl!!) {
                        referer = pageUrl
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
