package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.VideoDownloadManager.sanitizeFilename
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.txt
import java.io.File

class SaveM3UAction : VideoClickAction() {
    override val name = txt("Save as M3U")

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CloudStream"
        )
        if (!dir.exists()) dir.mkdirs()

        val episodeName = sanitizeFilename(
            video.name?.takeIf { it.isNotBlank() } ?: video.headerName,
            removeSpaces = false
        )
        val fileName = "$episodeName.m3u"
        val file = File(dir, fileName)

        var text = "#EXTM3U"

        result.links.forEach { link ->
            text += "\n\n#EXTINF:-1,${link.name}"

            if (link is DrmExtractorLink) {
                val manifestType = if (link.type == ExtractorLinkType.DASH) "mpd" else "hls"
                text += "\n#KODIPROP:inputstream=inputstream.adaptive"
                text += "\n#KODIPROP:inputstream.adaptive.manifest_type=$manifestType"

                when (link.uuid) {
                    CLEARKEY_UUID -> {
                        val kid = link.kid
                        val key = link.key
                        if (kid != null && key != null) {
                            fun b64urlToHex(b64: String): String {
                                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                                return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
                                    .joinToString("") { "%02x".format(it) }
                            }
                            text += "\n#KODIPROP:inputstream.adaptive.license_type=clearkey"
                            text += "\n#KODIPROP:inputstream.adaptive.license_key=${b64urlToHex(kid)}:${b64urlToHex(key)}"
                        }
                    }
                    WIDEVINE_UUID -> {
                        link.licenseUrl?.let { licenseUrl ->
                            text += "\n#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha"
                            text += "\n#KODIPROP:inputstream.adaptive.license_key=$licenseUrl"
                        }
                    }
                }
            }

            link.headers["User-Agent"]?.let { text += "\n#EXTVLCOPT:http-user-agent=$it" }
            link.headers["Referer"]?.let  { text += "\n#EXTVLCOPT:http-referrer=$it" }
            link.headers["Cookie"]?.let   { text += "\n#EXTVLCOPT:http-cookie=$it" }
            link.headers["Origin"]?.let   { text += "\n#EXTVLCOPT:http-origin=$it" }

            text += "\n${link.url}"
        }

        file.writeText(text)

        CommonActivity.showToast("Saved to Downloads/CloudStream/$fileName")
    }
}
