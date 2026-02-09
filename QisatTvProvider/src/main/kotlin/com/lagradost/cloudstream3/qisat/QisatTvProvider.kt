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
import android.util.Base64
import java.net.URI

class QisatTvProvider : MainAPI() {
    data class ServerItem(val name: String, val id: String)

    private fun Element.extractPoster(): String? {
        return this.selectFirst("[style*=background-image]")?.attr("style")?.let {
            Regex("""url\((['"]?)(.*?)\1\)""").find(it)?.groupValues?.get(2)
        }
            ?: this.attr("data-bg")
            ?: this.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
    }

    private fun String.normalizeNumbers(): String {
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        val latin = "0123456789"
        var result = this
        for (i in arabic.indices) {
            result = result.replace(arabic[i], latin[i])
        }
        return result
    }

    // New domain: qesset.com
    override var mainUrl = "https://qesset.com"
    override var name = "Qisat"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

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


            val poster = element.extractPoster()?.let { fixUrl(it) }

            val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

            if (url.contains("/movie") || url.contains("/film")) {
                items.add(newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                })
            } else {
                items.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                })
            }
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = true),
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
            
            val poster = element.extractPoster()?.let { fixUrl(it) }

            if (url.contains("/movie") || url.contains("/film")) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    // ---------- Load Series/Episode ----------
    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        var finalUrl = url

        // Fix for missing episodes: If we are on an episode page, fetch the series page
        // Fix for missing episodes: If we are on an episode page, fetch the series page
        val seriesLink = doc.selectFirst("div.singleSeries .info h2 a")?.attr("href")
        if (!seriesLink.isNullOrBlank() && !url.contains("/series/")) {
             try {
                 finalUrl = fixUrl(seriesLink)
                 doc = app.get(finalUrl).document
             } catch (e: Exception) {
                 // Fallback to original doc
             }
        }

        val title = doc.selectFirst("h1.title")?.text()?.trim() ?: doc.title().substringBefore(" - ").trim()

        val poster = doc.selectFirst("div.cover div.imgBg, div.cover div.img")?.extractPoster()?.let { fixUrl(it) } 
            ?: doc.selectFirst("div.cover img")?.extractPoster()?.let { fixUrl(it) }

        val plot = doc.selectFirst("div.story p")?.text()?.trim() ?: doc.selectFirst("div.story")?.text()?.trim()
        val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

        // Check if page has episodes list (new structure check)
        val isSeries = doc.select("article.postEp, div.postEp, a.ep-item").isNotEmpty() ||
                       doc.select("body").hasClass("single-series") || 
                       finalUrl.contains("/series/")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // QisatTv apparently loads all episodes in the grid on the single-series page
            // But we should still check for pagination just in case
            
            // Initial parsing of episodes on the current page
            val pagedEps = doc.select("article.postEp a, div.postEp a, a.ep-item").mapNotNull { a ->
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst(".title")?.text()?.trim() ?: a.attr("title").trim()
                val cleanName = epName.normalizeNumbers()

                val epNum = a.selectFirst(".episodeNum span:last-child")?.text()?.normalizeNumbers()?.toIntOrNull()
                    ?: Regex("""(?:^|\s)(?:episode|ep|حلقة|الحلقة)\s*(\d+)(?:\s|$)""", RegexOption.IGNORE_CASE)
                        .find(cleanName)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()

                val poster = a.selectFirst("div.imgBg, div.imgSer")?.extractPoster()?.let { fixUrl(it) }
                    ?: a.selectFirst("img")?.extractPoster()?.let { fixUrl(it) }

                val displayName = if (epNum != null) "الحلقة $epNum" else epName

                newEpisode(epUrl) {
                    this.name = displayName
                    this.episode = epNum
                    this.season = 1
                    this.posterUrl = poster
                }
            }
            episodes.addAll(pagedEps)

            val finalEpisodes = episodes.distinctBy { it.data }.sortedWith(
                compareByDescending<Episode> { it.episode ?: 0 }
                    .thenByDescending { it.name }
            ).toMutableList()

            finalEpisodes.forEachIndexed { index, ep ->
                if (ep.episode == null) {
                    ep.episode = index + 1
                }
            }

            return newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } 
        
        // Movie Logic
        return newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------- Load Links (Video Sources) ----------
    // ---------- Load Links (Video Sources) ----------
    // ---------- Load Links (Video Sources) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        var playerUrl = doc.selectFirst("iframe[src*='qesen.net/watch'], iframe[src*='qesset.com/watch']")?.attr("src")
            ?: doc.selectFirst("a.watch-btn")?.attr("href")
            ?: doc.selectFirst("a.fullscreen-clickable")?.attr("href")
            ?: doc.selectFirst("a[href*='post=']")?.attr("href")

        playerUrl = fixUrl(playerUrl ?: return false)

        var hasLinks = false
        val servers = mutableListOf<ServerItem>()

        // Try parsing from "post" param first (no network call needed)
        val postParam = Regex("post=([^&]+)").find(playerUrl)?.groupValues?.get(1)
        if (postParam != null) {
            try {
                val decoded = try {
                    String(Base64.decode(postParam, Base64.URL_SAFE or Base64.NO_WRAP))
                } catch (e: Exception) {
                    String(Base64.decode(postParam, Base64.DEFAULT))
                }
                // safe parse json
                val json = try {
                    AppUtils.parseJson<Map<String, Any>>(decoded)
                } catch (e: Exception) {
                    null
                }
                val serverList = json?.get("servers") as? List<Map<String, String>>
                serverList?.forEach {
                    val name = it["name"] ?: ""
                    val id = it["id"] ?: ""
                    if (name.isNotBlank() && id.isNotBlank()) {
                        servers.add(ServerItem(name, id))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback to fetching page if no servers found via param
        if (servers.isEmpty()) {
            try {
                val response = app.get(playerUrl).text
                val extractedServers =
                    Regex("""var\s+servers\s*=\s*(\[.*?\])""").find(response)?.groupValues?.get(1)
                if (!extractedServers.isNullOrBlank()) {
                    try {
                        AppUtils.parseJson<List<ServerItem>>(extractedServers).let { servers.addAll(it) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (servers.isEmpty()) return false

        // Parallel fetch servers
        // We construct the "direct" embed URLs based on the server ID and name mapping
        // This bypasses the need to visit the blocked/maintenance 'watch' page
        coroutineScope {
            servers.mapNotNull { server ->
                val serverName = server.name.lowercase()
                val serverId = server.id
                var fixedUrl: String? = null

                when {
                    serverName.contains("arab") || serverName.contains("المشغل الأول") -> {
                        fixedUrl = "https://v.turkvearab.com/embed-$serverId.html"
                    }
                    serverName.contains("estream") || serverName.contains("turk") || serverName.contains("المشغل الثاني") -> {
                        fixedUrl = "https://arabveturk.com/embed-$serverId.html"
                    }
                    serverName.contains("ok") || serverName.contains("المشغل الرابع") -> {
                        fixedUrl = "https://ok.ru/videoembed/$serverId"
                    }
                    serverName.contains("pro") || serverName.contains("المشغل الخامس") -> {
                        fixedUrl = "https://w.larhu.website/play.php?id=$serverId"
                    }
                    serverName.contains("red") || serverName.contains("المشغل السادس") -> {
                        fixedUrl = "https://iplayerhls.com/e/$serverId"
                    }
                    serverName.contains("express") || serverName.contains("المشغل الثالث") -> {
                        fixedUrl = serverId // The ID is the direct link for express
                    }
                    // Add more mappings as discovered
                    else -> {
                         // Fallback: Try to use the ID as a direct URL if it looks like one
                         if (serverId.startsWith("http")) {
                             fixedUrl = serverId
                         }
                    }
                }

                if (fixedUrl != null) {
                    Pair(server, fixedUrl)
                } else {
                    null
                }
            }.map { (server, url) ->
                async {
                    val fixedInnerSrc = url // It is already fixed
                     if (fixedInnerSrc.contains("cdnplus.cyou") || fixedInnerSrc.contains("cdnplus.online")) {
                        // Specific handling for CDNPlus
                        try {
                            val cdnResponse = app.get(fixedInnerSrc, referer = playerUrl).text
                            if (cdnResponse.contains("eval(function(p,a,c,k,e,d)")) {
                                 val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""").find(cdnResponse)?.value
                                 if (packed != null) {
                                     val unpacked = runJS(packed.replace("eval", ""))
                                     val m3u8 = Regex("""file:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                                     if (m3u8 != null) {
                                         callback(
                                             newExtractorLink(
                                                 "CdnPlus",
                                                 "${server.name} (CdnPlus)",
                                                 m3u8,
                                                 ExtractorLinkType.M3U8
                                             ) {
                                                 this.referer = fixedInnerSrc
                                                 this.quality = Qualities.Unknown.value
                                             }
                                         )
                                         hasLinks = true
                                     }
                                 }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        // Try to load extractors
                        val extractedLinks = mutableListOf<ExtractorLink>()
                        try {
                             loadExtractor(fixedInnerSrc, referer = playerUrl, subtitleCallback = subtitleCallback) { link ->
                                extractedLinks.add(link)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        
                        extractedLinks.forEach { link ->
                            if (link.name.startsWith(link.source)) {
                                val type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                callback(
                                    newExtractorLink(
                                        link.source,
                                        "${server.name} - ${link.name}",
                                        link.url,
                                        type
                                    ) {
                                        this.referer = link.referer
                                        this.quality = link.quality
                                    }
                                )
                            } else {
                                callback(link)
                            }
                            hasLinks = true
                        }
                    }
                }
            }.awaitAll()
        }

        return hasLinks
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
