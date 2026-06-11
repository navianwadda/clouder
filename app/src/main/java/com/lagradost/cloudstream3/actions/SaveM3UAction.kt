package com.lagradost.cloudstream3.actions.temp

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
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

        val episodeName = sanitizeFilename(
            video.name?.takeIf { it.isNotBlank() } ?: video.headerName,
            removeSpaces = false
        )
        val fileName = "$episodeName.m3u"

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

            val knownHeaders = mapOf(
                "User-Agent" to "http-user-agent",
                "Referer"    to "http-referrer",
                "Cookie"     to "http-cookie",
                "Origin"     to "http-origin",
            )
            link.headers.forEach { (key, value) ->
                val optKey = knownHeaders[key] ?: "http-header-${key.lowercase().replace(" ", "-")}"
                text += "\n#EXTVLCOPT:$optKey=$value"
            }

            text += "\n${link.url}"
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/x-mpegurl")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CloudStream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Failed to create MediaStore entry")
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CloudStream"
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeText(text)
            }
            CommonActivity.showToast("Saved to Downloads/CloudStream/$fileName")
        } catch (e: Exception) {
            CommonActivity.showToast("Failed to save: ${e.message}")
        }
    }
}
