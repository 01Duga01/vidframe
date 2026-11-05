# Vidframe

Android-клиент для просмотра одного видеосервиса (мобильная версия
`m.youtube.com`), собранный из исходников Firefox for Android (Fenix) с
движком GeckoView. Только Android (arm64-v8a, опционально armeabi-v7a).

> **Независимость проекта.** Vidframe — независимый проект с открытым
> исходным кодом. Он не связан с Google LLC, YouTube и Mozilla, не одобрен и
> не спонсируется ими. YouTube — товарный знак Google LLC. Firefox — товарный
> знак Mozilla Foundation; Vidframe собирается из исходного кода Firefox for
> Android (MPL-2.0) с неофициальным брендингом и **не является** Firefox.
> uBlock Origin (GPL-3.0) поставляется как универсальный блокировщик
> контента, включённый по умолчанию; проект не обещает «обход рекламы».

## Что делает клиент

| Функция | Реализация |
|---|---|
| Стартовая страница жёстко зафиксирована на `https://m.youtube.com/`, одно окно без адресной строки, поиска Fenix и вкладок | `VidframeLauncherActivity` открывает сайт как standalone web-app (PWA-оболочка Fenix) и перезаписывает манифест при каждом запуске; `VidframeHomeGuard` не даёт показать домашний экран, тулбар и вкладки Fenix |
| Только разрешённые домены | `NavigationAllowlist` + `VidframeRequestInterceptor` (хук в `AppRequestInterceptor`) |
| Режим `strict` / `warn` | Gradle-свойство `-Pvidframe.navMode=strict\|warn` |
| uBlock Origin ставится до первой загрузки YouTube, обновляется с AMO | `UboProvisioner` ставит подписанный XPI из assets и сам отвечает на запрос разрешений; launcher ждёт его (до 20 с); release-сборка без XPI падает на `preReleaseBuild` |
| DoH на AdGuard, TRR-only, failover | `vidframe-prefs.js` (с первого запроса) + `DohFailover` (рантайм) |
| Телеметрия, Pocket, спонсорский контент выключены | Значения по умолчанию в `Settings.kt`, `vidframe-prefs.js` |
| Настройки, uBO, история | Ярлыки долгого нажатия на иконку (`shortcuts.xml`) |
| Масштаб сайта 50–100 %, авто или вручную | `PageScale`: «Авто» = ширина экрана в dp / 560 / системный масштаб шрифта (с шагом 5 %), либо фиксированное значение в Настройки → «Масштаб сайта» (после выбора приложение перезапускается). Gecko получает `displayDensityOverride` = плотность × масштаб. Значение по умолчанию для сборки: `-Pvidframe.contentScale=auto\|0.5…1.0` |
| Быстрый холодный старт | Системный SplashScreen с первого кадра; launcher сам обрабатывает web-app intent и открывает окно видео без промежуточной `IntentReceiverActivity`; манифест проверяется раз за процесс; соединения с `m.youtube.com`, `i.ytimg.com`, `yt3.ggpht.com` открываются заранее (`speculativeConnect`) |
| Автоматическая тёмная тема | Следует системной теме (Android 9+), на Android 8 — режиму энергосбережения; сайт получает `prefers-color-scheme`; смена темы не перезагружает играющее видео; сплэш и фон до первой отрисовки в цвет сайта (`#FFFFFF` / `#0F0F0F`) |
| Масштаб видео щипком | `vidframe-ui/player.js`: в полноэкранном режиме щипок до 8×, «прилипание» к «Растянуто по размеру экрана» и к исходному размеру, перемещение двумя пальцами |
| Мини-плеер | При уходе со страницы видео (назад, «Главная», поиск) видео продолжает играть в плашке над нижней панелью: пауза, закрыть, тап — вернуться к видео с того же места. Кнопка «Домой» Android во время воспроизведения — системное окно «картинка в картинке» (`VidframePictureInPicture`) |
| Иконка и экран загрузки | Адаптивная иконка (градиент, знак-рамка с «V», монохромный слой для тематических иконок Android 13+); анимированный значок на сплэше |
| Всегда свежий uBO на первом запуске | `UboProvisioner` сравнивает вшитую версию (`ubo.lock` → `BuildConfig.VIDFRAME_UBO_VERSION`) с текущей на AMO; если на AMO новее — скачивает её (SHA-256 из AMO, подпись проверяет Gecko), иначе ставит вшитую. Пока идёт установка — экран «Устанавливаем блокировщик рекламы» (RU/EN по языку системы) с прогрессом; через 20 с можно продолжить без ожидания |
| «Скачать» через savefrom.net | Кнопка «Скачать» на странице видео открывает savefrom.net с ссылкой на ролик во внешнем браузере (`VideoDownloader`, `vidframe-download://`). `-Pvidframe.downloader=` (пусто) оставляет кнопку сайта |
| Анимации | Сплэш → экран настройки с «дышащим» значком и вращающимся кольцом; переход к сайту с масштабированием; мини-плеер-карточка с пружинным появлением, свайпом в сторону (закрыть) и вверх (развернуть); плавная «доводка» зума; морфинг иконок; учитывается системное «Убрать анимации» |
| Обход DPI без VPN и root | `vidframe/dpi/`: встроенный локальный SOCKS5-прокси режет TLS ClientHello внутри SNI на две TLS-записи и три TCP-сегмента (техника tpws/ByeDPI); Gecko ходит через него, имена резолвит сам по DoH, HTTP/3 выключается. Режим `-Pvidframe.dpiBypass=auto\|on\|off`, `auto` включает для российских сетей/SIM/региона. Подробности: [docs/dpi-bypass.md](docs/dpi-bypass.md) |
| Видео на аппаратном декодере | `VideoCodecTuning`: без аппаратного AV1-декодера выключает `media.av1.enabled`, и сайт отдаёт H.264 |
| Поиск Fenix убран | Нет домашнего экрана и адресной строки; пункт «Поиск» в настройках скрыт; удалены виджеты поиска, `SEARCH`/`WEB_SEARCH`/`ASSIST` intent-фильтры и deep links `settings_search_engine`, `install_search_widget`. Поиск внутри самого сайта остаётся. |

## Где что лежит

Проект — это форк монорепозитория Firefox. Все изменения либо в этом
каталоге (`vidframe/`), либо в небольшом наборе файлов Fenix/GeckoView,
перечисленных в [docs/E-repo-tree.md](docs/E-repo-tree.md).

## Документация

* [A. Сравнение путей и выбор версии](docs/A-paths-and-version.md)
* [B. Пошаговый план сборки](docs/B-build-plan.md)
* [C. policies.json и его Android-эквивалент](docs/C-policies.md)
* [D. userChrome.css / userContent.css на Android](docs/D-ui-css.md)
* [E. Структура репозитория](docs/E-repo-tree.md)
* [F. Подводные камни](docs/F-pitfalls.md)
* [G. Чек-лист перед релизом](docs/G-release-checklist.md)
* [Распространение, подпись, лицензии](docs/distribution-and-legal.md)

## Быстрая сборка (arm64-v8a)

```bash
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android"
```

```bash
vidframe/scripts/fetch-ubo.sh
```

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 vidframe/scripts/build.sh
```

```bash
vidframe/scripts/sign.sh vidframe/out/vidframe-arm64-v8a-release-unsigned.apk
```

## Лицензии

* Код Firefox/Fenix/GeckoView и изменения Vidframe в этих файлах — MPL-2.0.
* uBlock Origin — GPL-3.0, распространяется в неизменном виде (подписанный
  AMO XPI); исходный код: <https://github.com/gorhill/uBlock>. Версия и хеш —
  в `vidframe/config/ubo.lock`.
