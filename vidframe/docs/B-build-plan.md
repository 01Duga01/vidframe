# B. Пошаговый план реализации (путь 1)

Хост: Linux x86_64 (Ubuntu 22.04+/Fedora 39+) или macOS 13+ (Apple Silicon
или Intel). Нужно ≥ 16 ГБ RAM (лучше 32), ≥ 80 ГБ диска, Python 3.9+, git.
На Windows собирать только через WSL2.

## 1. Исходники

Этот репозиторий уже является форком монорепозитория Firefox. Чтобы собирать
Release, а не Nightly, переносим серию коммитов Vidframe на ветку `release`:

```bash
git remote add upstream https://github.com/mozilla-firefox/firefox.git
```

```bash
git fetch upstream release --tags
```

```bash
git checkout -b vidframe-release upstream/release
```

```bash
git cherry-pick <первый-коммит-vidframe>^..<последний-коммит-vidframe>
```

Выбранный тег запишите в `vidframe/config/UPSTREAM`.

## 2. Bootstrap (SDK, NDK, JDK, clang, rust, Gradle)

```bash
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android"
```

`mach bootstrap` кладёт Android SDK, NDK нужной версии (в этом дереве `NDK_VERSION = "r30"`), JDK и эмулятор в
`~/.mozbuild/` (`android-sdk-linux` или `android-sdk-macosx`, `android-ndk-*`,
`jdk`). Версию NDK задаёт дерево, выбирать её вручную не нужно. Если
системный SDK уже есть, не подставляйте его: версии должны совпадать с теми,
что прописаны в `python/mozboot/mozboot/android.py`.

Проверка:

```bash
ls ~/.mozbuild
```

## 3. Выбор ABI (mozconfig)

* `vidframe/mozconfigs/arm64` — основной, `aarch64-linux-android`.
* `vidframe/mozconfigs/armv7` — опциональный, `armv7-linux-androideabi`.

Каждый ABI собирается в свой objdir (`obj-vidframe-arm64`,
`obj-vidframe-armv7`). x86_64 нужен только для эмулятора; для него
скопируйте mozconfig с `--target=x86_64-linux-android`.

Официальный брендинг (`--enable-official-branding`, `MOZILLA_OFFICIAL=1`)
**не включать**: это товарные знаки Firefox.

## 4. uBlock Origin

```bash
vidframe/scripts/fetch-ubo.sh --update
```

Скрипт берёт с AMO текущую версию, пишет в `vidframe/config/ubo.lock`
версию, URL и sha256, скачивает XPI в
`mobile/android/fenix/app/src/main/assets/extensions/ublock_origin.xpi`
и проверяет ID `uBlock0@raymondhill.net`, `manifest_version == 2` и наличие
подписи AMO. Затем закоммитьте `ubo.lock`. Сам XPI в git не попадает (он в
`.gitignore`). Последующие сборки делаются без `--update` и
воспроизводимы по хешу:

```bash
vidframe/scripts/fetch-ubo.sh
```

## 5. Сборка GeckoView и Fenix

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 ./mach build
```

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 ./mach gradle -Pvidframe.navMode=strict fenix:assembleRelease
```

Или одной командой (она же копирует APK в `vidframe/out/`):

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 vidframe/scripts/build.sh
```

Режим «allowlist + предупреждение»:

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 VIDFRAME_NAV_MODE=warn vidframe/scripts/build.sh
```

`./mach package` для GeckoView запускается внутри `./mach build`, отдельно
он нужен только если вы собираете AAR для внешнего потребителя:

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 ./mach package
```

Для быстрой итерации по Kotlin-коду (без пересборки Gecko):

```bash
MOZCONFIG=vidframe/mozconfigs/arm64 ./mach gradle fenix:assembleDebug
```

Линтеры Android-кода перед коммитом:

```bash
./mach gradle ktfmtFormat detekt
```

## 6. Второй ABI

```bash
MOZCONFIG=vidframe/mozconfigs/armv7 vidframe/scripts/build.sh
```

Gradle-сплиты Fenix выдают отдельный APK на ABI. Берётся тот, для
которого собран Gecko. `build.sh` выбирает его по имени; сплиты других
ABI не содержат `libxul.so` и не распространяются. Универсальный APK
(fat AAR из нескольких objdir) возможен
(`mobile/android/config/mozconfigs/*/release-fat-aar`), но удваивает размер
и для sideload/F-Droid не нужен.

## 7. Ключ подписи (один раз)

```bash
mkdir -p vidframe/keystore
```

```bash
keytool -genkeypair -v -keystore vidframe/keystore/release.jks -alias vidframe -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Vidframe"
```

Храните `release.jks` вне репозитория, с резервной копией: потеря ключа
означает, что пользователи не смогут обновиться поверх установленной версии.

## 8. Подпись

```bash
vidframe/scripts/sign.sh vidframe/out/vidframe-arm64-v8a-release-unsigned.apk
```

Debug-сборки (`assembleDebug`) Gradle подписывает debug-ключом
`~/.android/debug.keystore` автоматически. `assembleRelease` при локальной
сборке тоже подписывается debug-ключом (см. `releaseTemplate` в
`fenix/app/build.gradle`), если не передан `-PdisableDebugSigning`. Для
публикации всегда переподписывайте release-ключом через `sign.sh`.

## 9. Установка и проверка устройства

```bash
vidframe/scripts/check-device.sh
```

```bash
adb install -r vidframe/out/vidframe-arm64-v8a-release.apk
```

```bash
adb logcat -s VidframeNav VidframeDoH VidframeUbo Vidframe
```

## 10. Проверки

Пройдите чек-лист из [G](G-release-checklist.md), DoH — по
[F § DoH](F-pitfalls.md#как-проверить-что-doh-не-утекает).

## 11. Публикация

См. [distribution-and-legal.md](distribution-and-legal.md).

## 12. Каждые 4 недели (новый Release Fenix)

```bash
git fetch upstream release --tags
```

```bash
git rebase --onto upstream/release <старая-база> vidframe-release
```

```bash
vidframe/scripts/fetch-ubo.sh --update
```

После rebase: собрать, пройти G, обновить `config/UPSTREAM`, поднять
версию. `versionCode` генерирует Fenix (`Config.generateFennecVersionCode`),
он растёт монотонно сам.
