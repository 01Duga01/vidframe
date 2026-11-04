# D. Минималистичный UI: что чем делается на Android

## userChrome.css — на Android не работает

У Fenix нет XUL/HTML-chrome. Тулбар, вкладки, меню и настройки — это
нативные Android View и Jetpack Compose в `org.mozilla.fenix.*`; GeckoView
рисует только веб-контент. `userChrome.css` применять не к чему, ни в
одной версии Fenix. Файл `vidframe/css/userChrome.css` оставлен пустым,
с объяснением.

**Альтернатива: PWA-оболочка + патчи.**

| Цель | Как сделано |
|---|---|
| Нет адресной строки, вкладок, домашнего экрана | Launcher-алиас `${applicationId}.App` → `vidframe.VidframeLauncherActivity`. Он записывает в `ManifestStorage` манифест `display: standalone`, `scope: https://m.youtube.com/` и шлёт `ACTION_VIEW_PWA`. Открывается `ExternalAppBrowserActivity` в режиме web-app: одна сессия; при повторном запуске используется та же (`WebAppIntentProcessor.findExistingSession`). |
| Нет onboarding, Terms of Use | `FeatureFlags.onboardingFeatureEnabled=false`, `isTermsOfUsePromptEnabled=false`. |
| Нет Pocket, sponsored, рекомендаций | значения по умолчанию в `Settings.kt` (см. C). |
| Нет синхронизации, телеметрии | Синхронизация не включается без входа в аккаунт Mozilla; телеметрия выключена по умолчанию. |
| Не «браузер по умолчанию» | Из `IntentReceiverActivity` удалены фильтры `APP_BROWSER`, `http/https`, `SEND`, `WEB_SEARCH`, `ASSIST`, `NFC`. Ссылки на `youtube.com`, `www.`, `m.`, `youtu.be` принимает `VidframeLauncherActivity`. |
| Поиск | Только внутри сайта (поле поиска m.youtube.com). Поиск Google закрыт allowlist'ом. |
| Логин | `accounts.google.com` в allowlist. Если навигация выходит за `scope` PWA, Fenix показывает узкую панель custom-tab с кнопкой «закрыть» (`WebAppHideToolbarFeature`), после логина пользователь возвращается в scope. |
| История (локально) | Ярлык «История» → `vidframe://urls_history`. Хранится в `places` на устройстве, без синхронизации. |
| Настройки uBO | Ярлык «Блокировщик» → `vidframe://settings_addon_manager` → uBO → «Настройки» (открывается `moz-extension://…/dashboard.html`). |
| Настройки приложения | Ярлык «Настройки» → `vidframe://settings`. |
| Кнопка «назад» | `ExternalAppBrowserActivity` → `BaseBrowserFragment.onBackPressed` → `GeckoSession.goBack()`, пока есть история. На корне — выход из activity (сессия остаётся в памяти, повторный запуск её подхватывает). SPA-навигация m.youtube.com использует `history.pushState`, поэтому «назад» работает внутри сайта (закрывает плеер, возвращает к поиску). |
| Название, иконка | `release/res/values/static_strings.xml` (`Vidframe`), `release/res/mipmap-anydpi-v26/ic_launcher*.xml` (нейтральная рамка кадра, не трейд-дресс YouTube). |
| Дисклеймер | `AboutFragment` использует `vidframe_about_disclaimer` вместо «produced by Mozilla». |

Ярлыки видны при долгом нажатии на иконку в лаунчере (Android 7.1+).
Их можно вынести на рабочий стол отдельными иконками.

## userContent.css — работает ограниченно, заменён встроенным расширением

Gecko на Android читает `chrome/userContent.css` из профиля, если
`toolkit.legacyUserProfileCustomizations.stylesheets=true` (по умолчанию
`false`, `modules/libpref/init/all.js`). Но профиль лежит в
`/data/data/<applicationId>/files/mozilla/…`, и без root или debuggable-сборки
файл туда не положить. Для поставки это не годится.

**Рабочий способ:** встроенное MV2-расширение
`fenix/app/src/main/assets/extensions/vidframe-ui/`, которое устанавливается
вызовом `engine.installBuiltInWebExtension("ui@vidframe",
"resource://android/assets/extensions/vidframe-ui/")` так же, как Fenix
ставит свой WebCompat-аддон. Встроенным расширениям подпись не нужна,
пользователь их не видит, и обновляются они вместе с APK. CSS подключается
через `content_scripts` с `run_at: document_start`.

Файлы:

* `vidframe-ui/m-youtube.css` — для `https://m.youtube.com/*`;
* `vidframe-ui/account-pages.css` — для страниц логина и согласия;
* `vidframe/css/userContent.css` — те же правила в форме userContent
  (`@-moz-document`) для отладки на рутованном устройстве.

### Применимые селекторы m.youtube.com

| Селектор | Что скрывает | Насколько стабилен |
|---|---|---|
| `ytm-mealbar-promo-renderer`, `.mealbar-promo-renderer` | нижняя плашка «Открыть в приложении» / промо | давно существует, но может быть переименован |
| `ytm-statement-banner-renderer` | баннеры-апселлы в ленте | средне |
| `ytm-open-app-button`, `.open-app-button` | кнопка «Открыть приложение» | средне |
| `a[href^="intent://"]`, `a[href^="vnd.youtube:"]` | любые ссылки, уводящие в нативное приложение | стабильно (по схеме, а не по имени элемента) |
| `a[href*="play.google.com/store"]` | «Скачать приложение» на страницах логина | стабильно |

Скрывать рекламу через CSS **не нужно и не следует**: этим занимается uBO
со своими списками, которые обновляются независимо от APK. Кастомные
элементы `ytm-*` YouTube меняет без предупреждения, поэтому в чек-листе G
есть пункт «проверить селекторы».

### Почему не hide-toolbar через патч BrowserFragment

Можно спрятать тулбар в `BrowserFragment` и оставить HomeActivity основным
экраном, но это затрагивает десятки файлов (тулбар-редизайн Fenix
меняется почти в каждом релизе). PWA-режим — публичный стабильный путь,
которым Fenix сам пользуется для «Добавить на главный экран». Это
и есть минимум сопровождения.
