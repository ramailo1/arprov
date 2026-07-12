package com.lagradost.cloudstream3.gogoanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class GogoAnimeProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://gogoanimez.to"
    override var name = "GogoAnime"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Others)

    private val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = a.attr("href") ?: return null
        val title = selectFirst("h3, h6, p.name")?.text() ?: a.attr("title").ifBlank { null } ?: return null
        val poster = selectFirst("img")?.attr("src") ?: return null
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/?page=%d&type=1" to "أحدث الحلقات (Sub)",
        "$mainUrl/?page=%d&type=2" to "أحدث الحلقات (Dub)",
        "$mainUrl/new-season/?anime_page=%d" to "الموسم الجديد",
        "$mainUrl/popular/?anime_page=%d" to "الأكثر مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.format(page), timeout = 120).document
        Log.d("GogoAnime", "Fetched main page ${request.name}, page $page URL: ${request.data.format(page)}")
        val items = mutableListOf<SearchResponse>()

        doc.select("ul.items > li").mapNotNull { it.toSearchResponse() }.also { items.addAll(it) }

        Log.d("GogoAnime", "Total items gathered: ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", timeout = 60).document
        return doc.select("ul.items > li").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        var animeUrl = url
        if (url.contains("-episode-")) {
            try {
                val epDoc = app.get(url, timeout = 60).document
                val animeLink = epDoc.selectFirst("a[href*='/anime/']")?.attr("href")
                if (animeLink != null) {
                    animeUrl = fixUrl(animeLink)
                }
            } catch (_: Exception) {}
        }

        val doc = app.get(animeUrl, timeout = 120).document
        Log.d("GogoAnime", "Loading anime page $animeUrl")
        val nonce = Regex("""nonce:\s*['"]([a-f0-9]+)['"]""").find(doc.html())?.groupValues?.get(1) ?: "b273b7b384"
        Log.d("GogoAnime", "Extracted nonce: $nonce")

        val title = doc.selectFirst("div.anime_info_body_bg h1, h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Unknown"
        val poster = doc.selectFirst("div.anime_info_body_bg img")?.attr("src")
            ?.let { fixUrlNull(it) }
        val synopsis = doc.selectFirst("div.description p")?.text()?.trim() ?: ""
        val genres = doc.select("p.type:contains(Genres) a, p.type:contains(التصنيفات) a").map { it.text() }
        val year = doc.selectFirst("p.type:contains(Released)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val episodeRanges = doc.select("#episode_page a").mapNotNull {
            Triple(
                it.attr("data-range-start").toIntOrNull() ?: return@mapNotNull null,
                it.attr("data-range-end").toIntOrNull() ?: return@mapNotNull null,
                it.attr("data-seri").toIntOrNull() ?: return@mapNotNull null
            )
        }

        Log.d("GogoAnime", "Found ${episodeRanges.size} episode ranges")
        val episodes = mutableListOf<Episode>()

        if (episodeRanges.isNotEmpty()) {
            for ((rangeStart, rangeEnd, seriId) in episodeRanges) {
                try {
                    val ajaxDoc = app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to "load_episode_range",
                            "range_start" to rangeStart.toString(),
                            "range_end" to rangeEnd.toString(),
                            "seri_id" to seriId.toString(),
                            "nonce" to nonce
                        ),
                        timeout = 30
                    ).document
                    ajaxDoc.select("li > a[href*='-episode-']").forEach { a ->
                        val epUrl = a.attr("href")
                        val epName = a.text().trim()
                        val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                        if (epUrl.isNotBlank()) {
                            episodes.add(newEpisode(epUrl) {
                                this.name = epName.ifBlank { "Episode $epNum" }
                                this.episode = epNum
                            })
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val sorted = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: 0 }))

        val tvType = if (genres.any { it.contains("Movie", true) }) TvType.AnimeMovie else TvType.Anime
        val dubStatus = if (title.contains("مدبلج", true) || title.contains("dub", true))
            DubStatus.Dubbed else DubStatus.Subbed

        return newAnimeLoadResponse(title, animeUrl, tvType) {
            this.apiName = this@GogoAnimeProvider.name
            addEpisodes(dubStatus, sorted)
            this.posterUrl = poster
            this.year = year
            plot = synopsis
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 60).document

        val serverLinks = doc.select(".anime_muti_link a[data-video]")
        Log.d("GogoAnime", "Found ${serverLinks.size} server links")
        if (serverLinks.isEmpty()) return false

        val serverUrls = mutableListOf<Pair<String, String>>()

        for (link in serverLinks) {
            val label = link.select("i, span").text().trim().ifBlank { link.text().trim() }
            val rel = link.attr("rel")
            val videoHtml = link.attr("data-video")
            val iframeSrc = Jsoup.parse(videoHtml).selectFirst("iframe")?.attr("src")
            if (iframeSrc != null) {
                val type = if (rel == "1") "sub" else "dub"
                val serverLabel = "$label ($type)"
                serverUrls.add(Pair(serverLabel, iframeSrc))
            }
        }

        Log.d("GogoAnime", "Collected ${serverUrls.size} server URLs")
        if (serverUrls.isEmpty()) return false

        for ((serverLabel, streamingUrl) in serverUrls) {
            try {
                Log.d("GogoAnime", "Processing streaming URL $streamingUrl")
                val streamDoc = app.get(streamingUrl, timeout = 30).document
                val megaplayIframe = streamDoc.selectFirst("iframe")?.attr("src")
                Log.d("GogoAnime", "Found megaplay iframe $megaplayIframe")

                if (megaplayIframe != null) {
                    val megaplayDoc = app.get(megaplayIframe, timeout = 30, referer = streamingUrl).document

                    val m3u8Url = Regex("""['"]?(https?://[^'"]*\.m3u8[^'"]*)""", RegexOption.IGNORE_CASE)
                        .find(megaplayDoc.html())?.groupValues?.get(1)

                    if (m3u8Url != null) {
                        Log.d("GogoAnime", "Found m3u8 $m3u8Url")
                        callback(newExtractorLink(name, serverLabel, m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = megaplayIframe
                            this.quality = Qualities.P720.value
                        })
                    }

                    val subtitlePattern = Regex(
                        """['"]?(https?://[^'"]*\.(?:vtt|srt|ass|sub)[^'"]*)""",
                        RegexOption.IGNORE_CASE
                    )
                    subtitlePattern.findAll(megaplayDoc.html()).forEach { match ->
                        val subUrl = match.groupValues[1]
                        if (subUrl.isNotBlank()) {
                            Log.d("GogoAnime", "Found subtitle $subUrl")
                            subtitleCallback(newSubtitleFile(
                                lang = extractSubtitleLang(subUrl),
                                url = subUrl
                            ))
                        }
                    }

                    val mp4Url = Regex("""['"]?(https?://[^'"]*\.mp4[^'"]*)""", RegexOption.IGNORE_CASE)
                        .find(megaplayDoc.html())?.groupValues?.get(1)
                    if (mp4Url != null && m3u8Url == null) {
                        Log.d("GogoAnime", "Found MP4 $mp4Url")
                        callback(newExtractorLink(name, serverLabel, mp4Url, ExtractorLinkType.VIDEO) {
                            this.referer = megaplayIframe
                            this.quality = Qualities.P720.value
                        })
                    }
                }
            } catch (_: Exception) {}
        }

        for ((_, streamingUrl) in serverUrls) {
            Log.d("GogoAnime", "Loading extractor for $streamingUrl")
            loadExtractor(streamingUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private fun extractSubtitleLang(url: String): String {
        val lower = url.lowercase()
        return when {
            "arab" in lower || "ar" in lower -> "العربية"
            "eng" in lower || "english" in lower -> "English"
            "fra" in lower || "french" in lower -> "Français"
            "tur" in lower || "turk" in lower -> "Türkçe"
            "ind" in lower || "indo" in lower -> "Indonesia"
            "esp" in lower || "spanish" in lower || "spa" in lower -> "Español"
            "por" in lower || "portuguese" in lower -> "Português"
            "rus" in lower || "russian" in lower -> "Русский"
            "jpn" in lower || "japanese" in lower -> "日本語"
            "ita" in lower || "italian" in lower -> "Italiano"
            "deu" in lower || "german" in lower || "ger" in lower -> "Deutsch"
            "tha" in lower || "thai" in lower -> "ไทย"
            "vie" in lower || "viet" in lower -> "Tiếng Việt"
            "hin" in lower || "hindi" in lower -> "हिन्दी"
            "kor" in lower || "korean" in lower -> "한국어"
            "zho" in lower || "chinese" in lower || "chi" in lower -> "中文"
            "may" in lower || "malay" in lower -> "Bahasa Melayu"
            "pol" in lower || "polish" in lower -> "Polski"
            "rum" in lower || "romanian" in lower -> "Română"
            else -> "Unknown"
        }
    }
}
