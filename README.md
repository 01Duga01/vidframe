# Vidframe

Android-клиент для мобильной версии YouTube на движке Firefox (GeckoView): одно окно без адресной строки,
блокировщик рекламы uBlock Origin из коробки, DNS over HTTPS и ничего лишнего.

> Независимый проект с открытым исходным кодом. Не связан с Google LLC, YouTube и Mozilla, не одобрен и не
> спонсируется ими. YouTube — товарный знак Google LLC, Firefox — товарный знак Mozilla Foundation.

## Возможности

- Сразу открывает `m.youtube.com`, переходы на другие сайты закрыты
- uBlock Origin ставится при первом запуске в самой свежей версии и раз в день проверяет обновления
- DoH на AdGuard DNS; в России — встроенный обход DPI без VPN и root
- Мини-плеер, «картинка в картинке», масштаб видео щипком, сайт в масштабе 80 %, чтобы элементы не налезали друг на друга
- Тёмная тема по системе, анимации в стиле официального приложения
- Без телеметрии, рекламы Mozilla и онбординга

## Сборка

Нужны Linux или macOS, ≥ 16 ГБ RAM. Команды запускаются из корня репозитория.

```bash
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android Artifact Mode"
```

```bash
export MOZCONFIG=$PWD/vidframe/mozconfigs/arm64-artifact
```

```bash
vidframe/scripts/fetch-ubo.sh --update
```

```bash
./mach build && ./mach gradle fenix:assembleRelease
```

Тесты и сборка одной командой: `vidframe/scripts/test-and-build.sh`. Подпись APK: `vidframe/scripts/sign.sh`.

## Документация

Подробности, архитектура, настройки сборки и чек-лист релиза — в [vidframe/README.md](vidframe/README.md)
и [vidframe/docs/](vidframe/docs/).

## Лицензии

Код Firefox — в основном MPL-2.0 (полный перечень: [LICENSE](LICENSE)), изменения Vidframe — MPL-2.0. uBlock Origin — GPL-3.0, поставляется без изменений
([исходники](https://github.com/gorhill/uBlock)).
