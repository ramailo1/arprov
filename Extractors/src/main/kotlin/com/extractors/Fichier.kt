package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class Fichier : ExtractorApi() {
    private val app = Requests()
    override val name = "1Fichier"
    override val mainUrl = "https://1fichier.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            
            // Extract file info
            val fileName = doc.select(".file-name, .filename, h1").text()
            val fileSize = doc.select(".file-size, .size").text()
            
            // Search for direct download button
            val downloadButtons = doc.select("input[type=submit], button[type=submit], .download-button, .btn-download")
            
            if (downloadButtons.isNotEmpty()) {
                try {
                    val form = doc.select("form").first()
                    val action = form?.attr("action") ?: url
                    
                    val formData = mutableMapOf<String, String>()
                    val hiddenInputs = form?.select("input[type=hidden]") ?: emptyList()
                    for (input in hiddenInputs) {
                        val name = input.attr("name")
                        val value = input.attr("value")
                        if (name.isNotEmpty()) {
                            formData[name] = value
                        }
                    }
                    formData["submit"] = "download"
                    
                    val response = app.post(action, data = formData)
                    val responseDoc = response.document
                    
                    val downloadLinks = responseDoc.select("a[href*=.mp4], a[href*=.m3u8], a[download]")
                    
                    for (link in downloadLinks) {
                        val href = link.attr("href")
                        
                        if (href.isNotEmpty() && (href.contains(".mp4") || href.contains(".m3u8") || href.contains("1fichier"))) {
                            var fullUrl = href
                            if (!fullUrl.startsWith("http") && fullUrl.startsWith("/")) {
                                fullUrl = mainUrl + fullUrl
                            } else if (!fullUrl.startsWith("http")) {
                                fullUrl = "https://$fullUrl"
                            }
                            
                            sources.add(
                                newExtractorLink(name, name, fullUrl, if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                            )
                        }
                    }
                    
                    // Direct links from response
                    val directLinks = responseDoc.select("a[download], .direct-link")
                    for (link in directLinks) {
                        val href = link.attr("href")
                        if (href.isNotEmpty() && (href.contains(".mp4") || href.contains(".m3u8"))) {
                             var finalUrl = href
                             if (!finalUrl.startsWith("http")) {
                                finalUrl = if (finalUrl.startsWith("//")) "https:$finalUrl" else mainUrl + finalUrl
                             }
                             sources.add(
                                newExtractorLink(name, name, finalUrl, if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                            )
                        }
                    }

                    // Script data from response
                    val scriptData = responseDoc.html()
                    val patterns = listOf(
                        Regex("""['"](https?://[^'"]*cdn[^'"]*\.(mp4|m3u8)[^'"]*)['"]"""),
                        Regex("""window\.open\(['"]([^'"]+)['"]"""),
                        Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""")
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(scriptData)
                        for (match in matches) {
                            var videoLink = match.groupValues[1]
                             if (!videoLink.startsWith("http")) {
                                videoLink = if (videoLink.startsWith("//")) "https:$videoLink" else "https://$videoLink"
                            }
                            sources.add(
                                newExtractorLink(name, name, videoLink, if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                            )
                        }
                    }

                } catch (e: Exception) {
                    // Ignore errors in form submission
                }
            }
        } catch (e: Exception) {
            // Global error handling
        }
        
        return sources
    }
}

class FichierCom : Fichier() {
    override val name = "1Fichier"
    override val mainUrl = "https://1fichier.com"
}

class FichierOrg : Fichier() {
    override val name = "1Fichier"
    override val mainUrl = "https://1fichier.org"
}
