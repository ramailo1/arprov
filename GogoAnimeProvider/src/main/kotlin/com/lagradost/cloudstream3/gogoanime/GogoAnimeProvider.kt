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
    override val usesWebView = true
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
        "$mainUrl/new-season/?anime_page=%d" to "الموسم الجديد",
        "$mainUrl/popular/?anime_page=%d" to "الأكثر مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()

        if (request.name.contains("الحلقات")) {
            try {
                val json = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "load_recent_releases",
                        "type" to "1",
                        "page" to page.toString()
                    ),
                    timeout = 30
                ).text
                val body = org.json.JSONObject(json)
                    .optJSONObject("data")
                    ?.optString("content", "")
                    ?: ""
                val doc = Jsoup.parse(body)
                doc.select("li").mapNotNull { it.toSearchResponse() }.also { items.addAll(it) }
                Log.d("GogoAnime", "Sub page $page: ${items.size} items (via AJAX)")
            } catch (e: Exception) {
                Log.d("GogoAnime", "Sub AJAX failed, falling back to HTML: ${e.message}")
                val doc = app.get(request.data.format(page), timeout = 120).document
                doc.select("ul.items > li").mapNotNull { it.toSearchResponse() }.also { items.addAll(it) }
            }
        } else {
            val doc = app.get(request.data.format(page), timeout = 120).document
            Log.d("GogoAnime", "Fetched ${request.name}, page $page")
            doc.select("ul.items > li").mapNotNull { it.toSearchResponse() }.also { items.addAll(it) }
        }

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
                        val rawHref = a.attr("href")
                        val epUrl = rawHref
                            .replace("\\/", "/")
                            .replace("\\\"", "\"")
                            .trim()
                            .removeSurrounding("\"").removeSurrounding("'")
                        val fullText = a.text().trim()
                        Log.d("GogoAnime", "  episode: rawHref='$rawHref' cleanUrl='$epUrl' fullText='$fullText'")
                        val epNum = Regex("""episode[_-](\d+)""", RegexOption.IGNORE_CASE)
                            .find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""\d+""").find(fullText)?.value?.toIntOrNull()
                        val type = when {
                            fullText.contains("DUB", true) || fullText.contains("مدبلج", true) -> "DUB"
                            else -> "SUB"
                        }
                        val epName = if (epNum != null) "Episode $epNum ($type)" else "Episode ($type)"
                        if (epUrl.isNotBlank()) {
                            episodes.add(newEpisode(epUrl) {
                                this.name = epName
                                this.episode = epNum
                            })
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val sorted = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: 0 }))

        Log.d("GogoAnime", "Final episode list (${sorted.size} total):")
        sorted.take(10).forEach { Log.d("GogoAnime", "  ep=${it.episode} name='${it.name}' data='${it.data}'") }
        if (sorted.size > 10) Log.d("GogoAnime", "  ... and ${sorted.size - 10} more")

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
        Log.d("GogoAnime", "=== loadLinks called ===")
        Log.d("GogoAnime", "raw data URL: $data")

        val cleanData = data.replace("\\/", "/")
        val realUrl = Regex("""https?://[^\s"']+""").findAll(cleanData).lastOrNull()?.value ?: data
        Log.d("GogoAnime", "resolved realUrl: $realUrl")

        Log.d("GogoAnime", "calling app.get on $realUrl")
        val mainHtml = app.get(realUrl, timeout = 60).text
        Log.d("GogoAnime", "=== MAIN PAGE HTML (first 2000) ===\n${mainHtml.take(2000)}\n=== END MAIN PAGE HTML ===")
        val doc = Jsoup.parse(mainHtml)

        val serverLinks = doc.select(".anime_muti_link a[data-video]")
        Log.d("GogoAnime", "Found ${serverLinks.size} server links with selector .anime_muti_link a[data-video]")

        if (serverLinks.isEmpty()) {
            Log.d("GogoAnime", "WARN: serverLinks empty, trying alternate selectors")
            Log.d("GogoAnime", "HTML around muti_link: ${mainHtml.takeLast(500)}")
            val altLinks = doc.select("a[data-video]")
            Log.d("GogoAnime", "alt selector a[data-video]: ${altLinks.size} links")
            val anyIframe = doc.select("iframe")
            Log.d("GogoAnime", "any iframes on page: ${anyIframe.size}")
            anyIframe.forEach { Log.d("GogoAnime", "  iframe src: ${it.attr("src")}") }
            return false
        }

        val serverUrls = mutableListOf<Pair<String, String>>()

        for ((idx, link) in serverLinks.withIndex()) {
            val label = link.select("i, span").text().trim().ifBlank { link.text().trim() }
            val rel = link.attr("rel")
            val videoHtml = link.attr("data-video")
            val iframeSrc = Jsoup.parse(videoHtml).selectFirst("iframe")?.attr("src")
            Log.d("GogoAnime", "Server[$idx] label='$label' rel='$rel' videoHtml='${videoHtml.take(200)}' iframeSrc='$iframeSrc'")
            if (iframeSrc != null) {
                val type = if (rel == "1") "sub" else "dub"
                val serverLabel = "$label ($type)"
                serverUrls.add(Pair(serverLabel, iframeSrc))
            } else {
                Log.d("GogoAnime", "WARN: no iframe extracted from data-video for server[$idx]")
            }
        }

        Log.d("GogoAnime", "Collected ${serverUrls.size} server URLs")
        serverUrls.forEach { (label, url) -> Log.d("GogoAnime", "  $label -> $url") }
        if (serverUrls.isEmpty()) {
            Log.d("GogoAnime", "WARN: no server URLs collected, returning false")
            return false
        }

        for ((serverLabel, streamingUrl) in serverUrls) {
            try {
                Log.d("GogoAnime", "Processing streaming URL $streamingUrl")
                val streamHtml = app.get(streamingUrl, timeout = 30).text
                Log.d("GogoAnime", "=== STREAM PAGE HTML (first 1200) ===\n${streamHtml.take(1200)}\n=== END STREAM PAGE ===")
                val streamDoc = Jsoup.parse(streamHtml)

                val allIframes = streamDoc.select("iframe")
                Log.d("GogoAnime", "iframes on stream page: ${allIframes.size}")
                allIframes.forEach { Log.d("GogoAnime", "  iframe: src='${it.attr("src")}'") }

                val megaplayIframe = streamDoc.selectFirst("iframe")?.attr("src")
                Log.d("GogoAnime", "Found megaplay iframe src: $megaplayIframe")

                if (megaplayIframe != null) {
                    Log.d("GogoAnime", "fetching megaplay iframe: $megaplayIframe")
                    val megaplayHtml = app.get(megaplayIframe, timeout = 30, referer = streamingUrl).text
                    Log.d("GogoAnime", "=== MEGAPLAY HTML (full) ===\n${megaplayHtml}\n=== END MEGAPLAY HTML ===")
                    var foundVideo = false

                    val dataId = Regex("""<div[^>]*id="megaplay-player"[^>]*data-id="([^"]+)"""")
                        .find(megaplayHtml)?.groupValues?.get(1)
                    Log.d("GogoAnime", "Megaplay data-id: $dataId")

                    if (dataId != null) {
                        val sourcesUrl = "https://megaplay.buzz/stream/getSources?id=$dataId"
                        Log.d("GogoAnime", "Fetching sources from: $sourcesUrl")
                        try {
                            val sourcesJson = app.get(sourcesUrl, timeout = 30, referer = megaplayIframe).text
                            Log.d("GogoAnime", "Sources response: ${sourcesJson.take(1000)}")
                            val json = org.json.JSONObject(sourcesJson)
                            val sourcesList = mutableListOf<org.json.JSONObject>()
                            json.optJSONArray("sources")?.let { arr ->
                                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { sourcesList.add(it) }
                            }
                            json.optJSONObject("sources")?.let { sourcesList.add(it) }
                            Log.d("GogoAnime", "Sources parsed: ${sourcesList.size} entries")
                            for (source in sourcesList) {
                                val file = source.optString("file", "")
                                val type = source.optString("type", "")
                                val label = source.optString("label", "")
                                if (file.isNotBlank()) {
                                    Log.d("GogoAnime", "Found source: file=$file type=$type label=$label")
                                    val linkType = when {
                                        file.contains(".m3u8") || type == "hls" -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                    callback(newExtractorLink(name, "$serverLabel ${label.ifBlank { serverLabel }}", file, linkType) {
                                        this.referer = "https://megaplay.buzz/"
                                        this.quality = when {
                                            label.contains("1080") -> Qualities.P1080.value
                                            label.contains("720") -> Qualities.P720.value
                                            label.contains("480") -> Qualities.P480.value
                                            label.contains("360") -> Qualities.P360.value
                                            else -> Qualities.P720.value
                                        }
                                    })
                                    foundVideo = true
                                }
                            }

                            val tracks = json.optJSONArray("tracks")
                            if (tracks != null) {
                                for (i in 0 until tracks.length()) {
                                    val track = tracks.optJSONObject(i)
                                    if (track != null) {
                                        val file = track.optString("file", "")
                                        val kind = track.optString("kind", "")
                                        val language = track.optString("language", "").ifBlank { track.optString("label", "") }
                                        if (file.isNotBlank() && (kind == "captions" || kind == "subtitles")) {
                                            Log.d("GogoAnime", "Found subtitle track: file=$file lang=$language")
                                            subtitleCallback(newSubtitleFile(
                                                lang = if (language.isNotBlank()) language else "Unknown",
                                                url = file
                                            ))
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("GogoAnime", "Error fetching sources: ${e.message}")
                        }
                    } else {
                        Log.d("GogoAnime", "No megaplay data-id found in HTML")
                    }

                    if (!foundVideo) {
                        Log.d("GogoAnime", "No m3u8 via API for $serverLabel, WebView fallback for $megaplayIframe")
                        loadExtractor(megaplayIframe, realUrl, subtitleCallback, callback)
                    }
                } else {
                    Log.d("GogoAnime", "WARN: no iframe found on stream page at all")
                }
            } catch (e: Exception) {
                Log.d("GogoAnime", "Error processing $streamingUrl: ${e.message}")
                Log.d("GogoAnime", "Stack: ${e.stackTraceToString()}")
            }
        }

        Log.d("GogoAnime", "=== loadLinks returning true ===")
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
