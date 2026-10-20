package com.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI

open class MegaMax : ExtractorApi() {
    private val app = Requests()
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.online"
    override val requiresReferer = false

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val streamWishExtractor = StreamWish()
    private val streamRubyExtractor = StreamRuby()

    private val streamWishHosts = listOf(
        "streamwish.to", "streamwish.com", "streamwish.net", "strwish.com", "awish.pro",
        "dwish.pro", "mwish.pro", "hanerix.com", "vibuxer.com", "hgplaycdn.com",
        "hgcloud.to", "hglink.to", "streamhg.com", "wishfast.top"
    )

    private suspend fun <A, B> Iterable<A>.parMap(f: suspend (A) -> B): List<B> =
        coroutineScope {
            map { async { f(it) } }.awaitAll()
        }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = url.replace("/download/", "/iframe/")
        try {
            val resp = app.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to userAgents.random(),
                    "Referer" to (referer ?: mainUrl)
                )
            )
            val html = resp.text

            val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val rawComp = Regex(""""component"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "files/mirror/video"
            val component = rawComp.replace("\\/", "/")

            val jsonText = if (!version.isNullOrEmpty()) {
                val inertiaResp = app.get(
                    pageUrl,
                    headers = mapOf(
                        "User-Agent" to userAgents.random(),
                        "Referer" to pageUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-Inertia" to "true",
                        "X-Inertia-Version" to version,
                        "X-Inertia-Partial-Component" to component,
                        "X-Inertia-Partial-Data" to "streams"
                    )
                )
                inertiaResp.text
            } else {
                html
            }

            val parsedData = tryParseJson<MegaMaxInertia>(jsonText)
            val qualities = parsedData?.props?.streams?.data

            if (!qualities.isNullOrEmpty()) {
                qualities.parMap { q ->
                    val qualityVal = getQualityFromName(q.label ?: q.resolution)
                    q.mirrors?.parMap mirrorLoop@{ mirror ->
                        val rawLink = mirror.link ?: return@mirrorLoop
                        val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
                        val host = runCatching { URI(link).host?.replace("www.", "") ?: "" }.getOrDefault("")
                        val driver = mirror.driver?.lowercase() ?: ""

                        when {
                            streamWishHosts.any { host.endsWith(it) } || driver == "streamhg" || link.contains("streamwish") -> {
                                streamWishExtractor.getUrl(link, pageUrl, subtitleCallback, callback)
                            }
                            host.contains("stmruby") || host.contains("streamruby") || driver == "streamruby" -> {
                                streamRubyExtractor.getUrl(link, pageUrl, subtitleCallback, callback)
                            }
                            link.endsWith(".m3u8") || link.contains(".m3u8?") -> {
                                callback(
                                    newExtractorLink(
                                        name,
                                        "${name} ${q.label ?: ""}".trim(),
                                        link,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = pageUrl
                                        this.quality = qualityVal
                                    }
                                )
                            }
                            link.endsWith(".mp4") || link.contains(".mp4?") -> {
                                callback(
                                    newExtractorLink(
                                        name,
                                        "${name} ${q.label ?: ""}".trim(),
                                        link,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = pageUrl
                                        this.quality = qualityVal
                                    }
                                )
                            }
                            else -> {
                                loadExtractor(link, pageUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } else {
                val linkMatches = Regex(""""link"\s*:\s*"([^"]+)"""").findAll(jsonText).map {
                    it.groupValues[1].replace("\\/", "/")
                }.toList()

                linkMatches.parMap { rawLink ->
                    val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
                    val host = runCatching { URI(link).host?.replace("www.", "") ?: "" }.getOrDefault("")

                    when {
                        streamWishHosts.any { host.endsWith(it) } || link.contains("streamwish") -> {
                            streamWishExtractor.getUrl(link, pageUrl, subtitleCallback, callback)
                        }
                        host.contains("stmruby") || host.contains("streamruby") -> {
                            streamRubyExtractor.getUrl(link, pageUrl, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(link, pageUrl, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    data class MegaMaxInertia(
        @JsonProperty("props") val props: MegaMaxProps? = null
    )

    data class MegaMaxProps(
        @JsonProperty("streams") val streams: MegaMaxStreams? = null
    )

    data class MegaMaxStreams(
        @JsonProperty("data") val data: List<MegaMaxQuality>? = null
    )

    data class MegaMaxQuality(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("mirrors") val mirrors: List<MegaMaxMirror>? = null
    )

    data class MegaMaxMirror(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("driver") val driver: String? = null
    )
}

class MegaMaxTo : MegaMax() {
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.to"
}

class MegaMaxCc : MegaMax() {
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.cc"
}

class MegaMaxMe : MegaMax() {
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.me"
}
