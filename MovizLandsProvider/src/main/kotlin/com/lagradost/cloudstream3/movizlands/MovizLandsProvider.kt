@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.movizlands

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element



import android.annotation.SuppressLint

class MovizLandsProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.moviz.land"
    override var name = "MovizLands"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun String.getFullSize(): String? {
        return this.replace("""-\d+x\d+""".toRegex(),"")
    }
    
    private fun String.cleanTitle(): String {
		val prefix = setOf("مشاهدة فيلم","مشاهدة وتحميل فيلم","تحميل","فيلم","انمي","إنمي","مسلسل","برنامج")
		val suffix = setOf("مدبلج للعربية","اون لاين","مترجم")
		this.let{ clean ->
            var aa = clean
				prefix.forEach{ pre ->
            	aa = if (aa.contains(pre))	aa.replace(pre,"") 	else	aa	}
                var bb = aa
				suffix.mapNotNull{ suf ->
            	bb = if (bb.contains(suf))	bb.replace(suf,"")	else	bb	}
        	return bb
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = select("h3, .BlockTitle, .movie-slide-title, .search-card-title, h1, h2").text().ifEmpty {
            attr("data-title")
        }
        val img = select("img").first()
        val posterUrl = img?.attr("data-src")?.ifEmpty { null } 
            ?: img?.attr("abs:src")
            ?: img?.attr("src")
            ?: attr("data-image").ifEmpty { null }
        val year = select(".WatchTime, .InfoEndBlock li:last-child, .RestInformation li:last-child, .search-card-year").text()?.getIntFromText()
        var quality = select(".Quality, .RestInformation li:first-child, .search-card-quality").text()?.replace(" |-|1080p|720p".toRegex(), "")?.replace("BluRay","BLURAY")
        val tvtype = if(title.contains("فيلم") || !title.contains("مسلسل")) TvType.Movie else TvType.TvSeries
        
        val url = select("a").first()?.attr("abs:href") ?: attr("data-url").ifEmpty { null } ?: return null

        return newMovieSearchResponse(
            title.cleanTitle(),
            url,
            tvtype,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "افلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/browse/" to "تصفح الكل",
        "$mainUrl/sports/" to "رياضة",
        "$mainUrl/kids/" to "اطفال",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document   
        val list = doc.select("div.Small--Box, .BlockItem, article.movie-slide, article.search-card, article").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ".toRegex(), "%20")
        val d = app.get("$mainUrl/?s=$q").document
        return d.select("div.Small--Box, article.search-card, article.movie-slide, article").mapNotNull {
            it.toSearchResponse()
        }
    }

private val seasonPatterns = arrayOf(
    Pair("الموسم العاشر|الموسم 10", 10),
    Pair("الموسم الحادي عشر|الموسم 11", 11),
    Pair("الموسم الثاني عشر|الموسم 12", 12),
    Pair("الموسم الثالث عشر|الموسم 13", 13),
    Pair("الموسم الرابع عشر|الموسم 14", 14),
    Pair("الموسم الخامس عشر|الموسم 15", 15),
    Pair("الموسم السادس عشر|الموسم 16", 16),
    Pair("الموسم السابع عشر|الموسم 17", 17),
    Pair("الموسم الثامن عشر|الموسم 18", 18),
    Pair("الموسم التاسع عشر|الموسم 19", 19),
    Pair("الموسم العشرون|الموسم 20", 20),
    Pair("الموسم الاول|الموسم 1", 1),
    Pair("الموسم الثاني|الموسم 2", 2),
    Pair("الموسم الثالث|الموسم 3", 3),
    Pair("الموسم الرابع|الموسم 4", 4),
    Pair("الموسم الخامس|الموسم 5", 5),
    Pair("الموسم السادس|الموسم 6", 6),
    Pair("الموسم السابع|الموسم 7", 7),
    Pair("الموسم الثامن|الموسم 8", 8),
    Pair("الموسم التاسع|الموسم 9", 9),
)

private fun getSeasonFromString(sName: String): Int {
    return seasonPatterns.firstOrNull{(pattern, seasonNum) -> sName.contains(pattern.toRegex()) }?.second ?: 1
}
        
    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        
        // Poster (Prioritize data-src)
        val posterUrl = doc.select(".MainSingle .image img, .video-poster img, article img, meta[property='og:image']").attr("data-src").ifEmpty {
            doc.select(".MainSingle .image img, .video-poster img, article img").attr("src").ifEmpty {
                doc.select("meta[property='og:image']").attr("content")
            }
        }

        // Title
        val rawTitle = doc.select("h1.PostTitle, h1.video-title, h1").text()
        var title = rawTitle.cleanTitle()
        
        // Year
        val year = doc.select(".RightTaxContent li:has(.fa-calendar) a, .video-meta").text()?.getIntFromText()
        
        // Plot
        val synopsis = doc.select(".StoryArea, .video-description, meta[property='og:description']").attr("content").ifEmpty {
            doc.select(".StoryArea, .video-description").text()
        }
        
        // Tags
        var tags = doc.select(".RightTaxContent li:has(.fa-bars) a, .video-genres a, .video-tags a").map { it.text() }
        
        // Recommendations
        val recommendations = doc.select(".mitatagall .Small--Box, .otherser .Small--Box, article.movie-slide").mapNotNull {
            it.toSearchResponse()
        }

        val isMovie = url.contains("/movies/") || url.contains("/film/") ||
                (!url.contains("/series/") && (rawTitle.contains("فيلم|عرض".toRegex()) || !rawTitle.contains("مسلسل")))

        return if (isMovie) {
            val watchHref = doc.select(".BTNSDownWatch a.watch, a.watch-btn, a[href*='/watch/']").attr("abs:href")
            val watchData = if (watchHref.isNotEmpty()) watchHref.replace("//watch/", "/watch/") else "${url.trimEnd('/')}/watch/"
            newMovieLoadResponse(title, url, TvType.Movie, watchData) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            val episodeElements = doc.select("a.episode-card, .allepcont a, .episodes-list a, .EpisodesList .EpisodeItem, a:has(.epnum), a[href*='/series/'], a[href*='-episode-'], a[href*='ep-']").filter { el ->
                val text = el.text()
                val href = el.attr("href")
                val aria = el.attr("aria-label")
                (aria.contains("الحلقة") || text.contains("الحلقة") || href.contains("episode") || href.contains("ep-")) && !text.contains("Full")
            }
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { element ->
                    val aTag = if (element.tagName() == "a") element else element.selectFirst("a")
                    val rawEpUrl = aTag?.attr("abs:href") ?: ""
                    val epUrl = rawEpUrl.replace("//watch/", "/watch/")
                    if (epUrl.isNotEmpty()) {
                        val epNum = element.attr("aria-label").getIntFromText()
                            ?: element.select(".episode-number, .epnum, em").text().getIntFromText()
                            ?: Regex("""(\d+)""").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                        val epName = element.select("div.ep-info h2, .episode-title").text().ifEmpty {
                            if (epNum != null) "الحلقة $epNum" else element.text().trim()
                        }
                        episodes.add(
                            newEpisode(epUrl) {
                                this.episode = epNum
                                this.name = epName
                                this.posterUrl = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = posterUrl
                this.year = year
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.replace("//watch/", "/watch/")
        val doc = app.get(cleanData).document
        
        val watchUrl = if (doc.select("ul#watch li, .server-card").isNotEmpty()) {
            cleanData
        } else {
            val btnHref = doc.select(".BTNSDownWatch a.watch, a.watch-btn, a[href*='/watch/']").attr("abs:href")
                .ifEmpty { doc.select(".WatchBar a, .WatchBar button, a[href*='/watch/']").attr("abs:href") }
            if (btnHref.isNotEmpty()) {
                btnHref.replace("//watch/", "/watch/")
            } else if (cleanData.endsWith("/watch/")) {
                cleanData
            } else {
                "${cleanData.trimEnd('/')}/watch/"
            }
        }

        val watchDoc = if (watchUrl == cleanData) doc else app.get(watchUrl).document

        val handleUrl: suspend (String) -> Unit = { rawUrl ->
            var url = rawUrl
            if (url.startsWith("//")) url = "https:$url"
            if (url.startsWith("http")) {
                if (url.contains("upbom")) {
                    try {
                        val upbomDoc = app.get(url, referer = mainUrl).document
                        upbomDoc.select("source[src]").forEach { source ->
                            val src = source.attr("src")
                            val size = source.attr("size").ifEmpty { "720" }
                            val quality = when (size) {
                                "1080" -> Qualities.P1080.value
                                "720" -> Qualities.P720.value
                                "480" -> Qualities.P480.value
                                "360" -> Qualities.P360.value
                                else -> Qualities.P720.value
                            }
                            var finalSrc = src
                            if (src.contains("?h=")) {
                                val h = src.substringAfter("?h=").substringBefore("&")
                                try {
                                    val host = String(android.util.Base64.decode(h, android.util.Base64.DEFAULT)).trim()
                                    val path = src.substringAfter("upbom.cam").substringBefore("?h=")
                                    if (host.isNotEmpty() && path.isNotEmpty()) {
                                        finalSrc = "http://$host$path"
                                    }
                                } catch (_: Exception) {}
                            }
                            callback(
                                newExtractorLink(
                                    "Upbom",
                                    "Upbom ${size}p",
                                    finalSrc,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://upbom.cam/"
                                    this.quality = quality
                                }
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                loadExtractor(url, data, subtitleCallback) { link ->
                    callback(link)
                }
            }
        }
        
        watchDoc.select("ul#watch li, .server-card, a[data-src], div[data-src], button[data-src]").forEach { el ->
            val serverUrl = el.attr("data-src").ifEmpty { el.attr("data-watch") }
            if (serverUrl.isNotEmpty()) {
                handleUrl(serverUrl)
            }
        }

        watchDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                handleUrl(src)
            }
        }
        return true
    }
}