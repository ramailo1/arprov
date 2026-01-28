package com.lagradost.cloudstream3.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Base64

class AnimeiatProvider : MainAPI() {

    override var lang = "ar"
    override var mainUrl = "https://api.animegarden.net/v1/animeiat"
    val pageUrl = "https://www.animeiat.tv"
    override var name = "Animeiat"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private inline fun <reified T> parseJson(text: String): T {
        return mapper.readValue(text)
    }

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
        val episodeRegex = "(.*)-episode-\\d+$".toRegex()
        episodeRegex.find(slug)?.groupValues?.get(1)?.let { slug = it }

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
        val payloadUrl = if (data.endsWith("/_payload.json")) data else "$data/_payload.json"

        try {
            val jsonString = app.get(payloadUrl).text
            val rawJson = parseJson<List<Any>>(jsonString)

            // Nuxt Reference Resolver
            fun resolve(element: Any?): Any? {
                return when (element) {
                    is Int -> if (element in rawJson.indices) rawJson[element] else element
                    else -> element
                }
            }
            
            // Helper to resolved specific values as Types
            fun resolveAsMap(element: Any?): Map<*, *>? = resolve(element) as? Map<*, *>
            fun resolveAsString(element: Any?): String? = resolve(element) as? String

            // Step 1: Find the episode object
            // We need to resolve each item to check if it's the right map
            val episodeObj = rawJson.mapNotNull { resolveAsMap(it) }
                .firstOrNull { it["video_id"] != null }

            // Step 2: Get video URLs (highest quality first)
            // 'video' field is likely an index, so we resolve it
            val videoMap = resolveAsMap(episodeObj?.get("video"))
            
            val urls = arrayListOf<String>()

            // 'url' and 'streamable_path' fields might also be indices to strings
            resolveAsString(videoMap?.get("url"))?.let { urls.add(it) }
            resolveAsString(videoMap?.get("streamable_path"))?.let { urls.add(it) }

            val selectedUrl = urls.firstOrNull()?.let {
                if (it.startsWith("http")) it else "$pageUrl/$it"
            }

            if (!selectedUrl.isNullOrEmpty()) {
                callback(
                    newExtractorLink(this.name, this.name, selectedUrl) {
                        referer = pageUrl
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Step 3: Fallback to UUID/player payload
            val uuid = resolveAsString(videoMap?.get("slug"))
            if (!uuid.isNullOrEmpty()) {
                val playerPayloadUrl = "$pageUrl/player/$uuid/_payload.json"
                val playerJsonString = app.get(playerPayloadUrl).text
                val playerRawJson = parseJson<List<Any>>(playerJsonString)

                fun findUrl(obj: Any?): String? {
                    if (obj is Map<*, *>) {
                        (obj["url"] as? String)?.let { return it }
                        obj.values.forEach { findUrl(it)?.let { return it } }
                    } else if (obj is List<*>) {
                        // Also iterate integers which might be indices in player payload?
                        // For simplicity assuming player payload key/values are strings/maps or simple lists
                        obj.forEach { findUrl(it)?.let { return it } }
                    }
                    return null
                }

                val fallbackUrl = playerRawJson.mapNotNull { findUrl(it) }.firstOrNull()
                if (!fallbackUrl.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(this.name, this.name, fallbackUrl) {
                            referer = pageUrl
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
