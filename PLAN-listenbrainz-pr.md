# ListenBrainz Scrobbler — Plan de implementación (Meld fork)

> Estado: preparado 28ago2026. Rama `feature/listenbrainz-scrobbling` creada en D:/tools/meld-scrobble.
> El issue está redactado en ISSUE-listenbrainz.md (pegar en GitHub de FrancescoGrazioso/Meld).

## Qué falta para publicar
1. Simone abre el issue: github.com/FrancescoGrazioso/Meld/issues/new (contenido en ISSUE-listenbrainz.md)
2. Simone hace fork con su cuenta (botón Fork en el repo, o `gh repo fork` si instala gh)
3. Simone añade el fork como remote y push:
   git remote add fork https://github.com/<su-usuario>/Meld.git
   git push -u fork feature/listenbrainz-scrobbling

## Implementación (archivos a crear/modificar)
1. `lastfm/src/main/kotlin/com/metrolist/listfm/` → crear módulo hermano `listenbrainz/`
   - `ListenBrainz.kt`: validateToken(token), submitListen(artist, track, album, ts, playing_now) → POST https://api.listenbrainz.org/1/submit-listens
   - Auth: header "Authorization: Token <token>" (Bearer-style, SIN MD5 — más simple que Last.fm)
   - Respuestas: validate → {"valid": true/false}; submit → 200 OK / 401 invalid
2. `ScrobbleManager.kt`: interface LastFmScrobbleClient ya existe con client inyectable
   - Añadir `ListenBrainzScrobbleClient : LastFmScrobbleClient` (mismos 2 métodos, llama a LB API)
   - La interface se puede renombrar a ScrobbleClient (o dejar el nombre, decisión del dev)
3. `PreferenceKeys.kt`: +LISTENBRAINZ_ENABLED, +LISTENBRAINZ_TOKEN
4. `ui/screens/settings/integrations/`: ListenBrainzSettings.kt (patrón LastFMSettings.kt)
5. `MusicService.kt`: inyección de cliente según preferencia (LB, LF, ambos o ninguno)

## API verificada en vivo (28ago2026)
- validate-token con token inválido → {"code":200,"message":"Token invalid.","valid":false} ✓ API viva
- submit-listens sin token → 401 {"code":401,"error":"Invalid authorization token."} ✓

## Test del PR
- Token real de listenbrainz.org (registro gratuito) → validate OK → scrobble de test → verificar en el perfil LB
- Captura de la nueva sección de settings para el PR

## Nota para Narcio
La idea es suya — este PR la materializa en el proyecto vivo. Crédito en el issue.