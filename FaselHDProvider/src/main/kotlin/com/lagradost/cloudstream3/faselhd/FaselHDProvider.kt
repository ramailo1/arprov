package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.CommonActivity.showToast
import android.widget.Toast


class FaselHDProvider : MainAPI() {
    override var mainUrl = "https://web13018x.faselhdx.bid"
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/most_recent" to "المضاف حديثاَ",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/anime" to "الأنمي",
        "$mainUrl/tvshows" to "البرامج التلفزيونية",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime-movies" to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url, headers = defaultHeaders).document
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.postInner > div.h1")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val img = selectFirst("div.imgdiv-class img")
            ?: selectFirst("div.postInner img")
            ?: selectFirst("img")

        var posterUrl = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("data-image").ifEmpty {
                        it.attr("data-srcset").ifEmpty { it.attr("src") }
                    }
                }
            }
        }

        if (!posterUrl.isNullOrEmpty() && posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val quality = selectFirst("span.quality")?.text()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = defaultHeaders).document
        return doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders).document

        val title = doc.selectFirst("div.title")?.text() ?: doc.selectFirst("title")?.text() ?: ""
        val poster = doc.selectFirst("div.posterImg img")?.attr("src")
        val desc = doc.selectFirst("div.singleDesc p")?.text()

        val tags = doc.select("div#singleList .col-xl-6").map { it.text() }
        val year = tags.find { it.contains("سنة الإنتاج") }?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = tags.find { it.contains("مدة") }?.substringAfter(":")?.trim()
        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        val isSeries = doc.select("div.epAll").isNotEmpty() || doc.select("div.seasonLoop").isNotEmpty()

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            for (ep in doc.select("div#epAll a")) {
                val epTitle = ep.text()
                val epUrl = ep.attr("href")
                val epNumber = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNumber
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document

        // Extract the player iframe URL
        val playerIframe = doc.selectFirst("iframe[name=\"player_iframe\"], iframe[src*=\"video_player\"]")
        val playerUrl = playerIframe?.absUrl("src")

        if (!playerUrl.isNullOrEmpty()) {
            showToast("يرجى الانتظار بضع ثوانٍ حتى يبدأ المشغل...", Toast.LENGTH_SHORT)
            try {
                // Method 1: Use WebViewResolver to intercept m3u8 network requests
                // The player uses obfuscated JS to generate video URLs, so we need WebView
                val extractedUrls = mutableSetOf<String>()
                
                // Script to extract data-url attributes from quality buttons after JS executes
                val extractionScript = """
                    (function() {
                        var urls = [];
                        var buttons = document.querySelectorAll('.hd_btn[data-url]');
                        for (var i = 0; i < buttons.length; i++) {
                            var btn = buttons[i];
                            var url = btn.getAttribute('data-url');
                            var quality = btn.innerText.trim();
                            if (url) urls.push(quality + '|||' + url);
                        }
                        // Also check for videoSrc global variable
                        if (window.videoSrc) urls.push('Auto|||' + window.videoSrc);
                        return JSON.stringify(urls);
                    })()
                """.trimIndent()
                
                // Create WebViewResolver that intercepts m3u8 URLs AND extracts from DOM
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8"""),
                    additionalUrls = listOf(
                        Regex("""scdns\.io.*\.m3u8"""),
                        Regex("""master\.m3u8"""),
                        Regex("""playlist\.m3u8""")
                    ),
                    userAgent = USER_AGENT,
                    script = extractionScript,
                    scriptCallback = { result ->
                        // Parse the JSON array of "quality|||url" strings
                        try {
                            val cleaned = result.trim('"').replace("\\\"", "\"")
                            if (cleaned.startsWith("[")) {
                                val urlList = cleaned.removeSurrounding("[", "]")
                                    .split("\",\"")
                                    .map { it.trim('"') }
                                    .filter { it.contains("|||") }
                                
                                for (entry in urlList) {
                                    val parts = entry.split("|||")
                                    if (parts.size == 2) {
                                        extractedUrls.add(entry)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // e.printStackTrace()
                        }
                    },
                    timeout = 15000L
                )
                
                // Resolve using WebView
                val (mainRequest, additionalRequests) = resolver.resolveUsingWebView(
                    requestCreator("GET", playerUrl, headers = defaultHeaders + mapOf("Referer" to data))
                )
                
                // Process intercepted m3u8 URLs from network requests
                val allRequests = listOfNotNull(mainRequest) + additionalRequests
                for (request in allRequests) {
                    val videoUrl = request.url.toString()
                    if (videoUrl.contains(".m3u8") && extractedUrls.none { it.endsWith(videoUrl) }) {
                        val qualityText = when {
                            videoUrl.contains("1080") -> "1080p"
                            videoUrl.contains("720") -> "720p"
                            videoUrl.contains("480") -> "480p"
                            videoUrl.contains("360") -> "360p"
                            videoUrl.contains("master") -> "Auto"
                            else -> "Unknown"
                        }
                        extractedUrls.add("$qualityText|||$videoUrl")
                    }
                }
                
                // Give WebView script time to execute
                delay(500)
                
                // Emit all extracted URLs as ExtractorLinks
                for (entry in extractedUrls) {
                    val parts = entry.split("|||")
                    if (parts.size == 2) {
                        val qualityText = parts[0]
                        val videoUrl = parts[1]
                        
                        if (videoUrl.contains(".m3u8")) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "$name - $qualityText",
                                    videoUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = playerUrl
                                    this.quality = getQualityInt(qualityText)
                                }
                            )
                        }
                    }
                }
                
                // Method 2: Fallback - try regex extraction from raw HTML (in case WebView fails)
                if (extractedUrls.isEmpty()) {
                    val playerResponse = app.get(playerUrl, referer = data, headers = defaultHeaders).text
                    val cleanedResponse = playerResponse.replace(Regex("""['\"]\s*\+\s*['"]"""), "")
                    val m3u8Pattern = Regex("""https?://[^\s"']+(?:scdns\.io)[^\s"']*\.m3u8""")
                    val qualityPattern = Regex("""(\d+p)""")
                    
                    for (match in m3u8Pattern.findAll(cleanedResponse)) {
                        val videoUrl = match.value
                        val qualityMatch = qualityPattern.find(videoUrl)
                        val qualityText = qualityMatch?.value ?: "Auto"
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "$name - $qualityText",
                                videoUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerUrl
                                this.quality = getQualityInt(qualityText)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // e.printStackTrace()
            }
        }

        return true
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            quality.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
