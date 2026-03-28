# Имплементация интеграции с SoundCloud по аналогии со Spotify

Основываясь на архитектуре интеграции со Spotify, интеграция с SoundCloud будет включать следующие компоненты и шаги:

## 1. Модуль API SoundCloud (Аналог `spotify` модуля)

Создать новый модуль/пакет `soundcloud` с аналогичной структурой моделей и API-клиентом.

### Новые файлы:
*   `soundcloud/build.gradle.kts` - скрипт сборки модуля (если используется многомодульная структура, иначе файлы в `app/src/main/kotlin/com/metrolist/soundcloud/`).
*   `soundcloud/src/main/kotlin/com/metrolist/soundcloud/SoundCloud.kt` - основной объект-одиночка (Singleton) для взаимодействия с API SoundCloud (REST API, как правило v2), использующий `io.ktor.client.HttpClient` так же, как и `Spotify.kt`.
*   `soundcloud/src/main/kotlin/com/metrolist/soundcloud/SoundCloudAuth.kt` - логика авторизации (получение client_id, OAuth токенов, если нужно, или использование cookie-based аутентификации, если будет выбран парсинг внутреннего API).
*   `soundcloud/src/main/kotlin/com/metrolist/soundcloud/SoundCloudMapper.kt` - маппинг ответов SoundCloud.

### Модели (в `soundcloud/src/main/kotlin/com/metrolist/soundcloud/models/`):
*   `SoundCloudTrack.kt`
*   `SoundCloudPlaylist.kt`
*   `SoundCloudUser.kt`
*   `SoundCloudPaging.kt`
*   И другие необходимые DTO для парсинга ответов.

## 2. Интеграция с плеером и базой данных в `app`

Нужно будет создать аналогичные классы для связи данных SoundCloud с локальной базой данных и плеером (YouTube Music).

### Новые файлы:
*   `app/src/main/kotlin/com/metrolist/music/playback/SoundCloudYouTubeMapper.kt` - аналог `SpotifyYouTubeMapper.kt` для поиска соответствий треков SoundCloud в YouTube Music.
*   `app/src/main/kotlin/com/metrolist/music/playback/queues/SoundCloudQueue.kt`
*   `app/src/main/kotlin/com/metrolist/music/playback/queues/SoundCloudPlaylistQueue.kt`
*   `app/src/main/kotlin/com/metrolist/music/playback/queues/SoundCloudLikedTracksQueue.kt` - (если поддерживается).
*   `app/src/main/kotlin/com/metrolist/music/db/entities/SoundCloudMatchEntity.kt` - сущность Room для кеширования совпадений (SoundCloud ID -> YouTube ID).

### Изменяемые файлы:
*   `app/src/main/kotlin/com/metrolist/music/db/MusicDatabase.kt` - добавить таблицу `SoundCloudMatchEntity` и соответствующие DAO методы.
*   Добавить DI/инстансы маппера в соответствующие места.

## 3. UI и ViewModels

Создать экраны и ViewModels для отображения данных из SoundCloud.

### Новые файлы:
*   `app/src/main/kotlin/com/metrolist/music/viewmodels/SoundCloudViewModel.kt`
*   `app/src/main/kotlin/com/metrolist/music/viewmodels/SoundCloudPlaylistViewModel.kt`
*   `app/src/main/kotlin/com/metrolist/music/ui/screens/settings/integrations/SoundCloudSettings.kt`
*   `app/src/main/kotlin/com/metrolist/music/ui/screens/SoundCloudLoginScreen.kt`
*   `app/src/main/kotlin/com/metrolist/music/ui/screens/playlist/SoundCloudPlaylistScreen.kt`

### Изменяемые файлы:
*   `app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt` (или где находится список интеграций) - добавить пункт "SoundCloud" рядом со Spotify.
*   Файлы навигации (NavGraph) для добавления новых маршрутов экранов SoundCloud.

## 4. Pre-commit шаги
   - Выполнить все необходимые pre-commit шаги для проверки кода, форматирования и линтинга.
