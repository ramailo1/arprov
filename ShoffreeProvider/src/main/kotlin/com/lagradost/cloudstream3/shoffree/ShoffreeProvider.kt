@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.shoffree

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume

import kotlin.text.Charsets
import com.lagradost.cloudstream3.utils.getQualityFromName

class ShoffreeProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://shoffree.sbs"
    override var name = "Shoffree"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val STREEM_DECRYPT_KEY = "LlyopEA5QYg1xmZ4"

    private val categoryUrls = mapOf(
        "movies" to "$mainUrl/movies",
        "series" to "$mainUrl/series",
        "anime" to "$mainUrl/anime",
        "wrestling" to "$mainUrl/wrestling",
        "theater" to "$mainUrl/theater",
        "recent" to "$mainUrl/recent"
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.value?.toIntOrNull()
    }

    private fun String.cleanHtml(): String = this.replace(Regex("<[^>]*>"), "").trim()

    private fun extractSeasonNumber(label: String): Int {
        return when {
            label.contains("الأول") -> 1
            label.contains("الثاني") -> 2
            label.contains("الثالث") -> 3
            label.contains("الرابع") -> 4
            label.contains("الخامس") -> 5
            label.contains("السادس") -> 6
            label.contains("السابع") -> 7
            label.contains("الثامن") -> 8
            label.contains("التاسع") -> 9
            label.contains("العاشر") -> 10
            else -> Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: 1
        }
    }

    private fun extractQualityFromUrl(url: String): Int? {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> null
        }
    }

    private fun extractId(url: String): String =
        Regex("""/(movie|serie|watch/[^/]+)/(\d+)/""").find(url)?.groupValues?.get(2) ?: ""

    private fun extractSlug(url: String): String =
        Regex("""/\d+/([^/?#]+)""").find(url)?.groupValues?.get(1) ?: ""

    private fun parseWatchUrl(url: String): Triple<String?, String?, String?> {
        return when {
            url.contains("/watch/movie/") -> Triple("movie", Regex("""/watch/movie/(\d+)/""").find(url)?.groupValues?.get(1), null)
            url.contains("/watch/") && url.contains("/episode/") ->
                Triple("serie",
                    Regex("""/watch/(\d+)/episode/""").find(url)?.groupValues?.get(1)
                        ?: Regex("""/serie/(\d+)/""").find(url)?.groupValues?.get(1),
                    Regex("""/episode/(\d+)/""").find(url)?.groupValues?.get(1))
            url.contains("/watch/wrestling/") -> Triple("wrestling", Regex("""/watch/wrestling/(\d+)/""").find(url)?.groupValues?.get(1), null)
            url.contains("/watch/theater/") -> Triple("theater", Regex("""/watch/theater/(\d+)/""").find(url)?.groupValues?.get(1), null)
            else -> Triple(null, null, null)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recent" to "المُضاف حديثاً",
        "$mainUrl/movies" to "الأفلام",
        "$mainUrl/series" to "المسلسلات",
        "$mainUrl/anime" to "أنمي وكرتون",
        "$mainUrl/wrestling" to "مصارعة حرة",
        "$mainUrl/theater" to "المسرحيات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("ShoffreeProvider: getMainPage page=$page request=${request.name} data=${request.data}")
        val url = when {
            page == 1 -> request.data
            else -> "${request.data}/page/$page"
        }
        println("ShoffreeProvider: Fetching URL: $url")
        try {
            val doc = app.get(url).document
            val items = parseVideoGrid(doc)
            println("ShoffreeProvider: Parsed ${items.size} items from ${request.name} page $page")
            return newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            println("ShoffreeProvider: ERROR in getMainPage: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("ShoffreeProvider: search query=$query")
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/search?query=$encodedQuery"
            println("ShoffreeProvider: Searching URL: $url")
            val doc = app.get(url).document
            val results = parseSearchResults(doc)
            println("ShoffreeProvider: Search found ${results.size} results")
            return results
        } catch (e: Exception) {
            println("ShoffreeProvider: ERROR in search: ${e.message}")
            return emptyList()
        }
    }

    private fun parseJsonLd(doc: Document): List<SearchResponse> {
        println("ShoffreeProvider: Attempting JSON-LD parsing")
        val script = doc.selectFirst("script[type='application/ld+json']") ?: return emptyList()
        
        try {
            val json = JSONObject(script.data())
            val graph = json.getJSONArray("@graph")
            println("ShoffreeProvider: JSON-LD graph has ${graph.length()} objects")
            
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until graph.length()) {
                val obj = graph.getJSONObject(i)
                if (obj.getString("@type") == "CollectionPage") {
                    val mainEntity = obj.optJSONObject("mainEntity") ?: continue
                    val itemList = mainEntity.optJSONArray("itemListElement") ?: continue
                    println("ShoffreeProvider: Found ItemList with ${itemList.length()} items")
                    
                    for (j in 0 until itemList.length()) {
                        val listItem = itemList.getJSONObject(j)
                        val item = listItem.optJSONObject("item") ?: continue
                        val type = item.optString("@type", "")
                        val title = item.optString("name", "")
                        val url = item.optString("url", "")
                        val poster = item.optString("image", "")
                        if (title.isBlank() || url.isBlank()) continue
                        
                        val tvType = when (type) {
                            "Movie" -> TvType.Movie
                            "TVSeries" -> TvType.TvSeries
                            "TVEpisode" -> TvType.TvSeries
                            else -> TvType.Movie
                        }
                        
                        items.add(newMovieSearchResponse(title, url, tvType) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            println("ShoffreeProvider: JSON-LD parsed ${items.size} items")
            return items
        } catch (e: Exception) {
            println("ShoffreeProvider: JSON-LD parsing failed: ${e.message}")
            return emptyList()
        }
    }

    private fun parseVideoGrid(doc: Document): List<SearchResponse> {
        println("ShoffreeProvider: Parsing video grid HTML")
        val items = mutableListOf<SearchResponse>()
        
        val cards = doc.select(".video-card, .shoffree-row, article[aria-label='post'], .video-result")
        println("ShoffreeProvider: Found ${cards.size} cards via primary selectors")
        
        if (cards.isEmpty()) {
            val fallbackCards = doc.select("article a[href], .video-grid a[href], .video-card a[href]")
            println("ShoffreeProvider: Fallback found ${fallbackCards.size} links")
            fallbackCards.forEach { link ->
                val card = link.parent()?.parent() ?: return@forEach
                parseCard(card, items)
            }
        } else {
            cards.forEach { card -> parseCard(card, items) }
        }
        
        val jsonLdItems = parseJsonLd(doc)
        if (jsonLdItems.isNotEmpty()) {
            println("ShoffreeProvider: Adding ${jsonLdItems.size} items from JSON-LD")
            items.addAll(jsonLdItems)
        }
        
        return items.distinctBy { it.url }
    }

    private fun parseCard(card: Element, items: MutableList<SearchResponse>) {
        val link = card.selectFirst("a[href]")?.attr("href") ?: return
        if (link.contains("/news/") || link.contains("coming-soon") || link.contains("قريبا")) return
        
        val title = card.selectFirst(".video-title, .shoffree-title, h2, h3, h4, [aria-label='title']")?.text()
            ?: card.selectFirst("img")?.attr("alt") ?: ""
        
        if (title.isBlank() || title.equals("logo", true)) return
        
        val img = card.selectFirst("img")
        val poster = when {
            img != null && img.attr("data-src").isNotBlank() && !img.attr("data-src").startsWith("data:") -> img.attr("data-src")
            img != null && img.attr("src").isNotBlank() && !img.attr("src").startsWith("data:") -> img.attr("src")
            img != null && img.attr("data-src").isNotBlank() -> img.attr("data-src")
            img != null -> img.attr("src")
            else -> ""
        }
        println("ShoffreeProvider: parseCard poster=$poster title=\"${title.take(40)}\"")
        
        val channelName = card.selectFirst(".channel-name")?.text()?.lowercase() ?: ""
        val type = when {
            link.contains("/watch/wrestling/") -> "wrestling"
            link.contains("/watch/theater/") -> "theater"  
            link.contains("/serie/") -> "series"
            link.contains("/movie/") -> "movie"
            link.contains("/watch/") && link.contains("/episode/") -> "series"
            else -> "unknown"
        }

        val isAnime = (type == "series" || type == "movie" || type == "theater" || type == "wrestling") && (
            title.contains("انمي", ignoreCase = true) ||
            title.contains("أنمي", ignoreCase = true) ||
            channelName.contains("anime") ||
            channelName.contains("انمي") ||
            channelName.contains("أنمي")
        )

        val tvType = when {
            (type == "movie" || type == "theater" || type == "wrestling") && isAnime -> TvType.Anime
            type == "movie" || type == "theater" || type == "wrestling" -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }

        println("ShoffreeProvider: parseCard type=$type anime=$isAnime tvType=$tvType title=\"${title.take(50)}\"")
        
        items.add(newMovieSearchResponse(title, link, tvType) {
            this.posterUrl = poster
        })
    }

    private fun parseSearchResults(doc: Document): List<SearchResponse> {
        println("ShoffreeProvider: Parsing search results")
        val items = parseVideoGrid(doc)
        
        val chips = doc.select(".chips-container .chip")
        println("ShoffreeProvider: Search page has ${chips.size} category chips")
        
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        println("ShoffreeProvider: load url=$url")
        try {
            val doc = app.get(url).document
            
            return when {
                url.contains("/movie/") -> loadMovie(doc, url)
                url.contains("/serie/") -> loadSeries(doc, url)
                url.contains("/watch/") -> loadWatchPage(doc, url)
                else -> {
                    println("ShoffreeProvider: Unsupported URL pattern: $url")
                    throw ErrorLoadingException("Unsupported URL: $url")
                }
            }
        } catch (e: Exception) {
            println("ShoffreeProvider: ERROR in load: ${e.message}")
            throw e
        }
    }

    private suspend fun loadMovie(doc: Document, url: String): LoadResponse {
        println("ShoffreeProvider: Loading movie page")
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull() ?: ""
        fun getPosterUrl(): String {
            val ogImage = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
            if (ogImage.isNotBlank() && 
                !ogImage.contains("logo", true) && 
                !ogImage.contains("favicon", true) && 
                !ogImage.contains("icon", true) &&
                !ogImage.contains("/assets/", true) &&
                !ogImage.contains("/images/", true)
            ) {
                return ogImage
            }
            val otherPoster = doc.selectFirst("img.poster, .poster img, .video-poster img")?.attr("data-src")
                ?: doc.selectFirst("img.poster, .poster img, .video-poster img")?.attr("src")
                ?: doc.selectFirst("article img[data-src], .video-card img[data-src]")?.attr("data-src")
                ?: doc.selectFirst("article img[src], .video-card img[src]")?.attr("src")
                ?: ""
            if (otherPoster.isNotBlank() && 
                !otherPoster.contains("logo", true) && 
                !otherPoster.contains("favicon", true) && 
                !otherPoster.contains("icon", true) &&
                !otherPoster.contains("/assets/", true) &&
                !otherPoster.contains("/images/", true)
            ) {
                return otherPoster
            }
            return ""
        }
        val poster = fixUrlNull(getPosterUrl()) ?: ""
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val id = extractId(url)
        val slug = extractSlug(url)
        val watchUrl = if (id.isNotBlank() && slug.isNotBlank()) {
            "$mainUrl/watch/movie/$id/$slug"
        } else {
            url
        }
        
        println("ShoffreeProvider: Movie: $title ($year), watchUrl: $watchUrl")
        
        return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun loadSeries(doc: Document, url: String): LoadResponse {
        println("ShoffreeProvider: loadSeries for $url")
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull() ?: ""
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content")) ?: ""
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val isAnime = title.contains("انمي", ignoreCase = true) ||
                      title.contains("أنمي", ignoreCase = true) ||
                      doc.select(".channel-name, .genre, .category, .video-info span").any {
                          it.text().contains("anime", ignoreCase = true) ||
                          it.text().contains("انيميشن", ignoreCase = true)
                      }
        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
        println("ShoffreeProvider: Type=${if (isAnime) "Anime" else "TvSeries"}")

        val seasonCards = doc.select("#seasons-area .season-card-unified, #seasons-area .season-card, .seasons-area a, .seasons-list a, .season-list-item, .season-card")
        println("ShoffreeProvider: Found ${seasonCards.size} season card(s)")

        val allEpisodes = mutableListOf<Episode>()

        if (seasonCards.isEmpty()) {
            val firstEpLink = doc.selectFirst("a[href*='/watch/'][href*='/episode/'], a[href*='/watch/']:not([href*='/movie/']):not([href*='/wrestling/']):not([href*='/theater/'])")
            if (firstEpLink != null) {
                val watchUrl = firstEpLink.attr("href")
                println("ShoffreeProvider: Single season — first episode link: $watchUrl")
                try {
                    val watchDoc = app.get(watchUrl).document
                    val eps = extractEpisodesFromSidebar(watchDoc, title)
                    eps.forEach { it.season = 1 }
                    allEpisodes.addAll(eps)
                } catch (e: Exception) {
                    println("ShoffreeProvider: Failed to fetch watch page sidebar: ${e.message}")
                }
            } else {
                println("ShoffreeProvider: No episode links found on series page")
            }
        } else {
            for (card in seasonCards) {
                val link = card.selectFirst("a[href]")?.attr("href") ?: continue
                val label = card.selectFirst(".s-year")?.text() ?: ""
                val seasonNum = extractSeasonNumber(label)
                println("ShoffreeProvider: Season $seasonNum ($label) -> $link")

                try {
                    val seasonDoc = if (link == url) doc else app.get(link).document
                    val epLink = seasonDoc.selectFirst("a[href*='/watch/'][href*='/episode/'], a[href*='/watch/']:not([href*='/movie/']):not([href*='/wrestling/']):not([href*='/theater/'])")
                    if (epLink != null) {
                        val watchDoc = app.get(epLink.attr("href")).document
                        val eps = extractEpisodesFromSidebar(watchDoc, title)
                        eps.forEach { it.season = seasonNum }
                        allEpisodes.addAll(eps)
                        println("ShoffreeProvider: Season $seasonNum -> ${eps.size} episodes")
                    } else {
                        println("ShoffreeProvider: No episode links for season $seasonNum")
                    }
                } catch (e: Exception) {
                    println("ShoffreeProvider: Failed to load season $seasonNum: ${e.message}")
                }
            }
        }

        val sorted = allEpisodes.sortedBy { (it.season ?: 1) * 10000 + (it.episode ?: 0) }
        println("ShoffreeProvider: \"$title\" total=${sorted.size} episodes across seasons")

        return newTvSeriesLoadResponse(title, url, tvType, sorted) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun extractEpisodesFromSidebar(doc: Document, seriesTitle: String = ""): List<Episode> {
        println("ShoffreeProvider: Extracting episodes from sidebar for \"$seriesTitle\"")
        val episodes = mutableListOf<Episode>()
        
        doc.select("#playlist-list .playlist-item[data-ep-id]").forEach { epLink ->
            val epUrl = epLink.attr("href").ifBlank {
                println("ShoffreeProvider: Skipping sidebar item with no href")
                return@forEach
            }
            val epNum = epLink.attr("data-ep").toIntOrNull()
            if (epNum == null) {
                println("ShoffreeProvider: Skipping sidebar item with no data-ep: ${epUrl.takeLast(60)}")
                return@forEach
            }
            val epId = epLink.attr("data-ep-id")
            val epTitle = epLink.selectFirst("h4, .item-meta h4")?.text() ?: "الحلقة $epNum"
            val epPoster = epLink.selectFirst("img")?.attr("src") ?: ""
            
            println("ShoffreeProvider: Episode #$epNum (id=$epId): \"$epTitle\" -> ${epUrl.takeLast(60)}")
            
            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum
                this.season = 1
                this.posterUrl = epPoster
            })
        }
        
        println("ShoffreeProvider: Sidebar yielded ${episodes.size} episodes for \"$seriesTitle\"")
        return episodes.sortedBy { it.episode ?: 0 }
    }

    private suspend fun loadWatchPage(doc: Document, url: String): LoadResponse {
        println("ShoffreeProvider: loadWatchPage for $url")
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull() ?: ""
        fun getPosterUrl(): String {
            val ogImage = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
            if (ogImage.isNotBlank() && 
                !ogImage.contains("logo", true) && 
                !ogImage.contains("favicon", true) && 
                !ogImage.contains("icon", true) &&
                !ogImage.contains("/assets/", true) &&
                !ogImage.contains("/images/", true)
            ) {
                return ogImage
            }
            
            // Try extracting from player background-image style
            val playerStyle = doc.selectFirst(".video-aspect-ratio, .fake-player, [style*=background-image]")?.attr("style") ?: ""
            val bgUrl = Regex("""url\(['"]?([^'"]+)['"]?\)""").find(playerStyle)?.groupValues?.get(1) ?: ""
            if (bgUrl.isNotBlank() && 
                !bgUrl.contains("logo", true) && 
                !bgUrl.contains("favicon", true) && 
                !bgUrl.contains("icon", true) &&
                !bgUrl.contains("/assets/", true) &&
                !bgUrl.contains("/images/", true)
            ) {
                return bgUrl
            }

            val otherPoster = doc.selectFirst("img.poster, .poster img, .video-poster img")?.attr("data-src")
                ?: doc.selectFirst("img.poster, .poster img, .video-poster img")?.attr("src")
                ?: doc.selectFirst("article img[data-src], .video-card img[data-src]")?.attr("data-src")
                ?: doc.selectFirst("article img[src], .video-card img[src]")?.attr("src")
                ?: ""
            if (otherPoster.isNotBlank() && 
                !otherPoster.contains("logo", true) && 
                !otherPoster.contains("favicon", true) && 
                !otherPoster.contains("icon", true) &&
                !otherPoster.contains("/assets/", true) &&
                !otherPoster.contains("/images/", true)
            ) {
                return otherPoster
            }
            return ""
        }
        val poster = fixUrlNull(getPosterUrl()) ?: ""
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content") ?: ""

        if (url.contains("/episode/")) {
            println("ShoffreeProvider: Episode watch page, extracting sidebar episodes")
            val episodes = extractEpisodesFromSidebar(doc, title)

            val isAnime = title.contains("انمي", ignoreCase = true) ||
                          title.contains("أنمي", ignoreCase = true)
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
            println("ShoffreeProvider: ${episodes.size} episodes from sidebar, type=${if (isAnime) "Anime" else "TvSeries"}")

            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val isMovie = url.contains("/movie/")
        val isTheater = url.contains("/theater/")
        val isWrestling = url.contains("/wrestling/")
        val tvType = if (isMovie || isTheater || isWrestling) TvType.Movie else TvType.TvSeries
        println("ShoffreeProvider: ${if (tvType == TvType.Movie) "Movie/Theater/Wrestling" else "Series"} watch page: \"$title\"")

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun decryptShoffreePlayer(payload: String, key: String): String {
        println("ShoffreeProvider: Decrypting payload length=${payload.length}, key=$key")
        val hexPairs = payload.chunked(2)
        val output = StringBuilder()
        for (i in hexPairs.indices) {
            val charByte = hexPairs[i].toInt(16)
            val keyChar = key[i % key.length].toInt()
            output.append((charByte xor keyChar).toChar())
        }
        return output.toString()
    }

    private suspend fun extractVideoSourcesFromPlayer(playerHtml: String): List<ExtractorLink> {
        return coroutineScope {
            val links = mutableListOf<ExtractorLink>()
            val doc = Jsoup.parse(playerHtml)
            
            println("ShoffreeProvider: Extracting video sources from player HTML (length=${playerHtml.length})")
            
            val sourceElements = doc.select("source[src], video[src], iframe[src]")
            val sourceTasks = sourceElements.map { el ->
                async {
                    val src = el.attr("src").ifBlank { el.attr("data-src") }
                    if (src.isNotBlank()) {
                        val quality = extractQualityFromUrl(src) ?: Qualities.Unknown.value
                        val link = newExtractorLink(
                            name, 
                            "Shoffree", 
                            src, 
                            if (src.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                        println("ShoffreeProvider: Found video source: $src quality=$quality")
                        link
                    } else null
                }
            }
            links.addAll(sourceTasks.awaitAll().filterNotNull())
            
            val scripts = doc.select("script").map { it.data() }.joinToString(" ")
            Regex("""https?://[^"']+\.(?:m3u8|mp4)""").findAll(scripts).forEach { match ->
                val src = match.value
                val quality = extractQualityFromUrl(src) ?: Qualities.Unknown.value
                val link = newExtractorLink(
                    name, 
                    "Shoffree", 
                    src, 
                    if (src.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = mainUrl
                }
                println("ShoffreeProvider: Found video source in script: $src quality=$quality")
                links.add(link)
            }
            
            doc.select("[data-src], [data-url], [data-video]").forEach { el ->
                val src = el.attr("data-src").ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-video") }
                if (src.isNotBlank() && (src.contains("http") || src.startsWith("//"))) {
                    val fullSrc = if (src.startsWith("//")) "https:$src" else src
                    val quality = extractQualityFromUrl(fullSrc) ?: Qualities.Unknown.value
                    val link = newExtractorLink(
                        name, 
                        "Shoffree", 
                        fullSrc, 
                        if (fullSrc.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = mainUrl
                    }
                    println("ShoffreeProvider: Found video source in data attr: $fullSrc quality=$quality")
                    links.add(link)
                }
            }
            
            return@coroutineScope links.distinctBy { it.url }.sortedByDescending { it.quality }
        }
    }

    private suspend fun loadLinksViaWebView(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        println("ShoffreeProvider: WebView fallback for $url")
        try {
            val resolver = WebViewResolver(Regex("shoffree\\.sbs|streem\\.watch"))
            val request = requestCreator("GET", url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to mainUrl
            ))
            
            // Resolve Cloudflare challenge via WebView
            resolver.resolveUsingWebView(request)
            
            // After WebView finishes, make fresh request with solved cookies
            val cfRes = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to mainUrl
            ))
            val doc = cfRes.document
            
            println("ShoffreeProvider: WebViewResolver secondary request status=${cfRes.code} for $url")
            
            if (cfRes.isSuccessful) {
                val sources = extractVideoSourcesFromPlayer(doc.html())
                if (sources.isNotEmpty()) {
                    sources.forEach { callback(it) }
                    println("ShoffreeProvider: WebView found ${sources.size} sources")
                    return true
                }
            }
            println("ShoffreeProvider: WebView returned no sources")
            return false
        } catch (e: Exception) {
            println("ShoffreeProvider: WebView fallback failed: ${e.message}")
            return false
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("ShoffreeProvider: loadLinks data=$data")
        
        val streamUrl = when {
            data.contains("/watch/") -> data
            data.contains("/movie/") -> {
                val id = extractId(data)
                val slug = extractSlug(data)
                if (id.isNotBlank() && slug.isNotBlank()) "$mainUrl/watch/movie/$id/$slug" else data
            }
            data.contains("/serie/") -> data
            else -> data
        }
        
        println("ShoffreeProvider: Resolved stream URL: $streamUrl")
        
        if (streamUrl.contains("/watch/")) {
            val (contentType, contentId, episodeId) = parseWatchUrl(streamUrl)
            println("ShoffreeProvider: Parsed watch URL - type=$contentType, id=$contentId, episode=$episodeId")

            if (contentType != null && contentId != null) {
                // POST key_token to establish session for streem endpoint
                println("ShoffreeProvider: POST key_token=$contentId to establish session")
                try {
                    val postResp = app.post(
                        streamUrl,
                        data = mapOf("key_token" to contentId),
                        headers = mapOf("Referer" to mainUrl, "X-Requested-With" to "XMLHttpRequest")
                    )
                    println("ShoffreeProvider: POST status=${postResp.code}, session established")
                } catch (e: Exception) {
                    println("ShoffreeProvider: POST session failed (non-fatal): ${e.message}")
                }

                val streemUrl = when (contentType) {
                    "movie" -> "$mainUrl/streem/watch/movie/$contentId"
                    "serie" -> {
                        if (episodeId != null) "$mainUrl/streem/watch/serie/$contentId/$episodeId" else null
                    }
                    "wrestling" -> "$mainUrl/streem/watch/wrestling/$contentId"
                    else -> null
                }

                if (streemUrl != null) {
                    val success = tryDirectDecryption(streemUrl, streamUrl, callback)
                    if (success) {
                        println("ShoffreeProvider: Direct decryption succeeded for $streemUrl")
                        return true
                    }
                    println("ShoffreeProvider: Direct decryption failed for $streemUrl")
                    
                    val wvSuccess = extractM3u8ViaWebView(streemUrl, streamUrl, callback)
                    if (wvSuccess) {
                        println("ShoffreeProvider: extractM3u8ViaWebView succeeded for $streemUrl")
                        return true
                    }
                    println("ShoffreeProvider: extractM3u8ViaWebView failed for $streemUrl")
                } else {
                    println("ShoffreeProvider: No streem URL for type=$contentType id=$contentId")
                }
            }
        }
        
        println("ShoffreeProvider: Loading watch page for iframe extraction")
        try {
            val watchDoc = app.get(streamUrl).document
            val iframeSrc = watchDoc.selectFirst("iframe[src]")?.attr("src") 
                ?: watchDoc.selectFirst("meta[property='og:video:url']")?.attr("content")
                ?: watchDoc.selectFirst("meta[itemprop='embedUrl']")?.attr("content")
            
            if (iframeSrc != null) {
                val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                if (fullIframeUrl.contains(mainUrl.removePrefix("https://").removePrefix("http://"))) {
                    println("ShoffreeProvider: Skipping internal iframe URL (same domain): $fullIframeUrl")
                } else {
                    println("ShoffreeProvider: Found iframe: $fullIframeUrl")
                    loadExtractor(fullIframeUrl, subtitleCallback, callback)
                    return true
                }
            }
            
            val directSources = extractVideoSourcesFromPlayer(watchDoc.html())
            if (directSources.isNotEmpty()) {
                directSources.forEach { callback(it) }
                println("ShoffreeProvider: Found ${directSources.size} direct sources in watch page")
                return true
            }
        } catch (e: Exception) {
            println("ShoffreeProvider: Watch page load failed: ${e.message}")
        }
        
        println("ShoffreeProvider: All strategies failed, trying WebView fallback")
        return loadLinksViaWebView(streamUrl, callback)
    }

    private suspend fun tryDirectDecryption(streemUrl: String, watchUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            println("ShoffreeProvider: Trying direct decryption for $streemUrl")
            val response = app.get(streemUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to mainUrl
            ))
            println("ShoffreeProvider: GET streem status=${response.code} length=${response.text.length}")
            val responseText = response.text

            if (responseText.isBlank()) {
                println("ShoffreeProvider: EMPTY response from streem endpoint")
                return false
            }

            println("ShoffreeProvider: Response preview: ${responseText.take(300)}")

            val decodedHtml = decryptResponse(responseText)
            if (decodedHtml == null) {
                println("ShoffreeProvider: decryptResponse returned null — check encryption pattern match")
                return false
            }

            if (decodedHtml.isBlank()) {
                println("ShoffreeProvider: Decrypted HTML is blank despite decryption succeeding")
                return false
            }

            println("ShoffreeProvider: Decrypted HTML length: ${decodedHtml.length}")
            println("ShoffreeProvider: Decrypted preview: ${decodedHtml.take(300)}")

            val directSuccess = extractFromDecryptedHtml(decodedHtml, streemUrl, callback)
            if (directSuccess) {
                println("ShoffreeProvider: Direct extraction succeeded for $streemUrl")
                return true
            }
            
            println("ShoffreeProvider: Direct extraction failed, trying AJAX API via /sources/ endpoint")
            val apiSuccess = fetchSourcesViaApi(decodedHtml, watchUrl, callback)
            if (apiSuccess) {
                println("ShoffreeProvider: AJAX API succeeded for $streemUrl")
                return true
            }
            
            println("ShoffreeProvider: All direct strategies failed for $streemUrl")
            return false
        } catch (e: Exception) {
            println("ShoffreeProvider: Direct decryption failed: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private suspend fun decryptResponse(response: String): String? {
        // Log what the response actually looks like for debugging
        val hasDecryptFunction = Regex("""\(function \(_key, _payload\) \{""").containsMatchIn(response)
        val hasAltDecrypt = Regex("""_decrypt\(_payload,\s*_key\)""").containsMatchIn(response)
        val hasShfEmb = response.contains("Shf_v2_embd")
        val hasScriptTags = response.contains("<script")
        val hasIframe = response.contains("<iframe")
        val hasKeyToken = response.contains("key_token")
        val hasVarKey = Regex("""var\s+_payload\s*=""").containsMatchIn(response)
        val hasVarKey2 = Regex("""var\s+_key\s*=""").containsMatchIn(response)
        println("ShoffreeProvider: decryptResponse patterns: hasDecryptFn=$hasDecryptFunction altDecrypt=$hasAltDecrypt ShfEmb=$hasShfEmb scripts=$hasScriptTags iframes=$hasIframe keyToken=$hasKeyToken varPayload=$hasVarKey varKey=$hasVarKey2")

        val scriptMatch = Regex("""\(function \(_key, _payload\) \{""").find(response)
        if (scriptMatch == null) {
            println("ShoffreeProvider: No primary decryption function found")
            if (hasAltDecrypt) println("ShoffreeProvider: But alt decrypt pattern found — trying alt path")
            else {
                println("ShoffreeProvider: No known encryption pattern detected in response")
                println("ShoffreeProvider: Available patterns: ShfEmb=$hasShfEmb scripts=$hasScriptTags iframes=$hasIframe")
                return null
            }
        }

        val payloadMatch = Regex("""\("([^"]+)",\s*"([^"]+)"\)""").find(response)
        if (payloadMatch != null) {
            val key = payloadMatch.groupValues[1]
            val payload = payloadMatch.groupValues[2]
            println("ShoffreeProvider: Primary pattern — key=$key payload_len=${payload.length}")
            val decryptedHex = decryptShoffreePlayer(payload, key)
            println("ShoffreeProvider: XOR-decrypted hex length=${decryptedHex.length}")
            val decodedBytes = Base64.decode(decryptedHex, Base64.DEFAULT)
            println("ShoffreeProvider: Base64 decoded bytes=${decodedBytes.size}")
            return String(decodedBytes, Charsets.UTF_8)
        }

        val altMatch = Regex("""_decrypt\(_payload,\s*_key\)""").find(response)
        if (altMatch != null) {
            val payloadMatch2 = Regex("""_payload\s*=\s*"([^"]+)""").find(response)
            if (payloadMatch2 != null) {
                val payload = payloadMatch2.groupValues[1]
                println("ShoffreeProvider: Alt pattern — payload_len=${payload.length} key=$STREEM_DECRYPT_KEY")
                val decryptedHex = decryptShoffreePlayer(payload, STREEM_DECRYPT_KEY)
                println("ShoffreeProvider: XOR-decrypted hex length=${decryptedHex.length}")
                val decodedBytes = Base64.decode(decryptedHex, Base64.DEFAULT)
                println("ShoffreeProvider: Base64 decoded bytes=${decodedBytes.size}")
                return String(decodedBytes, Charsets.UTF_8)
            }
            println("ShoffreeProvider: Alt pattern found but no _payload variable detected")
        }

        println("ShoffreeProvider: All decryption patterns exhausted, returning null")
        return null
    }

    private suspend fun extractFromDecryptedHtml(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val directSources = extractVideoSourcesFromPlayer(html)
        if (directSources.isNotEmpty()) {
            directSources.forEach { callback(it) }
            println("ShoffreeProvider: Found ${directSources.size} sources from decrypted HTML")
            return true
        }
        
        println("ShoffreeProvider: No direct sources in decrypted HTML, following iframes")
        return followEmbedIframes(html, referer, callback)
    }

    private suspend fun fetchSourcesViaApi(decodedHtml: String, watchUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val keyMatch = Regex("""\bKEY\s*=\s*'([^']+)'""").find(decodedHtml)
            if (keyMatch == null) {
                println("ShoffreeProvider: No KEY found in decrypted HTML for AJAX API")
                return false
            }
            val key = keyMatch.groupValues[1]
            println("ShoffreeProvider: Extracted KEY=$key for AJAX API")

            val apiUrl = watchUrl.replace("/watch/", "/sources/")
            println("ShoffreeProvider: POST to sources API: $apiUrl")

            val response = app.post(
                apiUrl,
                data = mapOf("key" to key, "server" to "1"),
                headers = mapOf(
                    "Referer" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            println("ShoffreeProvider: Sources API status=${response.code}")

            if (!response.isSuccessful) {
                println("ShoffreeProvider: Sources API returned ${response.code}")
                return false
            }

            val jsonText = response.text
            if (jsonText.isBlank()) {
                println("ShoffreeProvider: Empty sources API response")
                return false
            }
            println("ShoffreeProvider: Sources API response length=${jsonText.length}")
            println("ShoffreeProvider: Sources API response preview: ${jsonText.take(500)}")

            val json = JSONObject(jsonText)

            if (json.optString("server_status") != "online") {
                println("ShoffreeProvider: Server status=${json.optString("server_status")}, checking alternatives")
                println("ShoffreeProvider: Full API JSON dump: $jsonText")
                val moreServer = json.optJSONArray("more_server")
                if (moreServer != null && moreServer.length() > 0) {
                    println("ShoffreeProvider: Got ${moreServer.length()} alternative iframe servers")
                    for (i in 0 until moreServer.length()) {
                        val server = moreServer.getJSONObject(i)
                        val url = server.optString("url", "")
                        if (url.isNotBlank()) {
                            println("ShoffreeProvider: Following alternative server $i: $url")
                            return handleEmbedUrl(url, watchUrl, callback)
                        }
                    }
                }
                return false
            }

            val sources = json.optJSONArray("sources")
            if (sources == null || sources.length() == 0) {
                println("ShoffreeProvider: No sources array in API response")
                return false
            }

            println("ShoffreeProvider: Found ${sources.length()} sources in API response")

            var found = false
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val file = source.optString("file", "")
                if (file.isBlank()) continue

                val label = source.optString("label", "")
                val quality = when {
                    label.contains("1080") || label.contains("FHD") -> 1080
                    label.contains("720") || label.contains("HD") -> 720
                    label.contains("480") || label.contains("SD") -> 480
                    label.contains("360") -> 360
                    label.contains("240") || label.contains("Mobile") -> 240
                    label.contains("144") -> 144
                    else -> extractQualityFromUrl(file) ?: Qualities.Unknown.value
                }

                val link = newExtractorLink(
                    name, 
                    label.ifBlank { "Shoffree" }, 
                    file, 
                    if (file.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = mainUrl
                }
                println("ShoffreeProvider: Source #$i: file=${file.take(80)} label=$label quality=$quality")
                callback(link)
                found = true
            }

            return found
        } catch (e: Exception) {
            println("ShoffreeProvider: AJAX API failed: ${e.message}")
            return false
        }
    }

    private suspend fun followEmbedIframes(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = Jsoup.parse(html)
        val iframes = doc.select("iframe[src]")
        println("ShoffreeProvider: Found ${iframes.size} iframes in decrypted HTML")
        
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isBlank()) continue
            val fullSrc = if (src.startsWith("//")) "https:$src" else if (src.startsWith("/")) "$mainUrl$src" else src
            println("ShoffreeProvider: Following iframe: $fullSrc")
            
            val success = handleEmbedUrl(fullSrc, referer, callback)
            if (success) return true
        }
        
        return false
    }

    private suspend fun handleEmbedUrl(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            println("ShoffreeProvider: Handling embed URL: $url")
            val responseText = app.get(url, headers = mapOf("Referer" to referer)).text
            
            val decoded = decryptResponse(responseText)
            if (decoded != null) {
                println("ShoffreeProvider: Nested decryption succeeded for $url")
                return extractFromDecryptedHtml(decoded, url, callback)
            }
            
            val sources = extractVideoSourcesFromPlayer(responseText)
            if (sources.isNotEmpty()) {
                sources.forEach { callback(it) }
                println("ShoffreeProvider: Found ${sources.size} sources from embed $url")
                return true
            }
            
            val embedDoc = Jsoup.parse(responseText)
            val nestedIframe = embedDoc.selectFirst("iframe[src]")
            if (nestedIframe != null) {
                val nestedSrc = nestedIframe.attr("src")
                val fullNestedSrc = if (nestedSrc.startsWith("//")) "https:$nestedSrc" else nestedSrc
                println("ShoffreeProvider: Following nested iframe: $fullNestedSrc")
                return handleEmbedUrl(fullNestedSrc, url, callback)
            }
            
            println("ShoffreeProvider: No sources found in embed $url")
            return false
        } catch (e: Exception) {
            println("ShoffreeProvider: Embed URL $url failed: ${e.message}")
            return false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractM3u8ViaWebView(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = kotlinx.coroutines.withTimeoutOrNull(20000) {
        val webView = suspendCancellableCoroutine<WebView?> { continuation ->
            Handler(Looper.getMainLooper()).post {
                fun getContextFromClass(className: String): android.content.Context? {
                    try {
                        val clazz = Class.forName(className)
                        fun dereference(obj: Any?): android.content.Context? {
                            if (obj == null) return null
                            if (obj is android.content.Context) return obj
                            try {
                                if (obj.javaClass.name.contains("WeakReference")) {
                                    val getMethod = obj.javaClass.getMethod("get")
                                    return getMethod.invoke(obj) as? android.content.Context
                                }
                            } catch (e: Throwable) {}
                            return null
                        }
                        try {
                            val field = clazz.getDeclaredField("context").apply { isAccessible = true }
                            val ctx = dereference(field.get(null))
                            if (ctx != null) return ctx
                        } catch (e: Throwable) {}
                        try {
                            val method = clazz.getDeclaredMethod("getContext").apply { isAccessible = true }
                            val ctx = dereference(method.invoke(null))
                            if (ctx != null) return ctx
                        } catch (e: Throwable) {}
                        try {
                            val companionField = clazz.getDeclaredField("Companion").apply { isAccessible = true }
                            val companion = companionField.get(null)
                            if (companion != null) {
                                try {
                                    val field = companion.javaClass.getDeclaredField("context").apply { isAccessible = true }
                                    val ctx = dereference(field.get(companion))
                                    if (ctx != null) return ctx
                                } catch (e: Throwable) {}
                                try {
                                    val method = companion.javaClass.getDeclaredMethod("getContext").apply { isAccessible = true }
                                    val ctx = dereference(method.invoke(companion))
                                    if (ctx != null) return ctx
                                } catch (e: Throwable) {}
                            }
                        } catch (e: Throwable) {}
                    } catch (e: Throwable) {}
                    return null
                }
                
                val context = getContextFromClass("com.lagradost.cloudstream3.CloudStreamApp")
                    ?: getContextFromClass("com.lagradost.cloudstream3.AcraApplication")
                
                if (context == null) {
                    println("ShoffreeProvider: WebView context not found.")
                    continuation.resume(null)
                    return@post
                }
                
                println("ShoffreeProvider: WebView initialized. Preparing to load URL: $url with referer $referer")
                val wv = WebView(context)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                    allowFileAccess = true
                    allowContentAccess = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }

                val probe = ShoffreeProbe { mediaUrl ->
                    println("ShoffreeProvider: Probe resolved media => $mediaUrl")
                    val quality = extractQualityFromUrl(mediaUrl) ?: Qualities.Unknown.value
                    GlobalScope.launch(Dispatchers.IO) {
                        val link = newExtractorLink(
                            name, 
                            "Shoffree", 
                            mediaUrl, 
                            if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = referer
                        }
                        callback(link)
                        if (continuation.isActive) {
                            println("ShoffreeProvider: WebView media found. Resuming coroutine.")
                            continuation.resume(wv)
                        }
                    }
                }
                wv.addJavascriptInterface(probe, "ShoffreeProbe")

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null) {
                            println("ShoffreeProvider: WebView Console [${consoleMessage.messageLevel()}]: ${consoleMessage.message()} at ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        println("ShoffreeProvider: WebView started loading: $url")
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(buildJwHookScript(), null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        println("ShoffreeProvider: WebView finished loading: $url")
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(buildJwHookScript(), null)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        println("ShoffreeProvider: WebView error code $errorCode: $description (failing URL: $failingUrl)")
                        super.onReceivedError(view, errorCode, description, failingUrl)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        val reqUrl = request?.url?.toString()
                        val statusCode = errorResponse?.statusCode
                        println("ShoffreeProvider: WebView HTTP error $statusCode for URL: $reqUrl")
                        super.onReceivedHttpError(view, request, errorResponse)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                        val urlLower = requestUrl.lowercase()
                        
                        if (urlLower.contains(".m3u8") || urlLower.contains(".mp4")) {
                            if (!urlLower.contains(".gif") && !urlLower.contains(".png") && !urlLower.contains(".jpg")) {
                                println("ShoffreeProvider: WebView intercepted media: $requestUrl")
                                probe.post(requestUrl)
                            } else {
                                val muParam = request.url.getQueryParameter("mu")
                                if (muParam != null && (muParam.contains(".m3u8", true) || muParam.contains(".mp4", true))) {
                                    println("ShoffreeProvider: WebView intercepted mu media: $muParam")
                                    probe.post(muParam)
                                }
                            }
                        }
                        
                        val blockList = listOf("doubleclick.net", "googlesyndication.com", "adservice.google", "google-analytics.com")
                        if (blockList.any { requestUrl.contains(it) }) {
                            return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                        
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                wv.loadUrl(url, mapOf("Referer" to referer))
            }
        }
        
        if (webView != null) {
            println("ShoffreeProvider: WebView extraction succeeded, cleaning up WebView.")
            Handler(Looper.getMainLooper()).post {
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
            return@withTimeoutOrNull true
        } else {
            println("ShoffreeProvider: WebView extraction failed or timed out after 20 seconds.")
        }
        return@withTimeoutOrNull false
    } ?: false

    class ShoffreeProbe(private val onUrl: (String) -> Unit) {
        @android.webkit.JavascriptInterface
        fun post(url: String) {
            println("ShoffreeProvider: JS Probe intercepted URL: $url")
            onUrl(url)
        }
    }

    private fun buildJwHookScript(): String {
        return """
            (function() {
                if (window.__shofHooked) return;
                window.__shofHooked = true;
                
                function tryPostMedia(url) {
                    try {
                        if (!url) return;
                        if (url.indexOf('.gif') !== -1 || url.indexOf('.png') !== -1 || url.indexOf('.jpg') !== -1) {
                            var muMatch = url.match(/[?&]mu=([^&]+)/);
                            if (muMatch && muMatch[1]) {
                                var decoded = decodeURIComponent(muMatch[1]);
                                if (decoded.indexOf('.m3u8') !== -1 || decoded.indexOf('.mp4') !== -1) {
                                    window.ShoffreeProbe.post(decoded);
                                }
                            }
                            return;
                        }
                        if (url.indexOf('.m3u8') !== -1 || url.indexOf('.mp4') !== -1) {
                            window.ShoffreeProbe.post(url);
                        }
                    } catch(e) {}
                }

                // JWPlayer Hook
                var _jwImpl = window.jwplayer;
                Object.defineProperty(window, 'jwplayer', {
                    configurable: true,
                    enumerable: true,
                    get: function() { return _jwImpl; },
                    set: function(jw) {
                        if (typeof jw !== 'function') { _jwImpl = jw; return; }
                        _jwImpl = function() {
                            var inst = jw.apply(this, arguments);
                            if (inst && inst.setup) {
                                var origSetup = inst.setup.bind(inst);
                                inst.setup = function(cfg) {
                                    try {
                                        var src = (cfg.file)
                                            || (cfg.playlist && cfg.playlist[0] && cfg.playlist[0].file)
                                            || (cfg.sources && cfg.sources[0] && cfg.sources[0].file)
                                            || '';
                                        if (src) {
                                            tryPostMedia(src);
                                        }
                                    } catch(e) {}
                                    return origSetup(cfg);
                                };
                            }
                            return inst;
                        };
                        try {
                            for (var k in jw) {
                                if (jw.hasOwnProperty(k)) {
                                    try { _jwImpl[k] = jw[k]; } catch(e) {}
                                }
                            }
                        } catch(e) {}
                    }
                });

                // XMLHttpRequest Hook
                var XO = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    try {
                        var urlStr = typeof url === 'string' ? url : (url ? url.toString() : '');
                        tryPostMedia(urlStr);
                    } catch(e) {}
                    return XO.apply(this, arguments);
                };

                // Fetch Hook
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(resource, init) {
                        try {
                            var url = typeof resource === 'string' ? resource : (resource ? resource.url : '');
                            tryPostMedia(url);
                        } catch(e) {}
                        return origFetch.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()
    }
}