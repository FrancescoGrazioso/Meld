/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify login using an embedded WebView.
 * Loads Spotify's web login page, which supports all auth methods
 * (email/password, Facebook, Google, Apple). After successful login,
 * the sp_dc and sp_key cookies are extracted and used to obtain
 * internal access tokens — no Spotify Developer Client ID required.
 *
 * Token acquisition strategy:
 * 1. User logs in via WebView, landing on open.spotify.com
 * 2. sp_dc/sp_key cookies are extracted
 * 3. JavaScript hooks on fetch() and XMLHttpRequest intercept
 *    the web player's /api/token call during initialization
 * 4. A direct JS fetch to /api/token acts as reliable fallback
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.metrolist.spotify.models.SpotifyInternalToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isExchangingToken by remember { mutableStateOf(false) }

    val tokenBridge = remember {
        SpotifyTokenBridge(context, scope, navController) { exchanging ->
            isExchangingToken = exchanging
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.spotify_login)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        if (isLoading || isExchangingToken) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(ctx).apply {
                        tokenBridge.webViewRef = this
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = USER_AGENT_DESKTOP

                        @SuppressLint("JavascriptInterface")
                        addJavascriptInterface(tokenBridge, "MetrolistBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                Timber.d("SpotifyLogin: page started: $url")
                                if (url?.startsWith("https://open.spotify.com") == true) {
                                    installNetworkInterceptors(view)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                Timber.d("SpotifyLogin: page finished: $url")
                                if (!isExchangingToken) {
                                    tryExtractCookiesAndPollToken(view, url, tokenBridge) {
                                        isExchangingToken = it
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val requestUrl = request?.url?.toString()
                                Timber.d("SpotifyLogin: navigating to: $requestUrl")
                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                if (reqUrl.contains("/api/token") || reqUrl.contains("get_access_token")) {
                                    Timber.d("SpotifyLogin: [INTERCEPT] TOKEN-URL=$reqUrl")
                                    if (reqUrl.contains("reason=init") && reqUrl.contains("totp=")) {
                                        tokenBridge.capturedTokenUrl = reqUrl
                                        Timber.d("SpotifyLogin: [INTERCEPT] Captured TOTP URL for JS fallback")
                                    }
                                }
                                return null
                            }
                        }

                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                },
            )

            if (isExchangingToken) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.padding())
                        Text(
                            text = stringResource(R.string.spotify_logging_in),
                            modifier = Modifier.padding(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hooks both window.fetch and XMLHttpRequest.prototype before any page
 * scripts execute. This intercepts whichever mechanism the Spotify web
 * player uses to obtain its access token.
 */
private fun installNetworkInterceptors(view: WebView?) {
    if (view == null) return
    Timber.d("SpotifyLogin: installing fetch+XHR interceptors")
    view.evaluateJavascript(
        """
        (function() {
            if (window.__metrolistHooked) return;
            window.__metrolistHooked = true;
            window.__metrolistDone = false;

            function handleToken(body) {
                try {
                    var data = JSON.parse(body);
                    if (data.accessToken && !data.isAnonymous) {
                        window.__metrolistDone = true;
                        MetrolistBridge.onTokenResult(body);
                        return true;
                    }
                } catch(e) {}
                return false;
            }

            function isTokenUrl(u) {
                return u.indexOf('/api/token') !== -1 || u.indexOf('get_access_token') !== -1;
            }

            var origFetch = window.fetch;
            window.fetch = function() {
                var urlArg = arguments[0];
                var urlStr = (typeof urlArg === 'string') ? urlArg : (urlArg && urlArg.url ? urlArg.url : '');
                return origFetch.apply(this, arguments).then(function(response) {
                    if (!window.__metrolistDone && isTokenUrl(urlStr) && response.ok) {
                        response.clone().text().then(function(body) { handleToken(body); });
                    }
                    return response;
                });
            };

            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function() {
                this._mUrl = (arguments[1] || '').toString();
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                var xhr = this;
                if (isTokenUrl(xhr._mUrl)) {
                    xhr.addEventListener('load', function() {
                        if (!window.__metrolistDone && xhr.status === 200) {
                            handleToken(xhr.responseText);
                        }
                    });
                }
                return origSend.apply(this, arguments);
            };
        })();
        """.trimIndent(),
        null,
    )
}

/**
 * Extracts sp_dc/sp_key cookies once the WebView reaches open.spotify.com,
 * then polls the page DOM and storage for the access token that the Spotify
 * web player obtains during initialization.
 */
private fun tryExtractCookiesAndPollToken(
    view: WebView?,
    url: String?,
    bridge: SpotifyTokenBridge,
    setExchanging: (Boolean) -> Unit,
) {
    if (url == null || !url.startsWith("https://open.spotify.com")) return
    if (view == null) return

    val cookieManager = CookieManager.getInstance()
    val allCookies = cookieManager.getCookie("https://open.spotify.com")
    Timber.d("SpotifyLogin: checking cookies for open.spotify.com: $allCookies")

    if (allCookies.isNullOrBlank()) {
        Timber.w("SpotifyLogin: no cookies found yet")
        return
    }

    val cookieMap = allCookies.split(";")
        .associate { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }

    Timber.d("SpotifyLogin: cookie keys: ${cookieMap.keys}")

    val spDc = cookieMap["sp_dc"]
    if (spDc.isNullOrBlank()) {
        Timber.w("SpotifyLogin: sp_dc not found, keys present: ${cookieMap.keys}")
        return
    }

    val spKey = cookieMap["sp_key"] ?: ""
    Timber.d("SpotifyLogin: sp_dc found (${spDc.take(8)}...), polling for token...")

    setExchanging(true)
    bridge.spDc = spDc
    bridge.spKey = spKey

    view.evaluateJavascript(
        """
        (function() {
            if (window.__metrolistDone) return;

            function handleToken(body) {
                try {
                    var data = (typeof body === 'string') ? JSON.parse(body) : body;
                    if (data.accessToken && data.accessToken.length > 50 && !data.isAnonymous) {
                        window.__metrolistDone = true;
                        var json = (typeof body === 'string') ? body : JSON.stringify(data);
                        MetrolistBridge.onTokenResult(json);
                        return true;
                    }
                } catch(e) {
                    MetrolistBridge.onTokenError('handleToken parse error: ' + e.message);
                }
                return false;
            }

            var attempts = 0;
            var maxAttempts = 6;

            function tryFetchToken() {
                if (window.__metrolistDone) return;
                attempts++;

                var capturedUrl = MetrolistBridge.getTokenUrl();
                var fetchUrl = capturedUrl ? capturedUrl : '/api/token?reason=transport&productType=web_player';
                MetrolistBridge.onTokenError('poll attempt ' + attempts + ' url=' + fetchUrl.substring(0, 70));

                fetch(fetchUrl, { credentials: 'include' })
                    .then(function(r) {
                        if (!r.ok) throw new Error('HTTP ' + r.status);
                        return r.text();
                    })
                    .then(function(body) {
                        if (!window.__metrolistDone) {
                            MetrolistBridge.onTokenError('fetch response: ' + body.substring(0, 100));
                            handleToken(body);
                        }
                    })
                    .catch(function(e) {
                        MetrolistBridge.onTokenError('fetch /api/token failed: ' + e.message);
                        if (!window.__metrolistDone && attempts < maxAttempts) {
                            setTimeout(tryFetchToken, 2000);
                        } else if (!window.__metrolistDone) {
                            MetrolistBridge.onTokenError('Token not found after ' + maxAttempts + ' fetch attempts');
                        }
                    });
            }

            setTimeout(tryFetchToken, 3000);
        })();
        """.trimIndent(),
        null,
    )
}

/**
 * JavaScript bridge that receives the Spotify access token from the WebView.
 * Methods annotated with @JavascriptInterface are called from JS on a
 * background thread managed by WebView.
 */
private class SpotifyTokenBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val navController: NavController,
    private val setExchanging: (Boolean) -> Unit,
) {
    var spDc: String = ""
    var spKey: String = ""

    @Volatile
    var capturedTokenUrl: String = ""

    @Volatile
    var webViewRef: WebView? = null

    @Volatile
    private var tokenProcessed = false

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @JavascriptInterface
    fun onTokenResult(tokenJson: String) {
        if (tokenProcessed) return
        tokenProcessed = true
        Timber.d("SpotifyLogin: received token response (${tokenJson.length} chars)")

        // Kill the WebView immediately to stop the Spotify web player from
        // making further API calls that eat into the rolling 30s rate limit.
        scope.launch(Dispatchers.Main) {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                Timber.d("SpotifyLogin: WebView stopped to free rate-limit budget")
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val token = json.decodeFromString<SpotifyInternalToken>(tokenJson)

                if (token.isAnonymous || token.accessToken.isBlank()) {
                    throw IllegalStateException("Received anonymous token — sp_dc cookie is invalid or expired")
                }

                Timber.d("SpotifyLogin: access token obtained (anonymous=${token.isAnonymous})")
                Spotify.accessToken = token.accessToken

                // Save cookies now but NOT the access token yet.
                // Saving the token triggers isSpotifyActive=true, which makes
                // library screens call loadAll() — we need the WebView's rate
                // limit budget to drain first.
                context.dataStore.edit { prefs ->
                    prefs[SpotifySpDcKey] = spDc
                    prefs[SpotifySpKeyKey] = spKey
                }

                // Wait for the web player's in-flight requests to fall out of
                // Spotify's rolling 30-second rate-limit window.
                Timber.d("SpotifyLogin: waiting for rate-limit window to clear...")
                delay(5000)

                var profileFetched = false
                for (attempt in 1..3) {
                    Spotify.me().onSuccess { user ->
                        Timber.d("SpotifyLogin: logged in as ${user.displayName} (${user.id})")
                        context.dataStore.edit { prefs ->
                            prefs[SpotifyUsernameKey] = user.displayName ?: user.id
                            prefs[SpotifyUserIdKey] = user.id
                        }
                        profileFetched = true
                    }.onFailure { e ->
                        val is429 = e is Spotify.SpotifyException && e.statusCode == 429
                        if (is429 && attempt < 3) {
                            Timber.w("SpotifyLogin: me() got 429, retrying in ${attempt * 5}s")
                            delay(attempt * 5000L)
                        } else {
                            Timber.e(e, "SpotifyLogin: failed to fetch user profile")
                        }
                    }
                    if (profileFetched) break
                }

                // NOW persist the access token — this flips isSpotifyActive and
                // triggers loadAll() in the library screens we navigate back to.
                context.dataStore.edit { prefs ->
                    prefs[SpotifyAccessTokenKey] = token.accessToken
                    prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
                }

                scope.launch(Dispatchers.Main) {
                    navController.navigateUp()
                }
            } catch (e: Exception) {
                Timber.e(e, "SpotifyLogin: token parsing/processing failed")
                scope.launch(Dispatchers.Main) { setExchanging(false) }
            }
        }
    }

    @JavascriptInterface
    fun getTokenUrl(): String = capturedTokenUrl

    @JavascriptInterface
    fun onTokenError(errorMessage: String) {
        if (errorMessage.startsWith("poll attempt") ||
            errorMessage.startsWith("fetch response") ||
            errorMessage.startsWith("handleToken parse")
        ) {
            Timber.d("SpotifyLogin: $errorMessage")
            return
        }
        Timber.e("SpotifyLogin: WebView token fetch error: $errorMessage")
        scope.launch(Dispatchers.Main) { setExchanging(false) }
    }
}

/**
 * Desktop Chrome User-Agent. Using desktop UA is critical because:
 * - Facebook's mobile JS has compatibility issues with Android WebView
 * - Spotify and social login providers render more stable desktop pages
 */
private const val USER_AGENT_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
