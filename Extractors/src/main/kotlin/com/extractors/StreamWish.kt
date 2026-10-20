package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests

open class StreamWish : ExtractorApi() {
    private val app = Requests()
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val fallbackDomains = listOf("hanerix.com", "vibuxer.com", "hgplaycdn.com")

    private fun unpackFallback(html: String): String? {
        val header = "eval(function(p,a,c,k,e,d){"
        val start = html.indexOf(header)
        if (start < 0) return null
        val end = html.indexOf("'.split('|'))", start)
        if (end < 0) return null
        val sepStart = html.lastIndexOf("',", end)
        if (sepStart < start) return null
        val sep = html.substring(sepStart + 2, end)
        val sepMatch = Regex("^(\\d+),(\\d+),'([\\s\\S]*)$").find(sep) ?: return null
        val a = sepMatch.groupValues[1].toInt()
        val c = sepMatch.groupValues[2].toInt()
        val k = sepMatch.groupValues[3].split("|")
        if (c > k.size) return null
        var p = html.substring(start + header.length, sepStart)
        for (i in c - 1 downTo 0) {
            val ki = k.getOrNull(i) ?: continue
            if (ki.isEmpty()) continue
            p = p.replace(Regex("\\b" + i.toString(a) + "\\b"), ki)
        }
        return p
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val reqHeaders = mapOf(
            "User-Agent" to userAgents.random(),
            "Referer" to (referer ?: url)
        )

        val candidates = mutableListOf(url)
        val fileId = url.substringAfterLast('/').substringBefore('?')
        if (fileId.isNotEmpty()) {
            fallbackDomains.forEach { domain ->
                candidates.add("https://$domain/e/$fileId")
            }
        }

        for (candidateUrl in candidates.distinct()) {
            try {
                val pageResp = app.get(candidateUrl, headers = reqHeaders)
                val html = pageResp.text

                val unpacked = runCatching { getAndUnpack(html) }.getOrNull()
                    ?: unpackFallback(html)
                    ?: html

                // 1. Try links map (hls4 / hls2 / hls3)
                val linksMatch = Regex("""var\s+links\s*=\s*\{([\s\S]*?)\};""").find(unpacked)
                if (linksMatch != null) {
                    val block = linksMatch.groupValues[1]
                    fun linkVal(name: String): String? =
                        Regex("\"$name\":\"([^\"]*)\"").find(block)?.groupValues?.get(1)

                    val rawStream = linkVal("hls4") ?: linkVal("hls2") ?: linkVal("hls3")
                    if (!rawStream.isNullOrEmpty()) {
                        val finalStream = if (rawStream.startsWith("http")) rawStream else {
                            val host = candidateUrl.substringBefore("/e/")
                            "$host$rawStream"
                        }
                        emitStream(finalStream, candidateUrl, callback)
                        return
                    }
                }

                // 2. Try file regex in unpacked code
                val fileMatch = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)
                    ?: Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").find(unpacked)

                if (fileMatch != null) {
                    val streamUrl = fileMatch.groupValues[1]
                    emitStream(streamUrl, candidateUrl, callback)
                    return
                }

                // 3. Try mp4 video links
                val mp4Match = Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(unpacked)
                if (mp4Match != null) {
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            mp4Match.groupValues[1],
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = candidateUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                // Try next candidate
            }
        }
    }

    private suspend fun emitStream(
        m3u8Url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = runCatching {
            M3u8Helper.generateM3u8(name, m3u8Url, referer = referer)
        }.getOrNull()

        if (!links.isNullOrEmpty()) {
            links.forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    name,
                    name,
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

class StreamHg : StreamWish() {
    override val name = "StreamHG"
    override val mainUrl = "https://streamhg.com"
}

class WishFast : StreamWish() {
    override val name = "WishFast"
    override val mainUrl = "https://wishfast.top"
}

class Hanerix : StreamWish() {
    override val name = "Hanerix"
    override val mainUrl = "https://hanerix.com"
}

class Vibuxer : StreamWish() {
    override val name = "Vibuxer"
    override val mainUrl = "https://vibuxer.com"
}

class HgPlayCdn : StreamWish() {
    override val name = "HgPlayCdn"
    override val mainUrl = "https://hgplaycdn.com"
}

class HgCloud : StreamWish() {
    override val name = "HgCloud"
    override val mainUrl = "https://hgcloud.to"
}

class HgLink : StreamWish() {
    override val name = "HgLink"
    override val mainUrl = "https://hglink.to"
}

class StrWish : StreamWish() {
    override val name = "StrWish"
    override val mainUrl = "https://strwish.com"
}

class Awish : StreamWish() {
    override val name = "Awish"
    override val mainUrl = "https://awish.pro"
}

class Dwish : StreamWish() {
    override val name = "Dwish"
    override val mainUrl = "https://dwish.pro"
}

class Mwish : StreamWish() {
    override val name = "Mwish"
    override val mainUrl = "https://mwish.pro"
}

class SwishSrv : StreamWish() {
    override val name = "SwishSrv"
    override val mainUrl = "https://swishsrv.com"
}
