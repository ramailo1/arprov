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
    private val pageUrl = "https://www.animeiat.tv"
    override var name = "Animeiat"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private inline fun <reified T> parseJson(text: String): T = mapper.readValue(text)

    // ------------------------------
    // DATA CLASSES
    // ------------------------------
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
    data class Episodes(
        @JsonProperty("data") var data: ArrayList<EpisodeData> = arrayListOf(),
        @JsonProperty("meta") var meta: Map<String, Any>? = null
    )

    // ------------------------------
    // MAIN PAGE
    // ------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/home/sticky-episodes" to "حلقات مثبتة",
        "$mainUrl/home/currently-airing-animes" to "يعرض حاليا",
        "$mainUrl/home/completed-animes" to "مكتمل"
    )

    // ------------------------------
    // NETWORK HELPER
    // ------------------------------
    private suspend fun fetchText(url: String): String? = try {
        app.get(url).text
    } catch (e: Exception) {
        // e.printStackTrace()
        null
    }

    // ------------------------------
    // NUXT JSON RESOLVERS
    // ------------------------------
    private fun resolveNuxt(element: Any?, rawJson: List<Any>): Any? {
        return if (element is Int && element in rawJson.indices) rawJson[element] else element
    }

    private fun resolveMap(element: Any?, rawJson: List<Any>): Map<*, *>? = resolveNuxt(element, rawJson) as? Map<*, *>
    private fun resolveString(element: Any?, rawJson: List<Any>): String? = resolveNuxt(element, rawJson) as? String
    private fun decodeBase64Url(str: String?): String? = try {
        str?.let { String(Base64.decode(it, Base64.DEFAULT)) }
    } catch (_: Exception) { null }

    // ------------------------------
    // MAIN PAGE & SEARCH
    // ------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = parseJson<HomeResponse>(fetchText(request.data) ?: "")
        val list = json.data.map {
            newAnimeSearchResponse(it.name ?: "", "$pageUrl/anime/${it.slug}", if (it.type == "movie") TvType.AnimeMovie else TvType.Anime) {
                posterUrl = it.poster?.url
                addDubStatus(false, it.episodes)
            }
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = parseJson<HomeResponse>(fetchText("$mainUrl/anime?search=$query") ?: "")
        return json.data.map {
            newAnimeSearchResponse(it.name ?: "", "$pageUrl/anime/${it.slug}", if (it.type == "movie") TvType.AnimeMovie else TvType.Anime) {
                posterUrl = it.poster?.url
                addDubStatus(false, it.episodes)
            }
        }
    }

    // ------------------------------
    // LOAD ANIME & EPISODES
    // ------------------------------
    override suspend fun load(url: String): LoadResponse {
        var slug = url.replace("$pageUrl/anime/", "")
        "(.*)-episode-\\d+$".toRegex().find(slug)?.groupValues?.get(1)?.let { slug = it }

        val json = parseJson<Load>(fetchText("$mainUrl/anime/$slug") ?: "")
        val animeId = json.data?.id ?: throw ErrorLoadingException("Anime ID not found")

        val episodesList = arrayListOf<Episode>()
        val episodesJson = parseJson<Episodes>(fetchText("$mainUrl/anime/$animeId/episodes") ?: "")
        for (it in episodesJson.data) {
            val watchUrl = "$pageUrl/watch/${json.data?.slug}-episode-${it.number}"
            episodesList.add(newEpisode(watchUrl) {
                name = it.title
                episode = it.number
                posterUrl = it.poster?.url
            })
        }

        return newAnimeLoadResponse(json.data?.name ?: "", url, if (json.data?.type == "movie") TvType.AnimeMovie else TvType.Anime) {
            posterUrl = json.data?.poster?.url
            plot = json.data?.synopsis
            addEpisodes(DubStatus.Subbed, episodesList)
            tags = json.data?.genres?.map { it.name ?: "" }
            showStatus = if (json.data?.status == "finished_airing") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }

    // ------------------------------
    // LOAD VIDEO LINKS
    // ------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payloadUrl = if (data.endsWith("/_payload.json")) data else "$data/_payload.json"

        try {
            val jsonString = fetchText(payloadUrl) ?: return false
            val rawJson = parseJson<List<Any>>(jsonString)

            // Find the episode object with a video field
            val episodeObj = rawJson.mapNotNull { resolveMap(it, rawJson) }
                .firstOrNull { it["video_id"] != null }
            val videoMap = resolveMap(episodeObj?.get("video"), rawJson)

            val urls = arrayListOf<String>()
            resolveString(videoMap?.get("url"), rawJson)?.let { urls.add(it) }
            resolveString(videoMap?.get("streamable_path"), rawJson)?.let { urls.add(it) }

            // Pick best URL: mp4 > m3u8 > first
            val selectedUrl = urls.firstOrNull { it.endsWith(".mp4") }
                ?: urls.firstOrNull { it.endsWith(".m3u8") }
                ?: urls.firstOrNull()

            if (!selectedUrl.isNullOrEmpty()) {
                callback(newExtractorLink(this.name, this.name, selectedUrl) {
                    referer = pageUrl
                    quality = Qualities.Unknown.value
                })
                return true
            }

            // Fallback to UUID/player
            val uuid = resolveString(videoMap?.get("slug"), rawJson)
            if (!uuid.isNullOrEmpty()) {
                val playerPayloadUrl = "$pageUrl/player/$uuid/_payload.json"
                val playerJsonString = fetchText(playerPayloadUrl) ?: return false
                val playerRawJson = parseJson<List<Any>>(playerJsonString)

                fun findUrl(obj: Any?): String? {
                    if (obj is Map<*, *>) {
                        (obj["url"] as? String)?.let { return it }
                        for (value in obj.values) {
                            findUrl(value)?.let { return it }
                        }
                    } else if (obj is List<*>) {
                        for (item in obj) {
                            findUrl(item)?.let { return it }
                        }
                    }
                    return null
                }

                val fallbackUrl = playerRawJson.mapNotNull { findUrl(it) }.firstOrNull()
                if (!fallbackUrl.isNullOrEmpty()) {
                    callback(newExtractorLink(this.name, this.name, fallbackUrl) {
                        referer = pageUrl
                        quality = Qualities.Unknown.value
                    })
                    return true
                }
            }

        } catch (e: Exception) {
            // e.printStackTrace()
        }

        return false
    }
}
