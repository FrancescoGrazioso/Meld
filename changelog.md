---v13.4.0
# MAINTENANCE MODE
Metrolist is currently in maintenance mode. This means we will only be fixing bugs and making minor improvements. Please do not submit PRs for new features or major changes, as they will not be accepted.

No, this is not an April Fools joke, even though this update is being released on April 1st.

We are working on something big for the future of Metrolist - this is not the end of the project.

# Major changes
- Multiple playback fixes and reliability improvements (@alltechdev)
- Revamped the entire Lyrics engine, improving lyric accuracy and usability (@adrielGGmotion)
- Fixed multiple crash issues (@kairosci, @nyxiereal)
- Multiple improvements to Android Auto support (@andker87)
- Fixed multiple grammar and text inconsistency issues in the project (@TheRebo)

## Notable new features
- Added support for treating cached songs as offline songs (@kairosci)
- Added music alarm scheduling (@0xarchit)
- Added miniplayer styles (@johannesbrauer)
- Added a button to copy all song lyrics to the clipboard (@kairosci)
- Added a time transfer feature to move listening time between songs in the stats page (@finley-webber)
- Added customization support for the AI prompt used for translations (@nyxiereal)
- Added a notification-based music recognition for the QS tile shortcut (@isotjs)

## Other improvements
- Fixed incorrect artist order for multi-artist songs (@AntonioDionisio05)
- Fixed playtime in the stats page not being fully visible (@David-2765)
- Improved radio to start seamlessly when initiated from the currently playing track (@luigiwwmf)
- Improved the UI for tablets (@adrielGGmotion)
- Improved the About Screen layout (@adrielGGmotion)
- Fixed ghost adds on playlists (@johannesbrauer)
- Improved search focus and navigation behavior (@saivijaychandan)
- Added album navigation on song title click regardless of play source (@gergesh)
- Prevented UI state reset when switching apps (@mostafaalagamy)
- Restored the Daily Discover title in the Home screen (@mostafaalagamy)
- Fixed listen together audio choppiness (@nyxiereal)
- Redesigned romanization and account settings (@omardotdev)
- Improved the design of the sleep timer dialog (@johannesbrauer)
- Redesigned some components to use Material 3 Expressive (@johannesbrauer)
- Fixed links in the README (@Lolen10 @nyxiereal)

## New Contributors
* @AntonioDionisio05 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3255
* @David-2765 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3271
* @luigiwwmf made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3293
* @gergesh made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3300
* @Lolen10 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3328

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.3.0...v13.3.1
---v13.3.0
# Major changes
- Implemented song upload and delete functionality (@alltechdev)
- Multiple playback fixes and reliability improvements (@alltechdev, @mostafaalagamy)
- Fixed proguard rules causing issues with Reproducible Builds (@nyxiereal)
- Fixed proguard rules removing Listen Together protobuf classes (@mostafaalagamy)
- Added a playlist export option to the playlist context menu (@nyxiereal)

## Notable new features
- Added a Play all action for the stats page (@isotjs)
- Added a quick settings tile for recognizing music (@nyxiereal)
- Added automatic sleep timer options and integrated fade-out volume handling (@isotjs)
- Added a profile search filter (@alltechdev)
- Added channel subscriptions for podcasts and artists (@alltechdev)

## Other improvements
- Fixed cached images not clearing properly and cached covers not showing when offline (@nyxiereal)
- Removed useless and stale strings from the codebase (@nyxiereal)
- Refined the song details view (@omardotdev)
- Added support for Mistral AI models (@nyxiereal)
- Redesigned the lastfm integration settings (@omardotdev)
- Fixed importing csv files crashing the app (@nyxiereal)
- Prevent guest playback while in listen together (@nyxiereal)
- Fixed podcasts not working for logged-out users (@alltechdev)
- Updated dependencies (@nyxiereal)

## New Contributors
* @isotjs made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3090

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.2.1...v13.3.0
---v13.2.1
>[!WARNING]
>Listen Together doesn't work in v13.2.1! Use v13.2.0 if you need it.

## Hot Fixes
- Fix interface lag issue
- Fix navigate local playlists pinned in speed dial
- Removed "cache songs only after playback has started" option

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.2.0...v13.2.1
---v13.2.0
# Major changes
- Fixed playback breaking due to YouTube's February 2026 n-transform changes (@alltechdev)
- Added full podcast library support (@mostafaalagamy & @alltechdev)
- Redesigned loading, Changelog, and About screens (@adrielGGmotion)
- Improved app startup time via parallelized home screen loading (@mostafaalagamy)

## Notable new features
- Added an option to cache songs only after playback has started (@kairosci)
- Added a music recognizer home screen widget (@mostafaalagamy)
- Rewrote music recognizer in pure Kotlin, removing NDK dependency and reducing APK size (@mostafaalagamy)
- Overhauled lyrics: added LyricsPlus provider, AI lyric fixes, untranslation support, and provider priority settings (@nyxiereal)
- Changed listen together to use protobuf, lowering latency and improving reliability (@nyxiereal)
- Added auto-approve setting for listen together song requests (@nyxiereal)
- Added an option to persist the sleep timer default value (@johannesbrauer)
- Added a dialog on logout to keep or clear library data (@alltechdev)

## Other improvements
- Fixed backup restore causing playback errors due to stale auth credentials (@alltechdev)
- The CSV import dialog is now scrollable (@kairosci)
- Fixed Android 15 foreground service crashes (@kairosci)
- Fixed a crash on the About screen on some devices (@mostafaalagamy)
- Fixed home screen playlist navigation routing to wrong screen (@mostafaalagamy)
- Fixed crash when creating local playlists (@mostafaalagamy)

## New Contributors
* @johannesbrauer made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/2991

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.1.1...v13.2.0