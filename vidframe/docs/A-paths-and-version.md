# A. Выбор пути и версии

## Что проверено в исходниках (дерево firefox-main, 161.0a1)

Проверки сделаны по коду в этом репозитории, а не по документации для
десктопа. От них зависят все дальнейшие решения.

1. **На Android enterprise policies не собираются вообще.**
   `toolkit/components/enterprisepolicies/moz.build` подключает
   `EnterprisePolicies*.sys.mjs`, `components.conf` и валидатор схемы только
   при `MOZ_WIDGET_TOOLKIT != "android"`. Движка, который читал бы
   `policies.json`, в GeckoView нет. Поэтому `URLAllowlist`, `URLBlocklist`,
   `WebsiteFilter`, `DNSOverHTTPS`, `ExtensionSettings`/`Extensions` и
   `DisableTelemetry` на Android **молча игнорируются целиком**, а не
   «работают частично». Файл можно положить куда угодно, и ничего не
   изменится.
2. **В Fenix нет `distribution/` с префами и дополнениями.** Пакет
   `org.mozilla.fenix.distributions` (`DistributionIdManager`) определяет
   только ID партнёрской дистрибуции (Vivo, Digital Turbine, Aura) для
   атрибуции. Механизм Fennec `distribution/preferences.json` +
   `distribution/extensions/*.xpi` удалён вместе с Fennec.
3. **GeckoView читает `/data/local/tmp/<pkg>-geckoview-config.yaml`
   (префы и аргументы) только в debuggable-сборках**
   (`GeckoRuntime.java`). Для релиза это не подходит.
4. **DoH управляется из Kotlin.** Fenix хранит режим и URL в
   SharedPreferences (`Settings.trrMode`, `dohProviderUrl`) и при каждом
   старте передаёт их в `GeckoRuntimeSettings`
   (`trustedRecursiveResolverMode/Uri`). Значение, выставленное только
   gecko-префом, Fenix перезапишет.
5. **Навигацию можно перехватить в одном месте.**
   `AppRequestInterceptor.onLoadRequest` вызывается для top-level и
   subframe-навигаций всех сессий, включая PWA и Custom Tabs. Подресурсы
   (видеосегменты, XHR, картинки) через него не проходят.
6. **Дополнения ставятся из файла.**
   `AddonManager.installAddon(url, InstallationMethod.FROM_FILE)` принимает
   подписанный AMO XPI; при установке запрос разрешений приходит в
   `BrowserStore` как `WebExtensionPromptRequest.AfterInstallation.Permissions.Required`
   с колбэком `onConfirm`. Встроенные (`installBuiltInWebExtension`)
   расширения в менеджере дополнений скрыты и с AMO не обновляются.
7. **PWA-оболочка решает почти весь UX.** `ACTION_VIEW_PWA` и
   `WebAppIntentProcessor` открывают сайт в `ExternalAppBrowserActivity`:
   одна сессия, без адресной строки, без вкладок и без домашнего экрана.
   Аппаратная «назад» идёт по истории сессии.
8. **Минимальный API — 26 (Android 8.0)**
   (`build/moz.configure/android-sdk.configure`, `min_sdk_version="26"`).
   Сплиты ABI — `armeabi-v7a`, `arm64-v8a`, `x86_64`.

## Сравнение путей

| Критерий | 1. Своя сборка из firefox-source (Fenix + GeckoView, патчи) | 2. Обёртка над upstream Fenix APK (`distribution/` + профиль) |
|---|---|---|
| Сложность сборки под Android | Высокая на первом запуске: `mach bootstrap` ставит SDK/NDK/clang/rust, полная сборка GeckoView идёт 40–90 минут на 8–16 ядрах. Дальше она инкрементальная. | Формально низкая (apktool + переподпись), но см. следующие строки. |
| Возможность вообще реализовать требования | Реализуются все: allowlist, uBO, DoH, UI. | **Не реализуются.** Fenix не читает `distribution/` и `policies.json`, а профиль GeckoView лежит в приватных данных приложения. Без изменения кода нельзя ни ограничить навигацию, ни поставить uBO, ни включить DoH. Править smali в декомпилированном APK — это и есть путь 1, только без исходников. |
| Обновления | Rebase ~10 небольших коммитов на ветку `release` каждые 4 недели. Конфликты возможны в `Settings.kt`, `AndroidManifest.xml`, `AppRequestInterceptor.kt`. | Переподписанный APK не получает обновлений от Mozilla. Каждый релиз нужно заново декомпилировать и пропатчить smali, а это хрупко. |
| Стабильность uBO | Полный uBO (MV2) с AMO и автообновлением; поведение как в Firefox for Android. | То же расширение, но ставить его пришлось бы руками. |
| Размер APK | ~85–100 МБ на ABI (как у Fenix) плюс ~4 МБ uBO. Сплит по ABI обязателен. | Примерно такой же. |
| policies.json | Не поддерживается ни там, ни там; эквивалент — compiled-in префы + Kotlin. | Не поддерживается. |
| Юридические риски | Низкие: неофициальный брендинг, свой `applicationId`, MPL-2.0 соблюдается публикацией исходников. | **Высокие:** распространяется бинарник с товарными знаками Firefox (имя, иконка, `org.mozilla.firefox`), изменённый без разрешения. Это прямо нарушает Mozilla Trademark Policy. Для F-Droid такой путь невозможен. |

**Вывод: путь 1.** Путь 2 на Android не работает технически: всё, что на
десктопе делает `distribution/` + `policies.json`, в Fenix требует изменения
кода. Минимум сопровождения у пути 1 обеспечивается так:

* весь новый код живёт в одном пакете `org.mozilla.fenix.vidframe`;
* в upstream-файлах только точечные хуки (один вызов в
  `FenixApplication`, одна проверка в `AppRequestInterceptor`, значения по
  умолчанию в `Settings.kt`, манифест, ресурсы);
* UI не переписывается, используется готовая PWA-оболочка Fenix.

## Версия Firefox for Android

* **ESR для Android не существует.** Mozilla выпускает Fenix только в
  каналах Nightly / Beta / Release; ESR-ветка GeckoView как продукт не
  поставляется. Требование «release или ESR» на Android сводится к
  **Release**.
* **Фиксация:** ветка `release` монорепозитория и её релизный тег.
  В этом дереве Nightly = 161, значит на момент подготовки Release — **159**.
  Тег надо сверять при каждом rebase:

  ```bash
  git fetch origin release --tags
  ```

  ```bash
  git tag -l 'FIREFOX*159*RELEASE*'
  ```

  Записывайте выбранный тег в `vidframe/config/UPSTREAM`.
* **MV2 и uBO.** Firefox for Android поддерживает MV2 полностью;
  с Firefox 120 на Android открыт весь каталог AMO. В Gecko нет переключателя
  «выключить MV2» (в дереве есть только
  `extensions.manifestV2.actionsPopupURLRestricted`, а он касается URL
  попапов). Mozilla публично заявляла, что продолжит поддерживать MV2
  в Firefox, включая Android, и сроков отключения не объявляла. Проверка при
  каждом обновлении: `fetch-ubo.sh` требует `manifest_version == 2`,
  а чек-лист G включает проверку на устройстве. Если Mozilla объявит
  отказ от MV2, запасной вариант — uBO Lite (MV3, DNR), но он заметно слабее
  на m.youtube.com: без процедурных косметических фильтров и scriptlet'ов
  в нужном объёме.
* **«Стабильность policies.json на Android»** не применима (см. п. 1).
  Стабильность нашего эквивалента определяется ключами `Settings.kt`
  и `vidframe-prefs.js`; подводные камни перечислены в F.
