<#
.SYNOPSIS
    Standalone reproducer/baseline for the "HTTP 403 during playback" reports.

.DESCRIPTION
    Replicates, byte for byte, what the app does at playback time:

      1. POST https://music.youtube.com/youtubei/v1/player with the exact headers and
         context that innertube/InnerTube.kt#ytClient + YouTubeClient.toContext build,
         once per client in YTPlayerUtils.STREAM_FALLBACK_CLIENTS (+ MAIN_CLIENT).
      2. Pick the best *audio* adaptiveFormat the same way YTPlayerUtils.findFormat does.
      3. Issue the media GET the same way ExoPlayer/OkHttpDataSource does
         (MusicService.createCacheDataSource -> OkHttpDataSource.Factory with NO
         User-Agent / Origin / Cookie / visitor headers, Range: bytes=0-524287
         because of `.subrange(0, CHUNK_LENGTH)` in MusicService.createDataSourceFactory).
      4. Record the status code for each (client x visitorData x User-Agent x Range)
         combination and print a matrix.

    Nothing here writes to the app or its caches. Read-only network probing.

.PARAMETER VideoId
    Video ids to probe. Default: a small mixed set (normal ATV track, popular MV).

.PARAMETER PoTokenSession
    Optional "cold start"/session-bound poToken (bound to visitorData). In the app this
    is PoTokenGenerator -> generatePoToken(sessionId). Appended as `pot=` to stream URLs.

.PARAMETER PoTokenVideo
    Optional videoId-bound poToken. In the app this is generatePoToken(videoId). Sent in
    the /player body as serviceIntegrityDimensions.poToken.

.PARAMETER Cookie
    Optional raw YouTube cookie string ("SID=...; HSID=...; SAPISID=...; ..."), to compare
    logged-in vs anonymous. When given, SAPISIDHASH auth is computed like InnerTube.kt does.

.PARAMETER SignatureTimestamp
    Optional signature timestamp (sts) to send in playbackContext, as
    YTPlayerUtils.getSignatureTimestampOrNull would obtain from NewPipe.
    When omitted the script scrapes it from the live player JS.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\diagnose-yt-403.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\diagnose-yt-403.ps1 -VideoId dQw4w9WgXcQ -Verbose
#>
[CmdletBinding()]
param(
    [string[]] $VideoId = @('dQw4w9WgXcQ'),
    [string]   $PoTokenSession,
    [string]   $PoTokenVideo,
    [string]   $Cookie,
    [int]      $SignatureTimestamp = 0,
    [switch]   $NoVisitorDataVariant,
    [switch]   $Deep,
    [string]   $JsonOut
)

$ErrorActionPreference = 'Continue'
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
[System.Net.ServicePointManager]::Expect100Continue = $false

# --------------------------------------------------------------------------------------
# Constants mirrored from the repo
# --------------------------------------------------------------------------------------
$API      = 'https://music.youtube.com/youtubei/v1/player?prettyPrint=false'
$ORIGIN   = 'https://music.youtube.com'          # YouTubeClient.ORIGIN_YOUTUBE_MUSIC
$REFERER  = 'https://music.youtube.com/'         # YouTubeClient.REFERER_YOUTUBE_MUSIC
$UA_WEB   = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0'
# What ExoPlayer actually sends: MusicService builds OkHttpDataSource.Factory(OkHttpClient)
# without calling setUserAgent(), so OkHttp's own default UA goes out on the media GET.
$UA_EXO   = 'okhttp/4.12.0'
$CHUNK    = 524288                                # MusicService.CHUNK_LENGTH = 512 * 1024

# innertube/src/main/kotlin/com/metrolist/innertube/models/YouTubeClient.kt
$CLIENTS = @(
    @{ key='WEB_REMIX';                      clientName='WEB_REMIX';                      clientVersion='1.20260213.01.00'; clientId='67'; ua=$UA_WEB; loginSupported=$true;  loginRequired=$false; useSignatureTimestamp=$true;  isEmbedded=$false; useWebPoTokens=$true  }
    @{ key='TVHTML5_SIMPLY_EMBEDDED_PLAYER'; clientName='TVHTML5_SIMPLY_EMBEDDED_PLAYER'; clientVersion='2.0';              clientId='85'; ua='Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15'; loginSupported=$true; loginRequired=$false; useSignatureTimestamp=$true; isEmbedded=$true; useWebPoTokens=$false }
    @{ key='TVHTML5';                        clientName='TVHTML5';                        clientVersion='7.20260213.00.00'; clientId='7';  ua='Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15'; loginSupported=$true; loginRequired=$true; useSignatureTimestamp=$true; isEmbedded=$false; useWebPoTokens=$true }
    @{ key='ANDROID_VR_1_43_32';             clientName='ANDROID_VR';                     clientVersion='1.43.32';          clientId='28'; ua='com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)'; osName='Android'; osVersion='12'; deviceMake='Oculus'; deviceModel='Quest 3'; androidSdkVersion='32'; loginSupported=$false; loginRequired=$false; useSignatureTimestamp=$false; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='ANDROID_VR_1_61_48';             clientName='ANDROID_VR';                     clientVersion='1.61.48';          clientId='28'; ua='com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)'; osName='Android'; osVersion='12'; deviceMake='Oculus'; deviceModel='Quest 3'; androidSdkVersion='32'; loginSupported=$false; loginRequired=$false; useSignatureTimestamp=$false; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='ANDROID_CREATOR';                clientName='ANDROID_CREATOR';                clientVersion='25.03.101';        clientId='14'; ua='com.google.android.apps.youtube.creator/25.03.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)'; osName='Android'; osVersion='15'; deviceMake='Google'; deviceModel='Pixel 9 Pro Fold'; androidSdkVersion='35'; loginSupported=$true; loginRequired=$false; useSignatureTimestamp=$true; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='IPADOS';                         clientName='IOS';                            clientVersion='21.03.3';          clientId='5';  ua='com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)'; osName='iPadOS'; osVersion='17.7.10.21H450'; deviceMake='Apple'; deviceModel='iPad7,6'; loginSupported=$false; loginRequired=$false; useSignatureTimestamp=$false; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='ANDROID_VR_NO_AUTH';             clientName='ANDROID_VR';                     clientVersion='1.61.48';          clientId='28'; ua='com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)'; loginSupported=$false; loginRequired=$false; useSignatureTimestamp=$false; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='MOBILE(ANDROID)';                clientName='ANDROID';                        clientVersion='21.03.38';         clientId='3';  ua='com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip'; loginSupported=$true; loginRequired=$false; useSignatureTimestamp=$true; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='IOS';                            clientName='IOS';                            clientVersion='21.03.1';          clientId='5';  ua='com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)'; osVersion='18.2.22C152'; loginSupported=$false; loginRequired=$false; useSignatureTimestamp=$false; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='WEB';                            clientName='WEB';                            clientVersion='2.20260213.00.00'; clientId='1';  ua=$UA_WEB; loginSupported=$false; loginRequired=$false; useSignatureTimestamp=$false; isEmbedded=$false; useWebPoTokens=$false }
    @{ key='WEB_CREATOR';                    clientName='WEB_CREATOR';                    clientVersion='1.20260213.00.00'; clientId='62'; ua=$UA_WEB; loginSupported=$true; loginRequired=$true; useSignatureTimestamp=$true; isEmbedded=$false; useWebPoTokens=$false }
)

# --------------------------------------------------------------------------------------
# HTTP helpers (HttpWebRequest: full control over restricted headers + status on error)
# --------------------------------------------------------------------------------------
function Invoke-Raw {
    param(
        [string]$Url,
        [string]$Method = 'GET',
        [string]$Body,
        [string]$ContentType,
        [string]$UserAgent,
        [string]$Referer,
        [hashtable]$Headers,
        [int64]$RangeFrom = -1,
        [int64]$RangeTo = -1,
        [int]$TimeoutMs = 25000,
        [switch]$NoBody
    )
    $result = [ordered]@{ status = -1; err = $null; body = $null; headers = @{} }
    try {
        $req = [System.Net.HttpWebRequest]::Create($Url)
        $req.Method = $Method
        $req.Timeout = $TimeoutMs
        $req.ReadWriteTimeout = $TimeoutMs
        $req.AllowAutoRedirect = $true
        $req.AutomaticDecompression = [System.Net.DecompressionMethods]::GZip -bor [System.Net.DecompressionMethods]::Deflate
        if ($UserAgent) { $req.UserAgent = $UserAgent }
        if ($Referer)   { $req.Referer   = $Referer }
        if ($ContentType) { $req.ContentType = $ContentType }
        # HttpWebRequest refuses restricted headers via Headers[] -> route them to properties.
        if ($Headers) {
            foreach ($k in $Headers.Keys) {
                if (-not $Headers[$k]) { continue }
                $v = [string]$Headers[$k]
                switch ($k.ToLower()) {
                    'accept'         { $req.Accept = $v }
                    'content-type'   { $req.ContentType = $v }
                    'referer'        { $req.Referer = $v }
                    'user-agent'     { $req.UserAgent = $v }
                    'host'           { }
                    'connection'     { }
                    'content-length' { }
                    'range'          { }
                    default          { $req.Headers[$k] = $v }
                }
            }
        }
        if ($RangeFrom -ge 0) {
            if ($RangeTo -ge 0) { $req.AddRange([int64]$RangeFrom, [int64]$RangeTo) } else { $req.AddRange([int64]$RangeFrom) }
        }
        if ($Body) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
            $req.ContentLength = $bytes.Length
            $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
        }
        $resp = $req.GetResponse()
        $result.status = [int]$resp.StatusCode
        foreach ($h in $resp.Headers.AllKeys) { $result.headers[$h] = $resp.Headers[$h] }
        if (-not $NoBody) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $result.body = $sr.ReadToEnd(); $sr.Close()
        }
        $resp.Close()
    } catch [System.Net.WebException] {
        $we = $_.Exception
        if ($we.Response) {
            $result.status = [int]$we.Response.StatusCode
            foreach ($h in $we.Response.Headers.AllKeys) { $result.headers[$h] = $we.Response.Headers[$h] }
            try {
                $sr = New-Object System.IO.StreamReader($we.Response.GetResponseStream())
                $result.body = $sr.ReadToEnd(); $sr.Close()
            } catch {}
            $we.Response.Close()
        } else {
            $result.err = $we.Message
        }
    } catch {
        $result.err = $_.Exception.Message
    }
    return $result
}

function Get-VisitorData {
    Write-Verbose 'Fetching visitorData from https://music.youtube.com/sw.js_data'
    $r = Invoke-Raw -Url 'https://music.youtube.com/sw.js_data' -UserAgent $UA_WEB -Referer $REFERER
    if ($r.status -ne 200 -or -not $r.body) { return $null }
    try {
        $json = $r.body.Substring(5) | ConvertFrom-Json
        foreach ($cand in $json[0][2]) {
            if ($cand -is [string] -and $cand -match '^Cg[ts]') { return $cand }
        }
    } catch { Write-Verbose "visitorData parse failed: $_" }
    return $null
}

function Get-SignatureTimestamp {
    # Same value NewPipe's YoutubeJavaScriptPlayerManager.getSignatureTimestamp returns:
    # scraped straight out of the live player JS (`signatureTimestamp:NNNNN`).
    Write-Verbose 'Scraping signatureTimestamp from player JS'
    $iframe = Invoke-Raw -Url 'https://www.youtube.com/iframe_api' -UserAgent $UA_WEB
    if ($iframe.status -ne 200) { return 0 }
    if ($iframe.body -notmatch 'player\\?/([a-zA-Z0-9_-]{8})\\?/') { return 0 }
    $hash = $Matches[1]
    $jsUrl = "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_US/base.js"
    $js = Invoke-Raw -Url $jsUrl -UserAgent $UA_WEB
    if ($js.status -ne 200) { return 0 }
    if ($js.body -match 'signatureTimestamp[:=](\d+)') { return [int]$Matches[1] }
    return 0
}

function Get-SapisidHash([string]$cookieStr) {
    if (-not $cookieStr) { return $null }
    $sapisid = $null
    foreach ($p in $cookieStr.Split(';')) {
        $kv = $p.Trim().Split('=', 2)
        if ($kv.Length -eq 2 -and $kv[0] -eq 'SAPISID') { $sapisid = $kv[1] }
    }
    if (-not $sapisid) { return $null }
    $t = [int64]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes("$t $sapisid $ORIGIN")
    $hex = ($sha1.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    return "SAPISIDHASH ${t}_${hex}"
}

# --------------------------------------------------------------------------------------
# /player call, mirroring InnerTube.player()
# --------------------------------------------------------------------------------------
function Invoke-Player {
    param([hashtable]$Client, [string]$Vid, [string]$VisitorData, [int]$Sts, [string]$PoToken, [string]$CookieStr)

    $clientObj = [ordered]@{
        clientName    = $Client.clientName
        clientVersion = $Client.clientVersion
    }
    foreach ($f in @('osName','osVersion','deviceMake','deviceModel','androidSdkVersion')) {
        if ($Client.ContainsKey($f) -and $Client[$f]) { $clientObj[$f] = $Client[$f] }
    }
    $clientObj['gl'] = 'US'
    $clientObj['hl'] = 'en-US'
    if ($VisitorData) { $clientObj['visitorData'] = $VisitorData }

    $context = [ordered]@{
        client  = $clientObj
        request = [ordered]@{ internalExperimentFlags = @(); useSsl = $true }
        user    = [ordered]@{ lockedSafetyMode = $false }
    }
    if ($Client.isEmbedded) {
        $context['thirdParty'] = [ordered]@{ embedUrl = "https://www.youtube.com/watch?v=$Vid" }
    }

    $body = [ordered]@{
        context        = $context
        videoId        = $Vid
        contentCheckOk = $true
        racyCheckOk    = $true
    }
    if ($Client.useSignatureTimestamp -and $Sts -gt 0) {
        $body['playbackContext'] = [ordered]@{ contentPlaybackContext = [ordered]@{ signatureTimestamp = $Sts } }
    }
    if ($Client.useWebPoTokens -and $PoToken) {
        $body['serviceIntegrityDimensions'] = [ordered]@{ poToken = $PoToken }
    }

    $headers = @{
        'X-Goog-Api-Format-Version' = '1'
        'X-YouTube-Client-Name'     = $Client.clientId
        'X-YouTube-Client-Version'  = $Client.clientVersion
        'X-Origin'                  = $ORIGIN
        'Accept'                    = 'application/json'
        'Accept-Language'           = 'en-US,US;q=0.9,en;q=0.8'
        'Cache-Control'             = 'no-cache'
    }
    if ($VisitorData) { $headers['X-Goog-Visitor-Id'] = $VisitorData }
    if ($CookieStr -and $Client.loginSupported) {
        $headers['Cookie'] = $CookieStr
        $auth = Get-SapisidHash $CookieStr
        if ($auth) { $headers['Authorization'] = $auth }
    }

    $json = $body | ConvertTo-Json -Depth 12 -Compress
    return Invoke-Raw -Url $API -Method 'POST' -Body $json -ContentType 'application/json' `
        -UserAgent $Client.ua -Referer $REFERER -Headers $headers
}

# --------------------------------------------------------------------------------------
# Format selection, mirroring YTPlayerUtils.findFormat (AudioQuality.AUTO, unmetered)
# --------------------------------------------------------------------------------------
function Select-AudioFormat($streamingData) {
    if (-not $streamingData -or -not $streamingData.adaptiveFormats) { return $null }
    $cands = @()
    foreach ($f in $streamingData.adaptiveFormats) {
        $isAudio = -not ($f.PSObject.Properties.Name -contains 'width' -and $null -ne $f.width)
        $isOriginal = $true
        if ($f.PSObject.Properties.Name -contains 'audioTrack' -and $f.audioTrack) {
            if ($f.audioTrack.PSObject.Properties.Name -contains 'isAutoDubbed' -and $null -ne $f.audioTrack.isAutoDubbed) { $isOriginal = $false }
        }
        if ($isAudio -and $isOriginal) { $cands += $f }
    }
    if ($cands.Count -eq 0) { return $null }
    $best = $null; $bestScore = [double]::NegativeInfinity
    foreach ($f in $cands) {
        $score = [double]$f.bitrate
        if ("$($f.mimeType)".StartsWith('audio/webm')) { $score += 10240 }
        if ($score -gt $bestScore) { $bestScore = $score; $best = $f }
    }
    return $best
}

function Get-UrlParam([string]$url, [string]$name) {
    if (-not $url) { return $null }
    $m = [regex]::Match($url, "[?&]$name=([^&]*)")
    if ($m.Success) { return [uri]::UnescapeDataString($m.Groups[1].Value) }
    return $null
}

# --------------------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------------------
Write-Host ''
Write-Host '=========================================================================' -ForegroundColor Cyan
Write-Host ' Meld / Metrolist - YouTube playback 403 diagnostics' -ForegroundColor Cyan
Write-Host " $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')" -ForegroundColor Cyan
Write-Host '=========================================================================' -ForegroundColor Cyan

$visitorData = Get-VisitorData
if ($visitorData) {
    Write-Host "visitorData      : OK (${visitorData})".Substring(0, [Math]::Min(110, "visitorData      : OK (${visitorData})".Length)) -ForegroundColor Green
} else {
    Write-Host 'visitorData      : FAILED to obtain' -ForegroundColor Yellow
}

$sts = $SignatureTimestamp
if ($sts -le 0) { $sts = Get-SignatureTimestamp }
if ($sts -gt 0) { Write-Host "signatureTimestamp: $sts" -ForegroundColor Green }
else            { Write-Host 'signatureTimestamp: FAILED to obtain (sent as absent)' -ForegroundColor Yellow }

Write-Host "poToken (session): $(if ($PoTokenSession) { 'supplied' } else { 'NOT supplied' })"
Write-Host "poToken (video)  : $(if ($PoTokenVideo) { 'supplied' } else { 'NOT supplied' })"
Write-Host "cookie           : $(if ($Cookie) { 'supplied (logged-in run)' } else { 'NOT supplied (anonymous run)' })"
Write-Host ''

$rows = New-Object System.Collections.ArrayList

$visitorVariants = @(@{ name='visitorData=YES'; value=$visitorData })
if (-not $NoVisitorDataVariant) { $visitorVariants += @{ name='visitorData=NO'; value=$null } }

foreach ($vid in $VideoId) {
    Write-Host "#########################################################################" -ForegroundColor White
    Write-Host "# videoId: $vid" -ForegroundColor White
    Write-Host "#########################################################################" -ForegroundColor White

    foreach ($vv in $visitorVariants) {
        if (-not $vv.value -and $vv.name -eq 'visitorData=YES') { continue }
        Write-Host ''
        Write-Host "--- $($vv.name) ---" -ForegroundColor Magenta

        foreach ($c in $CLIENTS) {
            $row = [ordered]@{
                videoId = $vid; client = $c.key; visitorData = $vv.name
                playerHttp = $null; playability = $null; reason = $null
                formats = 0; itag = $null; mime = $null
                hasUrl = $false; hasSigCipher = $false; hasN = $false; hasPot = $false
                sabr = $false; expiresIn = $null; expireDeltaSec = $null
                getExoUA = $null; getClientUA = $null; getChunkRange = $null; note = $null
            }

            $poForThisClient = $PoTokenVideo
            $resp = Invoke-Player -Client $c -Vid $vid -VisitorData $vv.value -Sts $sts -PoToken $poForThisClient -CookieStr $Cookie
            $row.playerHttp = $resp.status

            if ($resp.status -ne 200 -or -not $resp.body) {
                $row.note = "player HTTP $($resp.status) $($resp.err)"
                [void]$rows.Add([pscustomobject]$row)
                Write-Host ("{0,-32} player={1,-4} {2}" -f $c.key, $resp.status, $row.note) -ForegroundColor Red
                continue
            }

            $pr = $null
            try { $pr = $resp.body | ConvertFrom-Json } catch { $row.note = 'json parse failed' }
            if (-not $pr) { [void]$rows.Add([pscustomobject]$row); continue }

            $row.playability = $pr.playabilityStatus.status
            $row.reason      = $pr.playabilityStatus.reason
            $sd = $pr.streamingData
            if ($sd) {
                if ($sd.PSObject.Properties.Name -contains 'serverAbrStreamingUrl' -and $sd.serverAbrStreamingUrl) { $row.sabr = $true }
                if ($sd.PSObject.Properties.Name -contains 'expiresInSeconds') { $row.expiresIn = $sd.expiresInSeconds }
                if ($sd.adaptiveFormats) { $row.formats = @($sd.adaptiveFormats).Count }
            }

            $fmt = Select-AudioFormat $sd
            if (-not $fmt) {
                $row.note = 'no audio adaptiveFormat'
                [void]$rows.Add([pscustomobject]$row)
                Write-Host ("{0,-32} player=200 status={1,-16} NO AUDIO FORMAT (sabr={2})" -f $c.key, $row.playability, $row.sabr) -ForegroundColor Yellow
                continue
            }

            $row.itag = $fmt.itag
            $row.mime = "$($fmt.mimeType)".Split(';')[0]
            $url = $null
            if ($fmt.PSObject.Properties.Name -contains 'url' -and $fmt.url) { $url = $fmt.url; $row.hasUrl = $true }
            if ($fmt.PSObject.Properties.Name -contains 'signatureCipher' -and $fmt.signatureCipher) { $row.hasSigCipher = $true }

            if (-not $url) {
                $row.note = 'format has NO url (signatureCipher only -> needs sig+n deobfuscation)'
                [void]$rows.Add([pscustomobject]$row)
                Write-Host ("{0,-32} player=200 status={1,-16} itag={2,-4} URL=NONE sigCipher={3}" -f $c.key, $row.playability, $row.itag, $row.hasSigCipher) -ForegroundColor Yellow
                continue
            }

            $row.hasN   = [bool](Get-UrlParam $url 'n')
            $row.hasPot = [bool](Get-UrlParam $url 'pot')
            $expire = Get-UrlParam $url 'expire'
            if ($expire) {
                $row.expireDeltaSec = [int64]$expire - [int64]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
            }

            # Optionally append the session poToken exactly like YTPlayerUtils does
            $probeUrl = $url
            if ($PoTokenSession -and -not $row.hasPot) {
                $sep = '&'; if ($probeUrl -notmatch '\?') { $sep = '?' }
                $probeUrl = "$probeUrl$sep" + 'pot=' + [uri]::EscapeDataString($PoTokenSession)
            }

            # (a) exactly what ExoPlayer does today: okhttp default UA, no extra headers,
            #     Range: bytes=0-524287 (MusicService .subrange(0, CHUNK_LENGTH))
            $a = Invoke-Raw -Url $probeUrl -UserAgent $UA_EXO -RangeFrom 0 -RangeTo ($CHUNK - 1) -NoBody -TimeoutMs 20000
            $row.getChunkRange = $a.status

            # (b) same but tiny range (isolates Range-size effects)
            $b = Invoke-Raw -Url $probeUrl -UserAgent $UA_EXO -RangeFrom 0 -RangeTo 1 -NoBody -TimeoutMs 20000
            $row.getExoUA = $b.status

            # (c) tiny range but with the *declared client's* UA (isolates UA mismatch)
            $d = Invoke-Raw -Url $probeUrl -UserAgent $c.ua -RangeFrom 0 -RangeTo 1 -NoBody -TimeoutMs 20000
            $row.getClientUA = $d.status

            [void]$rows.Add([pscustomobject]$row)

            $color = 'Green'
            if ($row.getExoUA -eq 403 -or $row.getChunkRange -eq 403) { $color = 'Red' }
            elseif ($row.getExoUA -ne 200 -and $row.getExoUA -ne 206) { $color = 'Yellow' }
            Write-Host ("{0,-32} player=200 status={1,-8} itag={2,-4} n={3,-5} pot={4,-5} sabr={5,-5} exp={6,5}s  GET[okhttpUA,512k]={7,-4} GET[okhttpUA,0-1]={8,-4} GET[clientUA,0-1]={9,-4}" -f `
                $c.key, $row.playability, $row.itag, $row.hasN, $row.hasPot, $row.sabr, $row.expireDeltaSec, $row.getChunkRange, $row.getExoUA, $row.getClientUA) -ForegroundColor $color
        }
    }
}

Write-Host ''
Write-Host '=========================== SUMMARY MATRIX ==============================' -ForegroundColor Cyan
$rows | Format-Table videoId, client, visitorData, playerHttp, playability, itag, hasUrl, hasSigCipher, hasN, hasPot, sabr, expireDeltaSec, getChunkRange, getExoUA, getClientUA -AutoSize | Out-String -Width 400 | Write-Host


# --------------------------------------------------------------------------------------
# Isolation probes: take the first URL that worked and vary ONE thing at a time, so each
# 403 hypothesis gets an explicit yes/no instead of hand-waving.
# --------------------------------------------------------------------------------------
if ($Deep) {
    $seed = $rows | Where-Object { $_.getExoUA -eq 200 -or $_.getExoUA -eq 206 } | Select-Object -First 1
    if (-not $seed) {
        Write-Host ''
        Write-Host '--- ISOLATION PROBES: skipped, no working URL to mutate ---' -ForegroundColor Yellow
    } else {
        # re-mint the URL (the one above was not kept)
        $seedClient = $CLIENTS | Where-Object { $_.key -eq $seed.client } | Select-Object -First 1
        $r = Invoke-Player -Client $seedClient -Vid $seed.videoId -VisitorData $visitorData -Sts $sts -PoToken $PoTokenVideo -CookieStr $Cookie
        $pr = $r.body | ConvertFrom-Json
        $f = Select-AudioFormat $pr.streamingData
        $u = $f.url

        Write-Host ''
        Write-Host "--- ISOLATION PROBES (seed: $($seed.client), itag $($f.itag)) ---" -ForegroundColor Cyan
        $sp = Get-UrlParam $u 'sparams'
        Write-Host "  signed params (sparams) : $sp"
        Write-Host "  signed ip=              : $(Get-UrlParam $u 'ip')"
        Write-Host "  expire=                 : $(Get-UrlParam $u 'expire')  (streamingData.expiresInSeconds=$($pr.streamingData.expiresInSeconds))"
        Write-Host ''

        function Probe-Case([string]$label, [string]$u2, [string]$ua, [int64]$rf, [int64]$rt, [hashtable]$hdr) {
            $x = Invoke-Raw -Url $u2 -UserAgent $ua -RangeFrom $rf -RangeTo $rt -Headers $hdr -NoBody -TimeoutMs 20000
            $c = 'Green'; if ($x.status -eq 403) { $c = 'Red' } elseif ($x.status -lt 200 -or $x.status -ge 300) { $c = 'Yellow' }
            Write-Host ("  {0,-56} -> {1}" -f $label, $x.status) -ForegroundColor $c
        }

        Probe-Case 'baseline: okhttp UA, Range bytes=0-524287'   $u $UA_EXO 0 ($CHUNK - 1) $null
        Probe-Case 'no Range header at all'                      $u $UA_EXO -1 -1 $null
        Probe-Case 'Range bytes=524288-1048575 (2nd chunk)'      $u $UA_EXO $CHUNK (2 * $CHUNK - 1) $null
        Probe-Case 'UA = client UA instead of okhttp'            $u $seedClient.ua 0 1 $null
        Probe-Case 'UA = empty'                                  $u '' 0 1 $null
        Probe-Case 'with Origin + Referer music.youtube.com'     $u $UA_EXO 0 1 @{ 'Origin' = $ORIGIN; 'Referer' = $REFERER }
        Probe-Case 'with X-Goog-Visitor-Id header'               $u $UA_EXO 0 1 @{ 'X-Goog-Visitor-Id' = $visitorData }
        Probe-Case 'with a BOGUS pot= appended'                  ($u + '&pot=' + ('A' * 120)) $UA_EXO 0 1 $null
        $past = $u -replace 'expire=\d+', ('expire=' + ([int64]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) - 60))
        Probe-Case 'expire= rewritten into the past'             $past $UA_EXO 0 1 $null
        if ($u -match 'ip=([^&]+)') {
            Probe-Case 'ip= rewritten (breaks the signature)'    ($u -replace 'ip=[^&]+', 'ip=1.2.3.4') $UA_EXO 0 1 $null
        }
        if ($u -match '[?&]n=') {
            Probe-Case 'n= rewritten (simulates unsolved nsig)'  ($u -replace '([?&]n=)[^&]+', '${1}AAAAAAAAAAAAAAAA') $UA_EXO 0 1 $null
        } else {
            Write-Host '  (url has no n= param -> nsig/deobfuscation is NOT on this code path)' -ForegroundColor DarkGray
        }

        # Source-address binding: same URL, forced over IPv4 vs IPv6 (needs curl.exe).
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            $conf = Join-Path $env:TEMP 'yt403-curl.conf'
            "url = `"$u`"" | Out-File -FilePath $conf -Encoding ascii
            foreach ($fam in @('--ipv4', '--ipv6')) {
                $out = & curl.exe -s -o NUL -K $conf $fam -L -r 0-1 --max-time 25 -w '%{http_code} (local=%{local_ip})'
                Write-Host ("  {0,-56} -> {1}" -f "same URL forced over $fam", $out)
            }
            Remove-Item $conf -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host ''
Write-Host '--- VERDICT ---' -ForegroundColor Cyan
$ok  = @($rows | Where-Object { $_.getExoUA -eq 200 -or $_.getExoUA -eq 206 })
$f403 = @($rows | Where-Object { $_.getExoUA -eq 403 -or $_.getChunkRange -eq 403 })
$nourl = @($rows | Where-Object { $_.playerHttp -eq 200 -and -not $_.hasUrl })
Write-Host ("200/206 on media GET : {0} -> {1}" -f $ok.Count,  (($ok  | ForEach-Object { "$($_.client)/$($_.visitorData)" }) -join ', '))
Write-Host ("403 on media GET     : {0} -> {1}" -f $f403.Count, (($f403 | ForEach-Object { "$($_.client)/$($_.visitorData)" }) -join ', '))
Write-Host ("no direct url        : {0} -> {1}" -f $nourl.Count, (($nourl | ForEach-Object { "$($_.client)/$($_.visitorData)" }) -join ', '))

if ($JsonOut) {
    $rows | ConvertTo-Json -Depth 5 | Out-File -FilePath $JsonOut -Encoding utf8
    Write-Host "`nJSON written to $JsonOut"
}
