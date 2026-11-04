# E. Структура репозитория

Репозиторий — форк монорепозитория Firefox (`firefox-source`). Остальные
~6 ГБ дерева не изменены. Ниже только то, что принадлежит Vidframe.
`(new)` — новый файл, `(mod)` — точечное изменение upstream-файла.

```
.
├── .gitignore                                         (mod) XPI uBO, vidframe/out, vidframe/keystore
├── vidframe/                                          (new) оверлей: конфиги, скрипты, документация
│   ├── README.md                                      обзор, дисклеймер, быстрая сборка
│   ├── config/
│   │   ├── UPSTREAM                                   ветка/тег upstream для серии патчей
│   │   ├── ubo.lock                                   версия, URL и sha256 uBlock Origin (AMO)
│   │   └── policies.reference.json                    справочная политика в десктопной схеме (на Android не читается)
│   ├── css/
│   │   ├── userChrome.css                             пустой, с объяснением (на Android не применяется)
│   │   └── userContent.css                            справочная копия правил vidframe-ui
│   ├── mozconfigs/
│   │   ├── arm64                                      aarch64-linux-android, unofficial branding
│   │   └── armv7                                      armv7-linux-androideabi
│   ├── scripts/
│   │   ├── fetch-ubo.sh                               скачать и проверить подписанный XPI uBO
│   │   ├── build.sh                                   mach build + gradle fenix:assembleRelease → vidframe/out/
│   │   ├── sign.sh                                    zipalign + apksigner v2/v3
│   │   ├── check-device.sh                            API level, ABI, RAM подключённого устройства
│   │   └── verify-doh.sh                              tcpdump-проверка утечек DNS
│   ├── fdroid/
│   │   └── io.github.vidframe.app.yml                 черновик метаданных F-Droid
│   └── docs/                                          A–G, распространение и лицензии
│
└── mobile/android/
    ├── app/
    │   ├── geckoview-prefs.js                         (mod) #include vidframe-prefs.js
    │   └── vidframe-prefs.js                          (new) Android-эквивалент policies.json (Gecko-префы)
    └── fenix/app/
        ├── build.gradle                               (mod) applicationId, суффикс .app, deep-link vidframe,
        │                                                    sharedUserId, BuildConfig.VIDFRAME_NAV_MODE
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml                (mod) launcher → VidframeLauncherActivity,
            │   │                                            убраны browser-фильтры IntentReceiverActivity
            │   ├── assets/extensions/
            │   │   ├── vidframe-ui/                   (new) встроенное расширение: CSS для m.youtube.com
            │   │   │   ├── manifest.json
            │   │   │   ├── m-youtube.css
            │   │   │   └── account-pages.css
            │   │   └── ublock_origin.xpi              (build) кладёт fetch-ubo.sh, в git не хранится
            │   ├── java/org/mozilla/fenix/
            │   │   ├── AppRequestInterceptor.kt       (mod) вызов VidframeRequestInterceptor, без app links
            │   │   ├── FeatureFlags.kt                (mod) onboarding выключен
            │   │   ├── extension/WebExtensionPromptFeature.kt (mod) без диалогов для uBO (UboProvisioner.autoAccept)
            │   │   ├── FenixApplication.kt            (mod) VidframeIntegration.start()
            │   │   ├── settings/about/AboutFragment.kt (mod) дисклеймер
            │   │   ├── utils/Settings.kt              (mod) значения по умолчанию (телеметрия, DoH, Pocket, ToU, HTTPS-only)
            │   │   └── vidframe/                      (new)
            │   │       ├── VidframeConfig.kt          START_URL, ID uBO, DoH endpoints, режим навигации
            │   │       ├── NavigationAllowlist.kt     правила хостов и путей
            │   │       ├── VidframeRequestInterceptor.kt  блокировка, страницы strict/warn, внешний chooser
            │   │       ├── DohFailover.kt             TRR-only + переключение endpoint по health-check
            │   │       ├── UboProvisioner.kt          первая установка uBO, автоподтверждение разрешений
            │   │       ├── VidframeLauncherActivity.kt запуск m.youtube.com как standalone web-app
            │   │       └── VidframeIntegration.kt     единая точка старта
            │   └── res/values/vidframe_strings.xml    (new) дисклеймер, подписи ярлыков
            ├── test/java/org/mozilla/fenix/vidframe/
            │   └── NavigationAllowlistTest.kt         (new) Robolectric-тесты allowlist и перехватчика
            └── release/res/
                ├── values/static_strings.xml          (mod) app_name = Vidframe
                ├── values/colors.xml                  (mod) цвет фона иконки
                ├── xml/shortcuts.xml                  (mod) Настройки / Блокировщик / История
                ├── drawable/vidframe_ic_launcher_foreground.xml (new)
                └── mipmap-anydpi-v26/ic_launcher{,_round}.xml   (new) адаптивная иконка
```

## Правила сопровождения

* Новая логика — только в `org.mozilla.fenix.vidframe` и `vidframe/`.
* В upstream-файлах держите изменения однострочными или блочными
  с пометкой `Vidframe` рядом: так конфликты rebase легко находить.
* Одна тема — один коммит (брендинг, allowlist, DoH, uBO, UI, префы,
  документация). Тогда при rebase конфликт сразу показывает, какая
  функция сломалась.
