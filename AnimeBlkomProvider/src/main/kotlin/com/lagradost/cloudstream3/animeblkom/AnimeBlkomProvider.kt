package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeBlkomProvider : MainAPI() {

    override var name = "AnimeBlkom"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val usesWebView = true

    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

    private val domains = listOf(
        "https://animeblkom.tv",
        "https://animeblkom.com",
        "https://animeblkom.net"
    )

    override var mainUrl = domains.first()
    private var lastWorkingDomain: String? = null

    // ======= MAIN PAGE =======
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<HomePageList>()
        val doc = tryMirrorsWithRetry { CloudflareHelper.getDocOrNull(it, it) }
            ?: return newHomePageResponse(emptyList())

        val latest = doc.select("div.recent-episode").mapNotNull { it.toSearch() }
        if (latest.isNotEmpty()) home.add(HomePageList("آخر الحلقات المضافة", latest, isHorizontalImages = true))

        val ratedDoc = tryMirrorsWithRetry { CloudflareHelper.getDocOrNull("$it/anime-list?sort_by=rate", it) }
        ratedDoc?.let {
            val rated = it.select("div.content").mapNotNull { elem -> elem.toSearch() }
            if (rated.isNotEmpty()) home.add(HomePageList("الأعلى تقييماً", rated))
        }

        return newHomePageResponse(home)
    }

    // ======= SEARCH =======
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = tryMirrorsWithRetry { CloudflareHelper.getDocOrNull("$it/search?query=$query", it) }
            ?: return emptyList()
        return doc.select("div.content, div.recent-episode").mapNotNull { it.toSearch() }
    }

    // ======= LOAD ANIME =======
    override suspend fun load(url: String): LoadResponse {
        val doc = tryMirrorsOrThrowWithRetry(url) { targetUrl, referer ->
            CloudflareHelper.getDocOrNull(targetUrl, referer)
        }

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("div.poster img")?.absUrl("data-original")
            ?.takeIf { it.isNotEmpty() } ?: doc.selectFirst("div.poster img")?.absUrl("src")
        val plot = doc.selectFirst(".story")?.text()
        val tags = doc.select(".genres a").map { it.text() }

        val statusText = doc.select(".info-table tr").firstNotNullOfOrNull {
            if (it.selectFirst(".head")?.text()?.contains("حالة") == true)
                it.selectFirst(".info")?.text()
            else null
        }

        val episodes = doc.select("ul.episodes-links a").mapNotNull {
            val epNum = it.selectFirst("span")?.text()?.toIntOrNull()
            newEpisode(it.attr("href")) {
                episode = epNum
                name = "Episode $epNum"
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            showStatus = when (statusText) {
                "مستمر" -> ShowStatus.Ongoing
                "منتهي" -> ShowStatus.Completed
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ======= LOAD LINKS =======
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = tryMirrorsOrThrowWithRetry(data) { targetUrl, referer ->
            CloudflareHelper.getDocOrNull(targetUrl, referer)
        }

        // Direct downloads
        doc.select("#download a.btn").forEach {
            val link = it.absUrl("href")
            val quality = it.text().filter(Char::isDigit).toIntOrNull() ?: Qualities.Unknown.value
            if (link.isNotBlank()) callback(
                newExtractorLink(name, "Download ${quality}p", link, ExtractorLinkType.VIDEO) { this.quality = quality }
            )
        }

        // Streaming servers
        doc.select(".servers a[data-src]").forEach {
            val link = it.attr("data-src")
            if (link.isNotBlank()) loadExtractor(link, data, subtitleCallback, callback)
        }

        return true
    }

    // ======= ELEMENT PARSER =======
    private fun Element.toSearch(): SearchResponse? {
        val title = selectFirst(".name")?.text() ?: return null
        val link = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.absUrl("data-original")?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.absUrl("src")
        return newAnimeSearchResponse(title, link, TvType.Anime) { posterUrl = poster }
    }

    // ======= MIRROR + RETRY HELPERS =======
    private suspend inline fun tryMirrorsWithRetry(
        retries: Int = 1,
        crossinline block: suspend (String) -> Document?
    ): Document? {
        // Try cached domain first
        lastWorkingDomain?.let { domain ->
            repeat(retries + 1) {
                try { 
                    block(domain)?.let { 
                        mainUrl = domain
                        return it 
                    } 
                } catch (_: Exception) {}
            }
        }
        // Try all mirrors
        for (domain in domains) {
            repeat(retries + 1) {
                try { 
                    block(domain)?.let { 
                        lastWorkingDomain = domain
                        mainUrl = domain
                        return it 
                    } 
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private suspend inline fun tryMirrorsOrThrowWithRetry(
        originalUrl: String,
        retries: Int = 1,
        crossinline block: suspend (String, String) -> Document?
    ): Document {
        tryMirrorsWithRetry(retries) { domain ->
            val mirroredUrl = originalUrl.replace(Regex("""https?://[^/]+"""), domain)
            block(mirroredUrl, domain)
        }?.let { return it }

        throw ErrorLoadingException("Cloudflare protection – please open in WebView")
    }
}