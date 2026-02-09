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

        val episodes = mutableListOf<Episode>()

        if (isSeries) {
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
        } 
        
        // Logical correction: Only treat as movie if it was supposed to be a series but no episodes were found.
        // This handles cases where a page is tagged as series but has no episodes (fallback to movie).
        // We DO NOT convert single-episode series to movies anymore, as per Cloudstream best practices.
        if (isSeries && episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
        
        if (isSeries) {
            val finalEpisodes = episodes
                .distinctBy { it.data }
                .sortedBy { it.episode ?: Int.MAX_VALUE }
                .toMutableList()

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
        var directUrlFound = false

        // 1. Direct Iframe Check (Episode Page)
        // Some/Old episodes might have the iframe directly on the episode page
        doc.selectFirst("div.getEmbed iframe, iframe[src*='embed'], iframe[src*='video']")?.attr("src")?.let { iframeUrl ->
            val fixedUrl = fixUrl(iframeUrl)
            if (fixedUrl.isNotBlank() && !fixedUrl.contains("facebook") && !fixedUrl.contains("twitter")) {
                loadExtractor(fixedUrl, referer = data, subtitleCallback = subtitleCallback, callback = callback)
                directUrlFound = true
            }
        }

        val servers = mutableListOf<ServerItem>()

        // 1.5 Check for server list on the Episode Page itself (Old Episodes often have it here)
        doc.select("ul.serversList li").forEach { li ->
            val name = li.attr("data-name").trim()
            val id = li.attr("data-server").trim()
            
            if (name.isNotBlank()) {
                if (id.isNotBlank()) {
                     servers.add(ServerItem(name, id))
                } else {
                    val codeLink = li.selectFirst("code a")?.attr("href")?.trim()
                    if (!codeLink.isNullOrEmpty()) {
                         callback(newExtractorLink(name, name, codeLink, ExtractorLinkType.VIDEO))
                         directUrlFound = true
                    }
                }
            }
        }

        var playerUrl = doc.selectFirst("iframe[src*='qesen.net/watch'], iframe[src*='qesset.com/watch']")?.attr("src")
            ?: doc.selectFirst("a.watch-btn")?.attr("href")
            ?: doc.selectFirst("a.fullscreen-clickable")?.attr("href")
            ?: doc.selectFirst("a[href*='post=']")?.attr("href")

        // If no player URL found, and we haven't found any direct links or servers, we can't do much more.
        if (playerUrl == null) {
             return directUrlFound || servers.isNotEmpty()
        }

        playerUrl = fixUrl(playerUrl)

        // 2. Try parsing from "post" param first (Fast Path for New Episodes)
        // Only if we don't have servers yet? Or maybe "post" param has *better* servers? 
        // Usually if serversList is present on page, it's the source of truth. 
        // But "post" param is used when serversList is NOT on the page.
        if (servers.isEmpty()) {
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
                    // ignore
                }
            }
        }

        // 3. Fallback to fetching page (Old Episodes or failed Post param)
        // Only fetch if we still don't have servers.
        if (servers.isEmpty()) {
            try {
                val responseDoc = app.get(playerUrl).document
                val responseText = responseDoc.html()

                // 3a. Check for direct iframe on the player page
                responseDoc.selectFirst("div.getEmbed iframe")?.attr("src")?.let { iframeUrl ->
                     val fixedUrl = fixUrl(iframeUrl)
                     if (fixedUrl.isNotBlank()) {
                         loadExtractor(fixedUrl, referer = playerUrl, subtitleCallback = subtitleCallback, callback = callback)
                         directUrlFound = true
                     }
                }

                // 3b. Check for server list (ul.serversList)
                responseDoc.select("ul.serversList li").forEach { li ->
                    val name = li.attr("data-name").trim()
                    val id = li.attr("data-server").trim()
                    
                    if (name.isNotBlank()) {
                        if (id.isNotBlank()) {
                             servers.add(ServerItem(name, id))
                        } else {
                            val codeLink = li.selectFirst("code a")?.attr("href")?.trim()
                            if (!codeLink.isNullOrEmpty()) {
                                 callback(newExtractorLink(name, name, codeLink, ExtractorLinkType.VIDEO))
                                 directUrlFound = true
                            }
                        }
                    }
                }

                // 3c. Check for JS variable (Legacy fallback)
                if (servers.isEmpty()) {
                    val extractedServers =
                        Regex("""var\s+servers\s*=\s*(\[.*?\])""").find(responseText)?.groupValues?.get(1)
                    if (!extractedServers.isNullOrBlank()) {
                        try {
                            AppUtils.parseJson<List<ServerItem>>(extractedServers).let { servers.addAll(it) }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        if (servers.isEmpty() && !directUrlFound) return false

        val serverResults = coroutineScope {
            val results = servers.mapNotNull { server ->
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
                        fixedUrl = serverId 
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
                    var found = false
                    val fixedInnerSrc = url 
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
                                         found = true
                                     }
                                 }
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    } else {
                        // Try to load extractors
                        val extractedLinks = mutableListOf<ExtractorLink>()
                        try {
                             loadExtractor(fixedInnerSrc, referer = playerUrl, subtitleCallback = subtitleCallback) { link ->
                                extractedLinks.add(link)
                            }
                        } catch (e: Exception) {
                            // ignore
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
                            found = true
                        }
                    }
                    found
                }
            }.awaitAll()
            
            results.any { it }
        }
        
        return directUrlFound || serverResults
    }

    private fun runJS(script: String): String {
        val rhino = org.mozilla.javascript.Context.enter()
        rhino.initSafeStandardObjects()
        rhino.optimizationLevel = -1
        val scope: org.mozilla.javascript.Scriptable = rhino.initSafeStandardObjects()
        val result: String
        try {
            val resultObj = rhino.evaluateString(scope, script, "JavaScript", 1, null)
            result = org.mozilla.javascript.Context.toString(resultObj)
        } catch (e: Exception) {
            return ""
        } finally {
            org.mozilla.javascript.Context.exit()
        }
        return result
    }
} 
