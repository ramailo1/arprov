package com.lagradost.cloudstream3.qisat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class QisatTvProvider : MainAPI() {
    // New domain: qesset.com
    override var mainUrl = "https://qesset.com"
    override var name = "Qisat"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ---------- Main Page ----------
    override val mainPage = mainPageOf(
        "/yeni-bolumler/" to "أحدث الحلقات",
        "/diziler/" to "المسلسلات التركية",
        "/category/filmler/" to "افلام تركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(fixUrl(request.data)).document
        val items = mutableListOf<SearchResponse>()

        val selector = "a[href*=\"-episode-\"], div.post-card a, div.block-post, a[href*=/series/], a[href*=/movie/]"
        
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
        var doc = app.get(url).document
        var finalUrl = url

        // Fix for missing episodes: If we are on an episode page, fetch the series page
        val seriesLink = doc.selectFirst("div.singleSeries .info h2 a")?.attr("href")
        if (!seriesLink.isNullOrBlank() && url.contains("/episode/")) {
             try {
                 finalUrl = fixUrl(seriesLink)
                 doc = app.get(finalUrl).document
             } catch (e: Exception) {
                 // Fallback to original doc
             }
        }

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
        
        if (finalUrl.contains("/series/") || episodesTab != null) {
            val episodes = mutableListOf<Episode>()
            var page = 1
            var hasNext = true

            while (hasNext) {
                val pageUrl = if (page == 1) finalUrl else "${finalUrl.removeSuffix("/")}/page/$page/"
                val pageDoc = try {
                    app.get(pageUrl).document
                } catch (e: Exception) {
                    break
                }

                val pagedEps = pageDoc.select("div.episodes-list a, div.block-post a, #episodes-tab a.block-post, #episodes-tab div.block-post a").mapNotNull { a ->
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
                }

                if (pagedEps.isEmpty()) {
                    hasNext = false
                } else {
                    episodes.addAll(pagedEps)
                    // Check for next page link to avoid unnecessary requests
                    val nextLink = pageDoc.selectFirst("a.next.page-numbers")
                    if (nextLink == null) {
                        hasNext = false
                    } else {
                        page++
                    }
                }
                
                // Safety break to prevent infinite loops if something goes wrong
                if (page > 50) break 
            }

            val finalEpisodes = episodes.distinctBy { it.data }.sortedByDescending { it.episode }

            if (finalEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, finalEpisodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            }
        } 
        
        // Single Episode or Movie Logic
        return newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) {
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
        
        // Extract the player iframe
        val playerIframe = doc.selectFirst("iframe[src*='qesen.net/watch'], iframe[src*='/albaplayer/']")
        val playerUrl = playerIframe?.attr("src")?.let { fixUrl(it) } ?: return false

        if (playerUrl.contains("watch?post=")) {
            val base64Data = playerUrl.substringAfter("watch?post=").substringBefore("&")
            val decoded = try {
                val json = String(android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT), Charsets.UTF_8)
                // Result: {"codeDaily":"...","servers":[{"name":"...","id":"..."}],"postID":"..."}
                json
            } catch (e: Exception) {
                null
            }

            if (decoded != null) {
                // We'll extract servers using Regex to avoid heavy JSON libraries if possible
                val serverPattern = """\{"name":"([^"]+)","id":"([^"]+)"\}""".toRegex()
                serverPattern.findAll(decoded).forEach { match ->
                    val name = match.groupValues[1]
                    val id = match.groupValues[2]
                    
                    // Most IDs are keys for extractors or absolute URLs
                    if (id.startsWith("http")) {
                        loadExtractor(id, data, subtitleCallback, callback)
                    } else {
                        // Some common hosts used by Qisat
                        val hostUrl = when (name.lowercase()) {
                            "arab hd", "pro hd" -> "https://mixdrop.co/e/$id"
                            "estream" -> "https://estream.to/$id"
                            "ok" -> "https://ok.ru/videoembed/$id"
                            else -> null
                        }
                        hostUrl?.let { loadExtractor(it, data, subtitleCallback, callback) }
                    }
                }
                return true
            }
        }

        // Determine base player URL for server switching
        // e.g., https://w.shadwo.pro/albaplayer/slug
        // val playerBaseUrl = playerUrl.substringBefore("?")

        // Fetch the player page to get dynamic server list
        val playerDoc = app.get(playerUrl, referer = data).document
        val servers = playerDoc.select("ul.aplr-menu li a.aplr-link").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.attr("href")
            if (href.isBlank() || href == "javascript:void(0)" || href.contains("javascript:")) return@mapNotNull null
            name to fixUrl(href)
        }

        if (servers.isEmpty()) {
             // Fallback to main player URL if no list found (though unlikely based on site structure)
             // functionality for single server handling remains similar to loop logic below
             // but strictly speaking we expect the list.
             // We can at least try the current playerUrl as a fallback
             val serverUrl = playerUrl
             
             // ... extract logic for single server ...
             try {
                val response = app.get(serverUrl, referer = data).text
                val innerIframeSrc = Jsoup.parse(response).selectFirst("iframe")?.attr("src")
                if (!innerIframeSrc.isNullOrBlank()) {
                     val fixedInnerSrc = fixUrl(innerIframeSrc)
                     if (fixedInnerSrc.contains("cdnplus.cyou") || fixedInnerSrc.contains("cdnplus.online")) {
                         // Specific handling for CDNPlus
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
            } catch (e: Exception) { }
        }

        coroutineScope {
            servers.map { (name, serverUrl) ->
                async {
                    // Use concurrent requests for speed
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
                                                 "$name (CdnPlus)",
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
            }.awaitAll()
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
