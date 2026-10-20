package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests

open class StreamRuby : ExtractorApi() {
    private val app = Requests()
    override val name = "StreamRuby"
    override val mainUrl = "https://streamruby.com"
    override val requiresReferer = false

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

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
        try {
            val pageResp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgents.random(),
                    "Referer" to (referer ?: url)
                )
            )
            val html = pageResp.text

            val unpacked = runCatching { getAndUnpack(html) }.getOrNull()
                ?: unpackFallback(html)
                ?: html

            val m3u8Url = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.groupValues?.get(0)

            if (!m3u8Url.isNullOrEmpty()) {
                val links = runCatching {
                    M3u8Helper.generateM3u8(name, m3u8Url, referer = url)
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
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

class StmRuby : StreamRuby() {
    override val name = "StmRuby"
    override val mainUrl = "https://stmruby.com"
}

class RubyStream : StreamRuby() {
    override val name = "RubyStream"
    override val mainUrl = "https://rubystream.net"
}
