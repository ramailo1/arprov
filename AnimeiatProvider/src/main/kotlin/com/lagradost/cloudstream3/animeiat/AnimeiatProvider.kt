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
        val slug = url.replace("$pageUrl/anime/", "")
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
        try {
            // Step 1: data = episode watch URL
            val html = app.get(data, headers = mapOf("Referer" to pageUrl)).text

            // Step 2: extract player UUID from page HTML
            val uuidRegex = """"playerId"\s*:\s*"([a-f0-9\-]{36})"""".toRegex()
            val match = uuidRegex.find(html)
            val playerSlug = match?.groups?.get(1)?.value ?: return false

            // Step 3: fetch player payload JSON
            val payloadUrl = "$pageUrl/player/$playerSlug/_payload.json"
            val payloadResponse = app.get(payloadUrl, headers = mapOf("Referer" to pageUrl)).text
            val payload = parseJson<List<Any>>(payloadResponse)

            // Step 4: recursive extractor
            fun extractUrl(obj: Any?): String? {
                if (obj !is Map<*, *>) return null
                val direct = obj["url"] as? String
                if (!direct.isNullOrEmpty() && direct.startsWith("http")) return direct
                val b64 = obj["url"] as? String
                if (!b64.isNullOrEmpty()) {
                    try {
                        val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                        if (decoded.startsWith("http")) return decoded
                    } catch (_: Exception) {}
                }
                val index = when (val v = obj["url"]) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
                if (index != null && index < payload.size) return extractUrl(payload[index])
                return null
            }

            payload.forEach { item ->
                val url = extractUrl(item)
                if (!url.isNullOrEmpty()) {
                    callback(newExtractorLink(this.name, this.name, url) {
                        referer = pageUrl
                        quality = Qualities.Unknown.value
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
