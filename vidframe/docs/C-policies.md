# C. policies.json и его Android-эквивалент

## Главное

На Android `policies.json` **не обрабатывается ничем**: модули
`EnterprisePolicies*.sys.mjs` исключены из сборки при
`MOZ_WIDGET_TOOLKIT == "android"` (см. `toolkit/components/enterprisepolicies/moz.build`).
Поэтому:

* `vidframe/config/policies.reference.json` — **справочный** файл
  в десктопной схеме. Он описывает целевое поведение одним документом,
  и по нему проверяется, что Android-реализация покрывает каждый пункт.
  В APK он не попадает и ни на что не влияет.
* Рабочий эквивалент состоит из трёх слоёв:
  1. **compiled-in Gecko-префы:** `mobile/android/app/vidframe-prefs.js`,
     подключается через `#include` из `geckoview-prefs.js` и попадает
     в `omni.ja`. Действует с первого сетевого запроса. Пользователь не
     может изменить его без `about:config`, а `about:config` в release-сборке
     Fenix недоступен;
  2. **значения по умолчанию Fenix:** `Settings.kt` (SharedPreferences);
  3. **код:** пакет `org.mozilla.fenix.vidframe`.

## Таблица соответствия

| Политика (десктоп) | На Android | Чем заменено в Vidframe |
|---|---|---|
| `Homepage` / `StartPage` | ✗ игнорируется | `VidframeLauncherActivity`: launcher-алиас `${applicationId}.App` указывает на него, он открывает `START_URL` как standalone web-app. |
| `WebsiteFilter` | ✗ игнорируется | `NavigationAllowlist` + `VidframeRequestInterceptor`, вызывается первым в `AppRequestInterceptor.onLoadRequest`. |
| `URLAllowlist` / `URLBlocklist` (это политики Chrome) | ✗ в Firefox таких нет нигде | см. `WebsiteFilter`. |
| `DNSOverHTTPS` (`Enabled`, `ProviderURL`, `Locked`) | ✗ игнорируется | `network.trr.mode=3` + `network.trr.uri` в `vidframe-prefs.js`; `Settings.trrMode` по умолчанию `MAX`, `dohProviderUrl` = AdGuard; пункт настроек DoH скрыт (`showDohEntryPoint=false`); `DohFailover` переключает endpoint. |
| `ExtensionSettings` → `force_installed` | ✗ игнорируется | `UboProvisioner`: установка подписанного XPI из assets через `AddonManager.installAddon(FROM_FILE)` с автоподтверждением разрешений и доступом в приватных вкладках. |
| `ExtensionUpdate` | ✗ | `extensions.update.enabled=true` + штатный `DefaultAddonUpdater` Fenix (WorkManager, AMO). |
| `DisableTelemetry` | ✗ | `Settings.isTelemetryEnabled=false` по умолчанию. От него зависят `isDailyUsagePingEnabled` и `isExperimentationEnabled` (Nimbus). Gecko: `datareporting.*`, `toolkit.telemetry.enabled=false`. |
| `DisableFirefoxStudies` | ✗ | Nimbus выключен через `isExperimentationEnabled` (см. выше). |
| `DisablePocket`, `FirefoxHome.*` | ✗ | `showPocketRecommendationsFeature`, `showPocketSponsoredStories`, `showContileFeature` = `false`. Домашний экран Fenix при обычном запуске не показывается вообще. |
| `FirefoxSuggest` | ✗ | `showSponsoredSuggestions=false`. |
| `UserMessaging.SkipOnboarding` | ✗ | `FeatureFlags.onboardingFeatureEnabled=false`, `isTermsOfUsePromptEnabled=false`. |
| `DisableFirefoxAccounts` | ✗ | Не отключается флагом. Синхронизация не включается сама; пункт меню остаётся в настройках. Чтобы убрать его полностью, нужен патч `SettingsFragment` (см. F). |
| `HttpsOnlyMode` | ✗ | `Settings.shouldUseHttpsOnly=true`, `dom.security.https_only_mode=true`. |
| `CaptivePortal` | ✗ | `network.captive-portal-service.enabled=false`. |
| `NetworkPrediction` | ✗ | `network.dns.disablePrefetch*`, `network.prefetch-next`, `network.http.speculative-parallel-limit=0`. |
| `Preferences` | ✗ | Напрямую в `vidframe-prefs.js`. |
| `NoDefaultBookmarks`, `DisplayBookmarksToolbar` | ✗ | Не нужны: в PWA-оболочке нет ни закладок, ни тулбара. |

## Android-эквивалент «policies.json»

Ниже весь эффективный набор в одном месте. Источник истины — файлы
в репозитории, здесь их сводка.

### `mobile/android/app/vidframe-prefs.js` (Gecko, compiled-in)

```js
pref("network.trr.mode", 3);
pref("network.trr.uri", "https://dns.adguard-dns.com/dns-query");
pref("network.trr.default_provider_uri", "https://dns.adguard-dns.com/dns-query");
pref("network.trr.custom_uri", "https://dns.adguard-dns.com/dns-query");
pref("network.trr.excluded-domains", "");
pref("network.trr.disable-ECS", true);
pref("network.captive-portal-service.enabled", false);
pref("network.connectivity-service.enabled", false);
pref("network.dns.disablePrefetch", true);
pref("network.dns.disablePrefetchFromHTTPS", true);
pref("network.prefetch-next", false);
pref("network.http.speculative-parallel-limit", 0);
pref("dom.security.https_only_mode", true);
pref("media.peerconnection.enabled", false);
pref("datareporting.healthreport.uploadEnabled", false);
pref("datareporting.policy.dataSubmissionEnabled", false);
pref("toolkit.telemetry.enabled", false);
pref("extensions.update.enabled", true);
pref("extensions.update.autoUpdateDefault", true);
```

Все имена проверены по `modules/libpref/init/StaticPrefList.yaml`,
`all.js` и `AddonManager.sys.mjs` этого дерева. `network.predictor.enabled`
в дереве больше нет, поэтому он не используется.

### `Settings.kt` (Fenix, значения по умолчанию)

| Ключ | Upstream | Vidframe |
|---|---|---|
| `pref_key_telemetry` | `true` | `false` |
| `pref_key_doh_settings_enabled` (показ UI) | Nimbus `doh.showUi` | `false` |
| `pref_key_doh_settings_mode` | `0` (Default) | `3` (Max = TRR-only) |
| `pref_key_doh_provider_uri` | `""` | `https://dns.adguard-dns.com/dns-query` |
| `pref_key_doh_default_provider_uri` | Cloudflare | AdGuard |
| `pref_key_https_only` | `false` | `true` |
| `pref_key_pocket_homescreen_recommendations` | по секциям | `false` |
| `pref_key_pocket_sponsored_stories` | по секциям | `false` |
| `pref_key_enable_contile` | `true` | `false` |
| `pref_key_show_sponsored_suggestions` | FxSuggest | `false` |
| `pref_key_terms_prompt_enabled` | Nimbus | `false` |

Это значения **по умолчанию**: если ключ уже записан (например, при
обновлении поверх старой версии), действует записанное значение. DoH
дополнительно принудительно выставляет `DohFailover.start()` при каждом
запуске, так что он фактически «Locked».

### Allowlist (`NavigationAllowlist.rules`)

| Хост | Поддомены | Пути | Зачем |
|---|---|---|---|
| `youtube.com` | да | все | сайт, `m.`, `www.`, `accounts.`, `consent.` |
| `youtu.be` | да | все | короткие ссылки |
| `youtube-nocookie.com` | да | все | встраивания |
| `ytimg.com`, `ggpht.com` | да | все | превью, аватары |
| `googlevideo.com` | да | все | видеопотоки |
| `gstatic.com`, `googleapis.com`, `googleusercontent.com` | да | все | скрипты, шрифты, API, аватары |
| `accounts.google.com`, `consent.google.com`, `myaccount.google.com` | нет | все | логин, согласие на cookies, переключение аккаунта |
| `www.google.com` | нет | `/recaptcha/`, `/sorry/`, `/accounts/`, `/signin`, `/intl/`, `/policies/` | капча, логин, политики. Поиск Google (`/search`) **закрыт** намеренно. |
| `policies.google.com` | нет | все | ссылки из диалога согласия |
| `support.google.com` | нет | `/youtube`, `/accounts` | справка |
| `dns.adguard-dns.com`, `unfiltered.adguard-dns.com`, `dns.quad9.net` | нет | все | DoH endpoints (навигации на них не нужны, но список служит единым реестром) |

Схемы `about:`, `data:`, `blob:`, `moz-extension:` (страницы uBO),
`resource:` и `javascript:` пропускаются. Любая другая схема (`intent:`,
`vnd.youtube:`, `market:`) блокируется.

### Режимы навигации

| | `strict` (по умолчанию) | `warn` |
|---|---|---|
| Сборка | `-Pvidframe.navMode=strict` | `-Pvidframe.navMode=warn` |
| Top-level переход вне списка | Страница-заглушка с кнопкой «Вернуться к видео». | Та же заглушка плюс кнопка «Открыть во внешнем браузере». |
| Как работает кнопка | — | Ссылка `vidframe-external://open?u=…` перехватывается `VidframeRequestInterceptor`. Только при `hasUserGesture` и только для http(s) открывается системный chooser **без** этого приложения (`EXTRA_EXCLUDE_COMPONENTS`). |
| Subframe вне списка | `Deny` (молча) | `Deny` (молча) |
| Подресурсы | Не фильтруются этим слоем, за них отвечают uBO и DoH-фильтр AdGuard. | так же |
| Внутри приложения внешний сайт | никогда не открывается | никогда не открывается |

Разница, следовательно, только в том, можно ли передать ссылку **наружу**.
Внутри клиента внешний контент не открывается ни в одном режиме.

### Дополнительный слой: ограничение подресурсов (опционально)

Если нужно запретить и запросы подресурсов к сторонним доменам, а не
только навигацию, это делается одним правилом uBO в «Мои фильтры». Так
не приходится писать отдельный webRequest-аддон:

```
*$3p,domain=youtube.com,to=~youtube.com|~ytimg.com|~ggpht.com|~googlevideo.com|~gstatic.com|~googleapis.com|~googleusercontent.com|~google.com
```

Правило можно поставить заранее через `adminSettings` uBO, но на Android
нет `storage.managed`, так что только через импорт бэкапа настроек. Это
рискованно: при изменениях инфраструктуры YouTube ломается воспроизведение.
Поэтому по умолчанию правило не включено.
