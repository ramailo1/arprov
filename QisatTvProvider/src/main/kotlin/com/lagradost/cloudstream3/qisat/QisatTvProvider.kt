package com.lagradost.cloudstream3.qisat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

class QisatTvProvider : MainAPI() {
    override var mainUrl = "https://www.qisat.tv"
    override var name = "Qisat"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ---------- Main Page ----------
    override val mainPage = mainPageOf(
        "/" to "أحدث الحلقات",
        "/turkish-series/" to "المسلسلات التركية",
        "/turkish-movies/" to "افلام تركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(fixUrl(request.data)).document
        val items = mutableListOf<SearchResponse>()

        val selector = if (request.data == "/") "div.episode-block, div.block-post, a[href*=/episode/]" else "div.block-post, a[href*=/series/], a[href*=/movie/]"
        
        doc.select(selector).forEach { element ->
            val a = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@forEach
            val url = fixUrl(a.attr("href"))
            val title = element.selectFirst(".title, h3")?.text()?.trim() ?: a.attr("title").trim()
            if (title.isEmpty()) return@forEach

            val poster = element.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

            items.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            })
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = false),
            hasNext = false
        )
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.block-post").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val url = fixUrl(a.attr("href"))
            val title = element.selectFirst(".title, h3")?.text()?.trim() ?: a.attr("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val poster = element.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // ---------- Load Series/Episode ----------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title")?.text()?.trim() ?: doc.title().substringBefore(" - ").trim()
        val poster = doc.selectFirst("div.poster img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { fixUrl(it) } ?: doc.selectFirst("div.cover img")?.let { img ->
             img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { fixUrl(it) }

        val plot = doc.selectFirst("div.story p")?.text()?.trim() ?: doc.selectFirst("div.story")?.text()?.trim()
        val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

        // Check if page has episodes list (even if it's an episode URL)
        val episodesTab = doc.select("#episodes-tab, .episodes-list").firstOrNull()
        
        if (url.contains("/series/") || episodesTab != null) {
            val episodes = doc.select("div.episodes-list a, div.block-post a, #episodes-tab a.block-post, #episodes-tab div.block-post a").mapNotNull { a ->
                val epUrl = fixUrl(a.attr("href"))
                if (!epUrl.contains("/episode/")) return@mapNotNull null
                
                val epName = a.selectFirst(".title")?.text()?.trim() ?: a.text().trim()
                val epNum = a.selectFirst(".episodeNum span:last-child")?.text()?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epName)?.value?.toIntOrNull()

                val epPoster = a.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }?.let { fixUrl(it) }

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            }.distinctBy { it.data }.sortedByDescending { it.episode }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            }
        } 
        
        // Single Episode or Movie Logic
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------- Load Links (Video Sources) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Extract the player iframe directly
        val playerIframe = doc.selectFirst("iframe[src*='/albaplayer/']")
        val playerUrl = playerIframe?.attr("src")?.let { fixUrl(it) } ?: return false

        // Determine base player URL for server switching
        // e.g., https://w.shadwo.pro/albaplayer/slug
        val playerBaseUrl = playerUrl.substringBefore("?")

        // Server mapping
        val servers = listOf(
            "Main" to 0, // Sometimes 0 is valid
            "CDNPlus" to 1,
            "MP4Plus" to 2,
            "AnaFast" to 3,
            "Vidoba" to 4,
            "VidSpeed" to 5,
            "Larhu" to 6,
            "VK" to 7,
            "OK" to 8
        )

        servers.amap { (_, index) ->
            // Use concurrent requests for speed
            val serverUrl = "$playerBaseUrl?serv=$index"
            try {
                // Determine referer: The player URL usually expects the episode page as referer
                val response = app.get(serverUrl, referer = data).text
                
                // Extract inner iframe (the actual video host)
                val innerIframeSrc = Jsoup.parse(response).selectFirst("iframe")?.attr("src")
                
                if (!innerIframeSrc.isNullOrBlank()) {
                     val fixedInnerSrc = fixUrl(innerIframeSrc)
                     if (fixedInnerSrc.contains("cdnplus.cyou") || fixedInnerSrc.contains("cdnplus.online")) {
                         // Specific handling for CDNPlus which uses packed JS
                         val cdnResponse = app.get(fixedInnerSrc, referer = serverUrl).text
                         val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""").find(cdnResponse)?.value
                         if (packed != null) {
                             val unpacked = runJS(packed.replace("eval", ""))
                             val m3u8 = Regex("""file:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                             if (m3u8 != null) {
                                 callback(
                                     newExtractorLink(
                                         "CdnPlus",
                                         "CdnPlus",
                                         m3u8,
                                         ExtractorLinkType.M3U8
                                     ) {
                                         this.referer = fixedInnerSrc
                                         this.quality = Qualities.Unknown.value
                                     }
                                 )
                             }
                         }
                     } else {
                         loadExtractor(fixedInnerSrc, referer = data, subtitleCallback = subtitleCallback, callback = callback)
                     }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }

        return true
    }

    private fun runJS(script: String): String {
        val rhino = Context.enter()
        rhino.initSafeStandardObjects()
        rhino.optimizationLevel = -1
        val scope: Scriptable = rhino.initSafeStandardObjects()
        val result: String
        try {
            val resultObj = rhino.evaluateString(scope, script, "JavaScript", 1, null)
            result = Context.toString(resultObj)
        } catch (e: Exception) {
            return ""
        } finally {
            Context.exit()
        }
        return result
    }
}
