# F. Подводные камни

## Что ломается при обновлении Fenix

| Место | Риск | Как заметить |
|---|---|---|
| `Settings.kt` | Ключи переименовываются, делегаты меняют сигнатуры, флаги переезжают в Nimbus (так было с `doh.showUi`, `termsOfUsePrompt`). Конфликт rebase или, хуже, новое поле, которое по умолчанию снова `true`. | После rebase: `git diff upstream/release -- …/utils/Settings.kt` и поиск новых `booleanPreference(… default = true`, связанных с телеметрией, sponsored, onboarding. |
| `AppRequestInterceptor.onLoadRequest` | Изменение сигнатуры или добавление новых интерсепторов в список. Новый интерсептор может снова открыть app links. | Компиляция + пункт G «ссылка на youtube.com не уходит в нативное приложение». |
| `AndroidManifest.xml` | В `IntentReceiverActivity` появляются новые `intent-filter` для http(s), и приложение снова становится «браузером». | `adb shell cmd package query-activities -a android.intent.action.VIEW -d https://example.com` не должен выдавать Vidframe. |
| PWA (`feature-pwa`, `ExternalAppBrowserActivity`) | Fenix периодически переделывает custom tabs и PWA (тулбар-редизайн). Может измениться поведение вне scope или меню. | G: логин, выход за scope, «назад». |
| Новые onboarding/промо-экраны | Появляются с новыми Nimbus-фичами, могут показываться в HomeActivity при открытии ярлыка «Настройки». | Чистая установка → ярлык «Настройки». |
| Иконка и строки | Новые ресурсы с брендом Firefox (сплэш HomeActivity, уведомления, виджеты). | Ручной просмотр `release/res` и экранов, куда ведут ярлыки. |
| `geckoview-prefs.js` | Mozilla может задать те же префы по-другому. Наш `#include` стоит в конце файла и поэтому побеждает, но новый механизм (например, Nimbus `setPref`) способен перезаписать значение в рантайме. | `about:support` в debug-сборке, раздел «Important modified preferences». |
| Gradle / AGP | `applicationId`, `sharedUserId`, `buildConfigField` в `build.gradle` конфликтуют при смене DSL. | Сборка. |

**`sharedUserId`.** Никогда не возвращайте `org.mozilla.firefox.sharedID`
и `applicationId` Mozilla: приложение с чужой подписью не установится
рядом с Firefox. Менять `sharedUserId` после первого публичного релиза
нельзя, иначе пользователь потеряет данные.

## Что ломается при обновлении uBO

* **Новые разрешения.** Если новая версия uBO запросит новые permissions,
  `DefaultAddonUpdater` покажет запрос на обновление (`onUpdatePermissionRequest`),
  а до подтверждения оставит старую версию. Это сделано намеренно.
  Автоматически принимаются только разрешения **первой** установки.
* **Переход на MV3 на AMO.** `fetch-ubo.sh` откажется от XPI с
  `manifest_version != 2`. Если gorhill когда-нибудь выпустит полный uBO под
  MV3 для Firefox, проверку придётся сознательно пересмотреть.
* **Hard blocklist / отзыв подписи** (маловероятно): аддон отключится
  у всех. Это контролирует `firefox.settings.services.mozilla.com`.
* **uBO на Android с недостатком памяти** при большом наборе списков
  (особенно на 2 ГБ RAM) может перезапускаться процесс расширений.
  Держите набор списков по умолчанию.
* **Встроенная версия (F-Droid-вариант, см. distribution)** не обновляется
  с AMO. Обновляются только фильтры.

**Фильтры uBO обновляются независимо от APK.** uBO сам скачивает списки
(`ublockorigin.github.io`, `ublockorigin.pages.dev`, `cdn.jsdelivr.net`,
`easylist.to`, `pgl.yoyo.org`, `filters.adtidy.org` и др.). Это запросы
расширения, а не навигации, поэтому `NavigationAllowlist` их не трогает.
AdGuard DNS эти хосты не блокирует. Проверка: uBO → Настройки → Списки
фильтров → «Обновить сейчас» и даты обновления.

## Как m.youtube.com детектит блокировщики и что делать

* **Объявления в ответе плеера (`/youtubei/v1/player`).** Реклама
  приходит в JSON и вставляется в видео. uBO режет её scriptlet'ами
  (`json-prune`, `set-constant` и др.) из списка *uBlock filters*. Если
  YouTube меняет схему ответа, реклама появляется до обновления фильтров.
  Обычно это часы или дни, и APK для этого обновлять не нужно.
* **Antiblock-попапы** («Блокировщики рекламы нарушают Условия
  использования»). Их тоже покрывают списки uBO, в частности *uBlock filters
  – Quick fixes*, который обновляется чаще остальных. Не добавляйте свои
  CSS-правила против этого попапа в `vidframe-ui`: они устаревают быстрее,
  чем выходят релизы APK.
* **Детект по задержкам, по отсутствию запросов к `doubleclick.net` /
  `googleads.g.doubleclick.net`.** Эти хосты блокирует и AdGuard DNS.
  Совпадение двух блокировок ничего не ломает. Если ломается плеер,
  временно переключите DoH на `unfiltered.adguard-dns.com`, чтобы понять,
  какой слой виноват.
* **Ограничение «вне браузера».** YouTube различает клиентов по UA.
  Vidframe отправляет стандартный UA GeckoView/Firefox for Android. Не
  меняйте его на Chrome-UA: это ломает сайт и выглядит как подделка
  клиента.
* **Что делать при поломке:** сначала обновить фильтры uBO, потом
  проверить с выключенным uBO (на m.youtube.com: значок uBO → выключить для
  сайта), потом посмотреть [issues uAssets](https://github.com/uBlockOrigin/uAssets/issues).
  В UI и описании проекта не обещайте пользователю «без рекламы».

## Принудительная мобильная версия

* Fenix отправляет мобильный UA (`USER_AGENT_MODE_MOBILE`), а в PWA-оболочке
  нет переключателя «Версия для ПК».
* `START_URL` жёстко задан как `https://m.youtube.com/` (без параметров).
  `youtube.com` и `www.youtube.com` при мобильном UA сами редиректят на
  `m.`; старт сразу с `m.` убирает этот редирект и мелькание custom-tab-панели
  (она появляется вне PWA-scope). Если на каком-то планшете YouTube отдаёт
  десктопную версию, верните в `START_URL` параметры `?app=m&persist_app=1`
  (cookie `PREF` с выбором мобильного интерфейса).
* `www.youtube.com` разрешён (логин и ссылки могут вести туда), но он вне
  PWA-scope. Fenix тогда покажет узкую custom-tab-панель. Чтобы её не было,
  оставьте ссылки на `www.` как есть: m.youtube.com сам редиректит
  мобильный UA обратно.

## Производительность видео

* На Android Gecko отдаёт в MSE только AV1 и H.264 (`media.mediasource.vp9.enabled` = `@IS_NOT_MOBILE@`).
  Сайт выбирает AV1, если он объявлен. Без аппаратного AV1 Gecko декодирует его программно (dav1d):
  это пропуски кадров, нагрев и расход батареи. `VideoCodecTuning` сканирует `MediaCodecList`
  (результат кэшируется по `Build.FINGERPRINT`) и при отсутствии аппаратного AV1 ставит
  `media.av1.enabled=false` на default-ветке. Тогда сайт отдаёт H.264, который на Android
  везде аппаратный, но ограничен 1080p.
* Проверка: в debug-сборке `about:support` → Media, или на видео «Статистика для сисадминов»:
  кодек должен быть `avc1`, если в логе `VidframeCodec: Hardware AV1 decoder: false`.
* С нативным приложением полностью не сравняться: оно использует ExoPlayer, предзагрузку и
  собственный протокол, а Vidframe — веб-версию. Узкие места веб-версии: первая загрузка JS
  сайта и программный AV1. Второе устранено, первое кэшируется после первого запуска.

## Shorts

* Неплавная лента Shorts бывает из-за сайта (предзагрузка нескольких роликов) и из-за
  программного AV1 (см. выше). Из-за самого клиента — когда страница слушает касания с
  `passive: false`: тогда Gecko не может прокручивать асинхронно и ждёт скрипт на каждом свайпе.
  `player.js` поэтому вешает такие обработчики только на время полноэкранного режима.

## Обход DPI

См. [dpi-bypass.md](dpi-bypass.md). Если после обновления Fenix видео перестали грузиться только
в РФ, сначала проверьте, что префы `network.proxy.*` не перезаписываются новым кодом Fenix.

## Как проверить, что DoH не утекает

1. **На устройстве, внутри приложения.** В release-сборке `about:`-страницы
   недоступны из PWA (нет адресной строки). Используйте debug-сборку
   (`fenix:assembleDebug`, тот же код):

   ```bash
   adb shell am start -a android.intent.action.VIEW -d "about:networking#dns" -n io.github.vidframe.fenix.debug/org.mozilla.fenix.IntentReceiverActivity
   ```

   В `about:networking#dns` у записей должна стоять колонка **TRR = true**,
   а в `about:networking#dnslookuptool` поиск `m.youtube.com` должен
   показывать «TRR: true». В debug-сборке доступен `about:config`:
   `network.trr.mode = 3`, `network.trr.uri` = текущий endpoint. Если
   intent не открылся, дойдите до адресной строки HomeActivity (ярлык
   «Настройки» → назад) и введите адрес вручную.
2. **Сторонние тесты.** Временно в debug-сборке добавьте в
   `NavigationAllowlist` `adguard.com` и `dnsleaktest.com`, откройте
   <https://adguard.com/en/test.html>: должно быть «AdGuard DNS is working».
   На <https://dnsleaktest.com> (extended test) должны быть только серверы
   AdGuard. В release эти домены не добавляйте.
3. **Снаружи устройства (лучше всего).** На Android **выключите Private
   DNS**, иначе DoT ОС скроет утечки. Раздайте Wi-Fi с Linux-хоста и
   запустите:

   ```bash
   sudo vidframe/scripts/verify-doh.sh wlan0 192.168.12.34 180
   ```

   В открытом DNS не должно быть `*.youtube.com`, `*.googlevideo.com`,
   `*.ytimg.com`. Допустимы только lookup'ы `dns.adguard-dns.com` (bootstrap
   Gecko) и запросы `DohFailover` к резервным endpoint'ам.
4. **Источники утечек, уже закрытые:** captive portal и connectivity
   service (обходят TRR; выключены в `vidframe-prefs.js`), DNS prefetch,
   speculative connect, WebRTC (выключен). **Не закрыто нашим кодом:**
   DNS других приложений и самой ОС (обновления, Play Services). Для этого
   нужен системный Private DNS или VPN.
5. **Bootstrap без системного DNS (опционально).** Задайте
   `pref("network.trr.bootstrapAddr", "94.140.14.14");` и тогда даже имя
   резолвера не уйдёт в системный DNS. Минус в том, что bootstrap
   привязывается к одному провайдеру и failover на Quad9 перестаёт работать
   без смены префа. Поэтому по умолчанию префа нет.

### Управляемые устройства (MDM)

Если клиент ставится на устройства под управлением (Android Enterprise),
добавьте системный уровень: `Private DNS = dns.adguard-dns.com` (DoT) через
политику `setGlobalPrivateDnsModeSpecifiedHost` (DevicePolicyManager,
Android 10+). Gecko при этом продолжит использовать собственный TRR, и
у вас будет две независимые защиты.

### Ограничения TRR-only (mode 3)

* Нет отката на системный DNS. Если все три endpoint'а недоступны,
  страницы не грузятся (это и есть «Locked»). `DohFailover` переключает
  endpoint при старте и при каждой смене сети.
* Captive portal (отели, транспорт) нужно открывать в системном браузере.
* Настройка «Increased» (mode 2) дала бы откат на системный DNS, то есть
  утечку. Не используйте её.

## Какие политики молча игнорируются в Fenix

**Все.** На Android нет движка enterprise policies (см. A, п. 1), и Fenix
не пишет в лог предупреждения о найденном `policies.json`. Отдельно стоит
упомянуть и другие механизмы, которые тоже молча не работают в release:

* `distribution/preferences.json`, `distribution/extensions/` — наследие
  Fennec, удалено.
* `user.js` в профиле — Gecko его читает, но положить файл в приватный
  каталог приложения без root нельзя.
* `/data/local/tmp/<pkg>-geckoview-config.yaml` — только для debuggable.
* Префы, которые Fenix перезаписывает из Kotlin при каждом старте
  (`network.trr.*`, `dom.security.https_only_mode`, tracking protection,
  `javascript.enabled`, cookies). Если задать их только в `vidframe-prefs.js`,
  Fenix их перезапишет. Поэтому для DoH и HTTPS-only изменены и
  `Settings.kt`, и префы.

## WebView vs GeckoView в этом сценарии

| | GeckoView (Vidframe) | Android System WebView |
|---|---|---|
| Движок | Gecko, собранный вместе с приложением | Chromium, обновляется через Play Store отдельно |
| Расширения | Полный WebExtensions MV2, uBO работает | Нет. Блокировка возможна только через `shouldInterceptRequest` (без косметики и scriptlet'ов) |
| DoH | Свой TRR в Gecko, независимый от ОС | Только системный Private DNS |
| Детект YouTube | Обычный Firefox for Android | WebView помечается в UA (`; wv`) и в `X-Requested-With`; Google ограничивает логин в WebView («This browser or app may not be secure») |
| Логин Google | Работает (полноценный браузер) | Заблокирован политикой Google для встроенных WebView |
| Размер | +~80 МБ на ABI | ~5 МБ |
| Обновления безопасности | Вместе с релизами Vidframe (каждые 4 недели), **ответственность на вас** | ОС/Play Store |

Vidframe **не использует** системный WebView ни для чего. Fenix
открывает в WebView только системные Custom Tabs других приложений, а
мы его не вызываем. `check-device.sh` выводит версию WebView для
информации; на работу клиента она не влияет.

**Безопасность.** Отставание от upstream-релиза больше чем на 1–2 недели
означает публичные уязвимости Gecko. Выпускайте rebase в течение нескольких
дней после каждого релиза и dot-релиза Firefox for Android.

## Прочее

* **Shortcuts с `targetPackage`.** `shortcuts.xml` не поддерживает
  плейсхолдеры, поэтому `io.github.vidframe.app` в нём записан явно.
  При смене `applicationId` меняйте его и там.
* **App links на youtube.com.** На Android 12+ непроверенные ссылки по
  умолчанию открываются в браузере. Пользователь может включить «Открывать
  поддерживаемые ссылки» в настройках приложения Vidframe. Верифицировать
  домен youtube.com мы не можем, и пытаться не нужно.
* **Меню custom-tab вне scope** содержит пункт «Открыть в Vidframe», он ведёт
  в HomeActivity с адресной строкой. Allowlist действует и там, но если
  нужен «киоск», удалите этот пункт (`CustomTabToolbarMenu`) отдельным
  патчем.
