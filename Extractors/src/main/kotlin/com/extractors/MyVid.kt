package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class MyVid : ExtractorApi() {
    private val app = Requests()
    override val name = "MyVid"
    override val mainUrl = "https://myviid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
          val sources = mutableListOf<ExtractorLink>()
          try {
              val text = app.get(url).document.select("body > script:nth-child(2)").html() ?: ""
              val a = text.substringAfter("||||").substringBefore("'.split").split("|")
              val link = "${a[7]}://${a[24]}.${a[6]}.${a[5]}/${a[83]}/v.${a[82]}"
              if (link.isNotBlank()) {
                  sources.add(
                        newExtractorLink(name, name, link, if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
              }
          } catch (e: Exception) {
          }
       return sources
    }
}
