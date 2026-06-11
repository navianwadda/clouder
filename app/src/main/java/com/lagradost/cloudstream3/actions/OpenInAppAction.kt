package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.isAppInstalled
import com.lagradost.cloudstream3.utils.DataStoreHelper
import android.util.Base64
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import java.io.File

fun updateDurationAndPosition(position: Long, duration: Long) {
    if (position <= 0 || duration <= 0) return
    val episode = getKey<ResultEpisode>("last_opened") ?: return
    DataStoreHelper.setViewPosAndResume(episode.id, position, duration, episode, null)
    ResultFragment.updateUI()
}

/**
 * Util method that may be helpful for creating intents for apps that support m3u8 files.
 * All sources are written to a temporary m3u8 file, which is then sent to the app.
 */
fun makeTempM3U8Intent(
    context: Context,
    intent: Intent,
    result: LinkLoadingResult
) {
    if (result.links.size == 1) {
        intent.setDataAndType(result.links.first().url.toUri(), "video/*")
        return
    }

    intent.apply {
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    val outputFile = File.createTempFile("mirrorlist", ".m3u8", context.cacheDir)
    var text = "#EXTM3U\n#EXT-X-VERSION:3"

    result.links.forEach { link ->
        text += "\n\n#EXTINF:-1,${link.name}"

        if (link is DrmExtractorLink) {
            // Manifest type: mpd for DASH, hls for M3U8
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
                        val kidHex = b64urlToHex(kid)
                        val keyHex = b64urlToHex(key)
                        text += "\n#KODIPROP:inputstream.adaptive.license_type=clearkey"
                        text += "\n#KODIPROP:inputstream.adaptive.license_key=$kidHex:$keyHex"
                    }
                }
                WIDEVINE_UUID -> {
                    val licenseUrl = link.licenseUrl
                    if (licenseUrl != null) {
                        text += "\n#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha"
                        text += "\n#KODIPROP:inputstream.adaptive.license_key=$licenseUrl"
                    }
                }
            }
        }

        // Pass all headers as EXTVLCOPT
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

    text += "\n#EXT-X-ENDLIST"
    outputFile.writeText(text)

    intent.setDataAndType(
        FileProvider.getUriForFile(
            context,
            context.applicationContext.packageName + ".provider",
            outputFile
        ), "application/x-mpegURL"
    )
}

abstract class OpenInAppAction(
    open val appName: UiText,
    open val packageName: String,
    private val intentClass: String? = null,
    private val action: String = Intent.ACTION_VIEW
) : VideoClickAction() {
    override val name: UiText
        get() = txt(R.string.episode_action_play_in_format, appName)

    override val isPlayer = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) =
        context?.isAppInstalled(packageName) != false

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val intent = Intent(action)
        intent.setPackage(packageName)
        if (intentClass != null) {
            intent.component = ComponentName(packageName, intentClass)
        }
        putExtra(context, intent, video, result, index)
        setKey("last_opened", video)
        launchResult(intent)
    }

    @Throws
    abstract suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    )

    @Throws
    abstract fun onResult(activity: Activity, intent: Intent?)

    fun onResultSafe(activity: Activity, intent: Intent?) {
        try {
            onResult(activity, intent)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
