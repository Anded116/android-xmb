# Play Console: Data safety и декларации

Ответы для сборки **store** (`assembleStoreRelease` / `bundleStoreRelease`). В ней нет `INTERNET`,
службы спец. возможностей и `WRITE_SETTINGS` — это сверено по манифесту готового APK (`aapt2 dump badging`).

## Data safety

| Вопрос | Ответ | Почему |
|---|---|---|
| Collects or shares any of the required user data types? | **No** | По определению Play данные «собраны», только если переданы с устройства. Приложение без `INTERNET` ничего не передаёт. |
| Is all user data encrypted in transit? | — | Не задаётся при ответе «No» выше. |
| Do you provide a way to request deletion? | — | Не задаётся; удаление приложения стирает его данные. |

## App content

- **Privacy policy** — обязательна (есть доступ к уведомлениям и календарю). Текст — `PRIVACY.md`, нужен
  публичный URL (см. `RELEASE_CHECKLIST.md`).
- **Ads** — No.
- **App access** — All functionality available without special access (нет логина).
- **Content rating** — анкета IARC: утилита, без насилия, общения, покупок внутри — ожидаемо «3+ / Everyone».
- **Target audience** — 13+ (не для детей: лаунчер меняет домашний экран).
- **News app / COVID / Government** — No.

## Разрешения и API, о которых спросит консоль

| Что | Нужна декларация | Комментарий |
|---|---|---|
| `READ_CALENDAR` | Нет | Не входит в ограниченные группы Play (SMS, звонки, фон-геолокация, все файлы). |
| `READ_MEDIA_AUDIO` | Нет | Политика Play «Photo and Video Permissions» касается только картинок и видео; аудио — обычное разрешение. |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | — | Только в личной сборке full (свои «Фото» и «Видео»). Политика «Photo and Video Permissions» пускает их лишь приложениям, для которых галерея — основная функция; в store их нет, в разделах только приложения. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | **Да** | App content → Foreground service permissions: тип `mediaPlayback`, «воспроизведение музыки, выбранной пользователем, в фоне», ссылка на короткое видео (запуск трека → экран гаснет → музыка играет, управление из уведомления). |
| Доступ к уведомлениям (`NotificationListenerService`) | Нет | Пользователь выдаёт сам в настройках; описать в политике — сделано. |
| `REQUEST_DELETE_PACKAGES` | Нет | Ограничен только `REQUEST_INSTALL_PACKAGES`. |
| `QUERY_ALL_PACKAGES` | — | Не используется: список приложений — через `<queries>` и `LauncherApps`. |
| `AccessibilityService` | — | Только в личной сборке full; в store её нет — декларация и экран раскрытия не нужны. |
