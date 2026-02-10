package com.lagradost.cloudstream3.egybest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

import android.annotation.TargetApi
import android.os.Build

import org.jsoup.nodes.Element

class EgyBestProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egibest.net"
    override var name = "EgyBest (In Progress)"

    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private fun String.getYearFromTitle(): Int? {
        return Regex("""\((\d{4})\)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element?.extractPoster(doc: Element? = null): String? {
        if (this == null) return null
        val img = when {
            this.tagName() == "img" -> this
            else -> this.selectFirst("img")
        }

        val poster = img?.attr("data-img")
            ?.ifBlank { img.attr("data-src") }
            ?.ifBlank { img.attr("src") }
            ?.takeIf { it.isNotBlank() }

        if (poster != null) return fixUrl(poster)

        // OpenGraph fallback (page-level)
        val og = doc?.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        if (og != null) return fixUrl(og)

        // Twitter card fallback
        return doc?.selectFirst("meta[name=twitter:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.attr("href") ?: return null
        val posterUrl = this.extractPoster(this.ownerDocument())
        // Browser inspection confirmed title is in 'div.title', not 'h3'
        var title = select(".title").text()
        if (title.isEmpty()) title = this.attr("title")
        val year = title.getYearFromTitle()
        val isMovie = Regex(".*/(movie|masrahiya)/").containsMatchIn(url)
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries
        title = if (year !== null) title else title.split(" (")[0].trim()
        val quality = select("span.ribbon span").text().replace("-", "")
        // If you need to differentiate use the url.
        return newMovieSearchResponse(
            title,
            fixUrl(url), // Fixed double URL issue
            tvType,
        ) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recent" to "أحدث الاضافات",
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/category/anime/" to "انمي",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = if(page > 1) {
             if(request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
        } else {
            request.data
        }
        
        val doc = app.get(url).document
        
        // Homepage uses .postBlock (often in sliders)
        // Grid pages (Recent, Movies, etc.) use .postBlockCol
        val list = doc.select(".postBlock, .postBlockCol")
            .filter { element ->
                // Robustly exclude the main slider duplicates on the homepage
                element.parents().none { it.id() == "postSlider" }
            }
            .mapNotNull { element ->
                element.toSearchResponse()
            }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val result = arrayListOf<SearchResponse>()
        listOf("$mainUrl/explore/?q=$query").forEach { url ->
            val d = app.get(url).document
            d.select(".postBlock").mapNotNull {
                it.toSearchResponse()?.let { it1 -> result.add(it1) }
            }
        }
        return result.distinct().sortedBy { it.name }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = Regex(".*/(movie|masrahiya)/").containsMatchIn(url)
        
        // Browser verification: Poster is in .postImg or .postCover, or .postBlockColImg
        val posterUrl = doc.selectFirst(".postImg, .postCover, .postBlockColImg, .poster")
            .extractPoster(doc)

        // Title is often in .postTitle h1, or just h1
        val title = doc.select(".postTitle h1, h1.title, h1").text()

        // Metadata table uses table.postTable, table.full or .table, not .movieTable
        val table = doc.select("table.postTable, table.full, table.table")
        
        val year = table.select("tr").firstOrNull { it.text().contains("سنة الإنتاج") }
                   ?.select("td")?.lastOrNull()?.text()?.toIntOrNull() ?: title.getYearFromTitle()
        
        val tags = table.select("tr").firstOrNull { it.text().contains("النوع") }
                   ?.select("a")?.map { it.text() }

        // Plot is in p.description (User snippet), fallback to .story or .postStory
        val synopsis = doc.select("p.description, .postStory, .story").text()

        val actors = doc.select("div.cast_list .cast_item, .story div a").mapNotNull {
            val imgTag = it.selectFirst("img")
            val name = imgTag?.attr("alt") ?: it.text()
            val image = imgTag?.extractPoster()
                
            val roleString = it.selectFirst("span")?.text() ?: ""
            // Allow actors without images as per CloudStream best practices
            val mainActor = Actor(name, image)
            ActorData(actor = mainActor, roleString = roleString)
        }

        return if (isMovie) {
            val recommendations = doc.select(".movies_small .postBlock, .movies_small .postBlockCol, .related .postBlock").mapNotNull { element ->
                element.toSearchResponse()
            }

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
                // addTrailer(youtubeTrailer)
            }
        } else {
            val episodes = ArrayList<Episode>()
            // Narrowly select season links inside specific containers to avoid random series
            // Using .toList() to avoid Jsoup Elements/Kotlin Ambiguity in older compilers
            val seasonLinks = doc.select(".h_scroll a, a:contains(الموسم)").toList().filter { it.parents().none { p -> p.hasClass("related") || p.hasClass("movies_small") } }.map { it.attr("href") }.distinct()
            
            if (seasonLinks.isNotEmpty()) {
                seasonLinks.forEach { seasonUrl ->
                     val d = app.get(fixUrl(seasonUrl)).document
                     // Robust universal season regex
                     val season = Regex("""(season|الموسم)[^\d]*(\d+)""").find(seasonUrl)?.groupValues?.get(2)?.toIntOrNull()
                     
                     // Prioritize .all-episodes (New Layout), fallback to filtered general links
                     var episodeLinks: List<Element> = d.select(".all-episodes a")
                     if (episodeLinks.isEmpty()) {
                         episodeLinks = d.select("a:contains(الحلقة)").toList().filter { element -> 
                             element.parents().none { p -> p.hasClass("slider") || p.hasClass("owl-carousel") || p.hasClass("related") || p.hasClass("movies_small") }
                         }
                     }

                     episodeLinks.forEach { epLink ->
                        val href = epLink.attr("href")
                        // Performance: Check current element for image first, fallback to series poster
                        val epThumb = epLink.selectFirst("img")?.extractPoster() ?: posterUrl
                        // Robust universal episode regex
                        val ep = Regex("""(ep|الحلقة)[^\d]*(\d+)""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                        
                        episodes.add(
                            newEpisode(fixUrl(href)) {
                                this.name = ep?.let { "الحلقة $it" } ?: epLink.text()
                                this.season = season
                                this.episode = ep
                                this.posterUrl = epThumb
                            }
                        )
                     }
                }
            } else {
                 // Try finding episodes on current page if no season list
                var episodeLinks: List<Element> = doc.select(".all-episodes a")
                if (episodeLinks.isEmpty()) {
                     episodeLinks = doc.select("a:contains(الحلقة)").toList().filter { element -> 
                         element.parents().none { p -> p.hasClass("slider") || p.hasClass("owl-carousel") || p.hasClass("related") || p.hasClass("movies_small") }
                     }
                }

                episodeLinks.forEach { epLink ->
                        val href = epLink.attr("href")
                        val epThumb = epLink.selectFirst("img")?.extractPoster() ?: posterUrl
                        val ep = Regex("""(ep|الحلقة)[^\d]*(\d+)""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                        
                        episodes.add(
                            newEpisode(fixUrl(href)) {
                                this.name = ep?.let { "الحلقة $it" } ?: epLink.text()
                                this.season = 1
                                this.episode = ep
                                this.posterUrl = epThumb
                            }
                        )
                }
            }
            
            // Sort by Season AND Episode key
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.actors = actors
                // addTrailer(youtubeTrailer)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        doc.select("ul#watch-servers-list li, .servList li").mapNotNull {
            val onclick = it.attr("onclick")
            val url = Regex("loadIframe\\(this, '(.*?)'\\)").find(onclick)?.groupValues?.get(1)
            url
        }.forEach { url ->
             // Ensure URLs are fixed (absolute)
             loadExtractor(fixUrl(url), subtitleCallback, callback)
        }
        
        // Fallback for default iframe if list is empty or logic fails
        doc.select("iframe#videoPlayer").attr("src").takeIf { it.isNotBlank() }?.let { url ->
            loadExtractor(fixUrl(url), subtitleCallback, callback)
        }

        return true
    }
}
