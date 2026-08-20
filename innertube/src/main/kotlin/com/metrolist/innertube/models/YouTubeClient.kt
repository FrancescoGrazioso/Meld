package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val buildId: String? = null,
    val cronetVersion: String? = null,
    val packageName: String? = null,
    val friendlyName: String? = null,
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val isEmbedded: Boolean = false,
    val useWebPoTokens: Boolean = false,
) {
    fun toContext(locale: YouTubeLocale, visitorData: String?, dataSyncId: String?) = Context(
        client = Context.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            osName = osName,
            osVersion = osVersion,
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            androidSdkVersion = androidSdkVersion,
            gl = locale.gl,
            hl = locale.hl,
            visitorData = visitorData
        ),
        user = Context.User(
            onBehalfOfUser = if (loginSupported) dataSyncId else null
        ),
    )

    companion object {
        const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        val WEB = YouTubeClient(
            clientName = "WEB",
            clientVersion = "2.20260213.00.00",
            clientId = "1",
            userAgent = USER_AGENT_WEB,
        )

        val WEB_REMIX = YouTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260213.01.00",
            clientId = "67",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
        )

        /**
         * The only client that answers `OK` for age-restricted / explicit tracks, because it is the
         * only authenticated one left in the stream chain. Everything else is refused with
         * "sign in to confirm your age".
         *
         * `useWebPoTokens` is load-bearing and was missing: yt-dlp lists `web_creator` as requiring
         * a GVS PO token, and the flag is what makes `YTPlayerUtils` append `pot=` to the
         * deciphered URL at all. Without it we produced a URL with a correct signature and a
         * transformed `n` that googlevideo still rejected with 403 on the very first byte
         * (`hasPot=false` in the fix403 trace). Upstream sets the same flag.
         */
        val WEB_CREATOR = YouTubeClient(
            clientName = "WEB_CREATOR",
            clientVersion = "1.20260213.00.00",
            clientId = "62",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
        )

        val TVHTML5 = YouTubeClient(
            clientName = "TVHTML5",
            clientVersion = "7.20260213.00.00",
            clientId = "7",
            userAgent = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
        )

        /**
         * Embedded player that can bypass age-restriction.
         * Does not require login for age-restricted content.
         */
        val TVHTML5_SIMPLY_EMBEDDED_PLAYER = YouTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            loginSupported = true,
            loginRequired = false,
            useSignatureTimestamp = true,
            isEmbedded = true,
        )

        val IOS = YouTubeClient(
            clientName = "IOS",
            clientVersion = "21.03.1",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            osVersion = "18.2.22C152",
        )

        val MOBILE = YouTubeClient(
            clientName = "ANDROID",
            clientVersion = "21.03.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
            loginSupported = true,
            useSignatureTimestamp = true
        )

        /**
         * Video not playable: Paid / Movie / Private / Age-restricted.
         * Note: The 'Authorization' key must be excluded from the header.
         * For some reason, PoToken is not required.
         */
        val ANDROID_NO_SDK = YouTubeClient(
            clientName = "ANDROID",
            clientVersion = "21.03.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
            friendlyName = "Android No SDK",
            loginSupported = false,
            useSignatureTimestamp = false
        )

        val ANDROID_VR_NO_AUTH = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            loginSupported = false,
            useSignatureTimestamp = false
        )

        /**
         * Video not playable: Kids / Paid / Movie / Private / Age-restricted.
         * This client can only be used when logged out.
         */
        val ANDROID_VR_1_61_48 = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            buildId = "SQ3A.220605.009.A1",
            cronetVersion = "132.0.6808.3",
            packageName = "com.google.android.apps.youtube.vr.oculus",
            friendlyName = "Android VR 1.61",
            loginSupported = false,
            useSignatureTimestamp = false
        )

        /**
         * Current ANDROID_VR pin, matching yt-dlp master and YouTube.js.
         *
         * [ANDROID_VR_1_43_32] and [ANDROID_VR_1_61_48] are bot-gated **by client version**:
         * measured on three videoIds, both return `LOGIN_REQUIRED / "Sign in to confirm you're not
         * a bot"` with zero formats, anonymously *and* signed in, with or without visitorData, and
         * with the device fields stripped. Only the version differs here, and 1.65.10 returns
         * `OK` with 100% direct-url formats — so this is a server-side version gate, not a
         * malformed request.
         *
         * Note the deliberate omissions versus the older two: no `buildId`, no `cronetVersion`,
         * no `packageName`, `osVersion` is `"12L"` (not `"12"`), and the user agent is the
         * `... gzip` form rather than the Cronet form. Those are the values yt-dlp and YouTube.js
         * both pin, byte-for-byte. A Cronet-style variant was probed and behaves identically, so
         * the UA is not the discriminator — but there is no reason to diverge from upstream.
         *
         * Requires a visitorData: without one it returns LOGIN_REQUIRED like its older siblings.
         * See the X-Goog-Visitor-Id note in `InnerTube.ytClient`.
         */
        val ANDROID_VR_1_65_10 = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            friendlyName = "Android VR 1.65",
            loginSupported = false,
            useSignatureTimestamp = false
        )

        /**
         * Uses non adaptive bitrate, which fixes audio stuttering with YT Music.
         * Does not use AV1.
         */
        val ANDROID_VR_1_43_32 = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            buildId = "SQ3A.220605.009.A1",
            cronetVersion = "107.0.5284.2",
            packageName = "com.google.android.apps.youtube.vr.oculus",
            friendlyName = "Android VR 1.43",
            loginSupported = false,
            useSignatureTimestamp = false
        )

        /**
         * Cannot play livestreams and lacks HDR, but can play videos with music and labeled "for children".
         */
        val ANDROID_CREATOR = YouTubeClient(
            clientName = "ANDROID_CREATOR",
            clientVersion = "25.03.101",
            clientId = "14",
            userAgent = "com.google.android.apps.youtube.creator/25.03.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
            osName = "Android",
            osVersion = "15",
            deviceMake = "Google",
            deviceModel = "Pixel 9 Pro Fold",
            androidSdkVersion = "35",
            buildId = "AP3A.241005.015.A2",
            cronetVersion = "132.0.6779.0",
            packageName = "com.google.android.apps.youtube.creator",
            friendlyName = "Android Studio",
            loginSupported = true,
            useSignatureTimestamp = true
        )

        /**
         * Internal YT client for an unreleased YT client. May stop working at any time.
         */
        val VISIONOS = YouTubeClient(
            clientName = "VISIONOS",
            clientVersion = "0.1",
            clientId = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            osName = "visionOS",
            osVersion = "1.3.21O771",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            friendlyName = "visionOS",
            loginSupported = false,
            useSignatureTimestamp = false
        )

        /**
         * The device machine id for the iPad 6th Gen (iPad7,6).
         * AV1 hardware decoding is not supported.
         */
        val IPADOS = YouTubeClient(
            clientName = "IOS",
            clientVersion = "21.03.3",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
            osName = "iPadOS",
            osVersion = "17.7.10.21H450",
            deviceMake = "Apple",
            deviceModel = "iPad7,6",
            friendlyName = "iPadOS",
            loginSupported = false,
            useSignatureTimestamp = false,
            packageName = "com.google.ios.youtube"
        )
    }
}
