package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import okhttp3.Interceptor
import okhttp3.Response

class FaselHDProvider : MainAPI() {
    override var lang = "ar"
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime,
    )

    // Base domain — will be resolved dynamically on first use
    private val baseDomain = "faselhdx.bid"
    override var mainUrl = "https://web3126x.$baseDomain"

    // Resolve the real (post-redirect) mainUrl
    private suspend fun getRealMainUrl(): String {
        return try {
            val resp = app.get(mainUrl, allowRedirects = true)
            val finalUrl = resp.url.trimEnd('/')
            // Extract scheme + host only
            val uri = java.net.URL(finalUrl)
            "${uri.protocol}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }

    // OkHttp interceptor to add Referer for poster images
    private val refererInterceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            return if (url.contains(baseDomain)) {
                val newRequest = request.newBuilder()
                    .header("Referer", mainUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        // Regex patterns for m3u8 extraction from player page JS
        val M3U8_REGEX = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
        val SOURCES_REGEX = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""")
        val PLAYER_TOKEN_REGEX = Regex("""video_player\?player_token=[^"'\s]+""")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val url = this.attr("href").takeIf { it.isNotEmpty() } ?: return null
        val title = this.select("img").attr("alt").ifEmpty {
            this.select(".entry-title, h3, .title").text()
        }
        // Poster: use data-src first (lazy-loaded), fallback to src
        val poster = this.select("img").let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }.takeIf { it.isNotEmpty() }

        val type = if (url.contains("/episode")) TvType.TvSeries else TvType.Movie
        return newAnimeSearchResponse(title, url, type) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Resolve real domain first
        mainUrl = getRealMainUrl()

        val document = app.get(
            mainUrl,
            headers = mapOf("Referer" to mainUrl, "User-Agent" to USER_AGENT)
        ).document

        val sections = document.select(".home-section, .widget, section").mapNotNull { section ->
            val title = section.select(".section-title, h2, h3").firstOrNull()?.text()
                ?: return@mapNotNull null
            val items = section.select("article a, .entry-box a, .thumb a").mapNotNull {
                it.toSearchResult()
            }
            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(sections, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = getRealMainUrl()
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(
            url,
            headers = mapOf("Referer" to mainUrl, "User-Agent" to USER_AGENT)
        ).document
        return document.select("article a, .entry-box a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Ensure we use the real redirected domain for all requests
        mainUrl = getRealMainUrl()

        val fixedUrl = if (url.contains(baseDomain)) url
        else url.replace(Regex("https?://[^/]+"), mainUrl)

        val document = app.get(
            fixedUrl,
            headers = mapOf("Referer" to mainUrl, "User-Agent" to USER_AGENT)
        ).document

        val title = document.select(".entry-title, h1.title, h1").firstOrNull()?.text()
            ?: document.title()

        // Poster: og:image is most reliable for FaselHD
        val poster = document.select("meta[property=og:image]").attr("content").ifEmpty {
            document.select(".entry-thumbnail img, .poster img").let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        }

        val plot = document.select(".entry-content p, .story, .description").firstOrNull()?.text()

        val isEpisode = fixedUrl.contains("/episode")
        val isSeries = fixedUrl.contains("/series") || fixedUrl.contains("/tvshow")

        if (isEpisode || isSeries) {
            // Get season/episodes list
            val episodes = mutableListOf<Episode>()

            // Episodes links on page
            val episodeLinks = document.select("#DivEpisodesList a, .episodes-list a, .ep-list a")
            if (episodeLinks.isNotEmpty()) {
                episodeLinks.forEachIndexed { idx, el ->
                    val epUrl = el.attr("href")
                    val epTitle = el.text().trim()
                    val epNum = epTitle.filter { it.isDigit() }.toIntOrNull() ?: (idx + 1)
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNum
                        }
                    )
                }
            } else if (isEpisode) {
                // Single episode page
                episodes.add(
                    newEpisode(fixedUrl) {
                        this.name = title
                    }
                )
            }

            return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.plot = plot
            }
        } else {
            // Movie
            return newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        mainUrl = getRealMainUrl()

        val fixedData = if (data.contains(baseDomain)) data
        else data.replace(Regex("https?://[^/]+"), mainUrl)

        // Step 1: Load the movie/episode page and find the player_token URL
        // The page uses JS to inject an iframe into #vihtml with /video_player?player_token=XXX
        // We use WebViewResolver to let JS run, then intercept the player_token request

        val playerTokenUrl = try {
            // Use WebViewResolver on the page URL to intercept the video_player token URL
            WebViewResolver(
                Regex("""video_player\?player_token=""")
            ).resolveUsingWebView(
                requestCreator(
                    "GET", fixedData,
                    referer = mainUrl,
                    headers = mapOf("User-Agent" to USER_AGENT)
                )
            ).first?.url
        } catch (e: Exception) {
            null
        }

        if (playerTokenUrl != null) {
            // Step 2: Now WebView-resolve the player_token page to get the m3u8
            val m3u8Url = try {
                WebViewResolver(
                    Regex("""\.m3u8"""),
                    // Also try catching direct mp4
                    additionalUrls = listOf(Regex("""\.mp4"""))
                ).resolveUsingWebView(
                    requestCreator(
                        "GET",
                        playerTokenUrl,
                        referer = fixedData,
                        headers = mapOf("User-Agent" to USER_AGENT)
                    )
                ).first?.url
            } catch (e: Exception) {
                null
            }

            if (m3u8Url != null) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        referer = playerTokenUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = m3u8Url.contains(".m3u8"),
                    )
                )
                return true
            }
        }

        // Fallback: Try to extract player_token from page HTML directly
        // (some servers pre-render the iframe src)
        val doc = app.get(
            fixedData,
            headers = mapOf("Referer" to mainUrl, "User-Agent" to USER_AGENT)
        ).document

        // Look for iframe with player_token
        val iframeSrc = doc.select("iframe[src*=video_player]").attr("src").ifEmpty {
            doc.select("iframe[data-src*=video_player]").attr("data-src")
        }

        if (iframeSrc.isNotEmpty()) {
            val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc
            else "$mainUrl$iframeSrc"

            // Get the player page HTML (may need WebView for CF)
            val m3u8 = try {
                WebViewResolver(Regex("""\.m3u8""")).resolveUsingWebView(
                    requestCreator(
                        "GET", fullIframeUrl,
                        referer = fixedData,
                        headers = mapOf("User-Agent" to USER_AGENT)
                    )
                ).first?.url
            } catch (e: Exception) {
                null
            }

            if (m3u8 != null) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8,
                        referer = fullIframeUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                    )
                )
                return true
            }
        }

        // Last fallback: search raw HTML for m3u8
        val rawHtml = doc.html()
        val m3u8Match = M3U8_REGEX.find(rawHtml) ?: SOURCES_REGEX.find(rawHtml)
        if (m3u8Match != null) {
            val m3u8Url = m3u8Match.groupValues[1]
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = fixedData,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                )
            )
            return true
        }

        return false
    }
}
